package io.github.qwqgong.androidcyaml;

import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android implementation of the narrow platform contract consumed by the
 * embedded mihomo executable. The core owns configuration semantics; this
 * class owns only Android APIs that the core cannot call directly.
 */
final class AndroidPlatformBridge implements Closeable {
    private static final String TAG = "AndroidCyaml/Platform";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MILLIS = 20_000;

    private final AndroidVpnService service;
    private final String socketName;
    private final LocalServerSocket serverSocket;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private final Object tunnelLock = new Object();

    private volatile boolean closed;
    private ParcelFileDescriptor tunnel;
    private TunSpec activeSpec;

    AndroidPlatformBridge(AndroidVpnService service) throws IOException {
        this.service = Objects.requireNonNull(service);
        socketName = "androidcyaml-platform-" + android.os.Process.myPid() + "-" + UUID.randomUUID();
        serverSocket = new LocalServerSocket(socketName);
        acceptExecutor.execute(this::acceptLoop);
    }

    String coreSocketAddress() {
        return "@" + socketName;
    }

    boolean hasUsableTunnel() {
        synchronized (tunnelLock) {
            return tunnel != null && tunnel.getFileDescriptor().valid();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                LocalSocket connection = serverSocket.accept();
                requestExecutor.execute(() -> handleConnection(connection));
            } catch (IOException exception) {
                if (!closed) {
                    Log.e(TAG, "Android platform accept loop failed", exception);
                }
                return;
            }
        }
    }

    private void handleConnection(LocalSocket connection) {
        try {
            connection.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            String line = readBoundedLine(connection.getInputStream(), MAX_REQUEST_BYTES);
            if (line == null || line.isBlank()) {
                sendError(connection, "empty Android platform request");
                return;
            }
            JSONObject request = new JSONObject(line);
            String operation = request.optString("operation", "");
            switch (operation) {
                case "open_tun" -> handleOpenTun(connection, request);
                case "find_process" -> handleFindProcess(connection, request);
                default -> sendError(connection, "unsupported Android platform operation: " + operation);
            }
        } catch (IOException | JSONException | RuntimeException exception) {
            Log.w(TAG, "Android platform request failed", exception);
            try {
                sendError(connection, usefulMessage(exception));
            } catch (IOException ignored) {
                // The peer may already have closed after a transport failure.
            }
        } finally {
            try {
                connection.close();
            } catch (IOException ignored) {
                // Request connections are one-shot.
            }
        }
    }

    private void handleOpenTun(LocalSocket connection, JSONObject request)
            throws IOException, JSONException {
        JSONObject tunObject = request.optJSONObject("tun");
        if (tunObject == null) {
            sendError(connection, "open_tun request omitted TUN options");
            return;
        }
        TunSpec spec = TunSpec.fromJson(tunObject);
        ParcelFileDescriptor descriptor = ensureTunnel(spec);
        JSONObject response = new JSONObject().put("ok", true);
        sendResponse(connection, true, response, descriptor.getFileDescriptor());
    }

    private void handleFindProcess(LocalSocket connection, JSONObject request)
            throws IOException, JSONException {
        String network = request.optString("network", "");
        int protocol;
        if (network.startsWith("tcp")) {
            protocol = OsConstants.IPPROTO_TCP;
        } else if (network.startsWith("udp")) {
            protocol = OsConstants.IPPROTO_UDP;
        } else {
            sendError(connection, "unsupported process lookup network: " + network);
            return;
        }

        InetSocketAddress source = endpoint(
                request.getString("sourceAddress"),
                request.getInt("sourcePort")
        );
        InetSocketAddress destination = endpoint(
                request.getString("destinationAddress"),
                request.getInt("destinationPort")
        );
        ConnectivityManager connectivityManager = service.getSystemService(
                ConnectivityManager.class
        );
        if (connectivityManager == null) {
            sendError(connection, "ConnectivityManager is unavailable");
            return;
        }
        int uid = connectivityManager.getConnectionOwnerUid(protocol, source, destination);
        if (uid == android.os.Process.INVALID_UID) {
            sendError(connection, "connection owner was not found");
            return;
        }

        String packageName = packageNameForUid(uid);
        JSONObject response = new JSONObject()
                .put("ok", true)
                .put("uid", uid)
                .put("packageName", packageName);
        sendResponse(connection, true, response, null);
    }

    private ParcelFileDescriptor ensureTunnel(TunSpec spec) throws IOException {
        synchronized (tunnelLock) {
            if (spec.equals(activeSpec)
                    && tunnel != null
                    && tunnel.getFileDescriptor().valid()) {
                return tunnel;
            }

            ParcelFileDescriptor candidate = establish(spec);
            ParcelFileDescriptor previous = tunnel;
            tunnel = candidate;
            activeSpec = spec;
            closeQuietly(previous);
            return candidate;
        }
    }

    private ParcelFileDescriptor establish(TunSpec spec) throws IOException {
        if (spec.inet4Address().isEmpty() && spec.inet6Address().isEmpty()) {
            throw new IOException("mihomo did not provide a TUN interface address");
        }
        if (!spec.includePackage().isEmpty() && !spec.excludePackage().isEmpty()) {
            throw new IOException("Android cannot combine tun.include-package and tun.exclude-package");
        }

        VpnService.Builder builder = service.newPlatformBuilder()
                .setSession(service.getString(R.string.app_name))
                .setMtu(spec.mtu())
                .setBlocking(false)
                .setMetered(false)
                .setConfigureIntent(service.openAppPendingIntent());

        boolean hasIpv4 = addAddresses(builder, spec.inet4Address());
        boolean hasIpv6 = addAddresses(builder, spec.inet6Address());
        if (spec.autoRoute()) {
            addRoutes(builder, spec.inet4RouteAddress(), hasIpv4, false);
            addRoutes(builder, spec.inet6RouteAddress(), hasIpv6, true);
            addExcludedRoutes(builder, spec.inet4RouteExcludeAddress());
            addExcludedRoutes(builder, spec.inet6RouteExcludeAddress());
        }
        for (String address : spec.dnsServerAddress()) {
            builder.addDnsServer(parseNumericAddress(address));
        }
        applyPackageRouting(builder, spec);

        ParcelFileDescriptor established = builder.establish();
        if (established == null) {
            throw new IOException("Android did not establish the VpnService TUN interface");
        }
        Log.i(TAG, "Established mihomo TUN: " + spec.summary());
        return established;
    }

    private void applyPackageRouting(VpnService.Builder builder, TunSpec spec) throws IOException {
        PackageManager packageManager = service.getPackageManager();
        String ownPackage = service.getPackageName();
        try {
            if (!spec.includePackage().isEmpty()) {
                int accepted = 0;
                for (String packageName : spec.includePackage()) {
                    if (ownPackage.equals(packageName)) {
                        continue;
                    }
                    try {
                        packageManager.getPackageInfo(packageName, 0);
                        builder.addAllowedApplication(packageName);
                        accepted++;
                    } catch (PackageManager.NameNotFoundException exception) {
                        Log.w(TAG, "Ignoring unavailable included package " + packageName);
                    }
                }
                if (accepted == 0) {
                    throw new IOException("tun.include-package did not match an installed application");
                }
                return;
            }

            builder.addDisallowedApplication(ownPackage);
            for (String packageName : spec.excludePackage()) {
                if (ownPackage.equals(packageName)) {
                    continue;
                }
                try {
                    packageManager.getPackageInfo(packageName, 0);
                    builder.addDisallowedApplication(packageName);
                } catch (PackageManager.NameNotFoundException exception) {
                    Log.w(TAG, "Ignoring unavailable excluded package " + packageName);
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("Android package routing failed", exception);
        }
    }

    private static boolean addAddresses(VpnService.Builder builder, List<String> prefixes)
            throws IOException {
        boolean added = false;
        for (String value : prefixes) {
            IpPrefix prefix = parsePrefix(value);
            builder.addAddress(prefix.getAddress(), prefix.getPrefixLength());
            added = true;
        }
        return added;
    }

    private static void addRoutes(
            VpnService.Builder builder,
            List<String> prefixes,
            boolean familyAvailable,
            boolean ipv6
    ) throws IOException {
        if (!familyAvailable) {
            return;
        }
        if (prefixes.isEmpty()) {
            builder.addRoute(ipv6 ? "::" : "0.0.0.0", 0);
            return;
        }
        for (String value : prefixes) {
            IpPrefix prefix = parsePrefix(value);
            builder.addRoute(prefix);
        }
    }

    private static void addExcludedRoutes(VpnService.Builder builder, List<String> prefixes)
            throws IOException {
        for (String value : prefixes) {
            builder.excludeRoute(parsePrefix(value));
        }
    }

    private String packageNameForUid(int uid) {
        PackageManager packageManager = service.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            Arrays.sort(packages);
            return packages[0];
        }
        String name = packageManager.getNameForUid(uid);
        return name == null || name.isBlank() ? "uid:" + uid : name;
    }

    private static InetSocketAddress endpoint(String address, int port) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IOException("invalid endpoint port");
        }
        return new InetSocketAddress(parseNumericAddress(address), port);
    }

    private static InetAddress parseNumericAddress(String value) throws IOException {
        if (value == null || value.isBlank() || !value.matches("[0-9A-Fa-f:.]+")) {
            throw new IOException("invalid numeric IP address: " + value);
        }
        return InetAddress.getByName(value);
    }

    private static IpPrefix parsePrefix(String value) throws IOException {
        int separator = value == null ? -1 : value.lastIndexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IOException("invalid IP prefix: " + value);
        }
        try {
            InetAddress address = parseNumericAddress(value.substring(0, separator));
            int length = Integer.parseInt(value.substring(separator + 1));
            return new IpPrefix(address, length);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid IP prefix: " + value, exception);
        }
    }

    private static void sendError(LocalSocket connection, String message) throws IOException {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", false).put("error", message == null ? "unknown error" : message);
        } catch (JSONException exception) {
            throw new IOException("unable to encode platform error", exception);
        }
        sendResponse(connection, false, response, null);
    }

    private static void sendResponse(
            LocalSocket connection,
            boolean success,
            JSONObject response,
            FileDescriptor descriptor
    ) throws IOException {
        OutputStream output = connection.getOutputStream();
        if (descriptor != null) {
            connection.setFileDescriptorsForSend(new FileDescriptor[]{descriptor});
        }
        output.write(success ? 1 : 0);
        output.flush();
        if (descriptor != null) {
            connection.setFileDescriptorsForSend(null);
        }
        output.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String readBoundedLine(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() <= maximumBytes) {
            int value = input.read();
            if (value == -1) {
                return output.size() == 0 ? null : output.toString(StandardCharsets.UTF_8);
            }
            if (value == '\n') {
                return output.toString(StandardCharsets.UTF_8);
            }
            if (value != '\r') {
                output.write(value);
            }
        }
        throw new IOException("Android platform request exceeds 64 KiB");
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        acceptExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        synchronized (tunnelLock) {
            closeQuietly(tunnel);
            tunnel = null;
            activeSpec = null;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Descriptor cleanup is best effort during service teardown.
        }
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private record TunSpec(
            int mtu,
            List<String> inet4Address,
            List<String> inet6Address,
            boolean autoRoute,
            boolean strictRoute,
            List<String> inet4RouteAddress,
            List<String> inet6RouteAddress,
            List<String> inet4RouteExcludeAddress,
            List<String> inet6RouteExcludeAddress,
            List<String> dnsServerAddress,
            List<String> includePackage,
            List<String> excludePackage
    ) {
        static TunSpec fromJson(JSONObject object) throws JSONException, IOException {
            int mtu = object.optInt("mtu", 9000);
            if (mtu < 1280 || mtu > 65_535) {
                throw new IOException("invalid TUN MTU: " + mtu);
            }
            return new TunSpec(
                    mtu,
                    strings(object.optJSONArray("inet4Address")),
                    strings(object.optJSONArray("inet6Address")),
                    object.optBoolean("autoRoute", true),
                    object.optBoolean("strictRoute", false),
                    strings(object.optJSONArray("inet4RouteAddress")),
                    strings(object.optJSONArray("inet6RouteAddress")),
                    strings(object.optJSONArray("inet4RouteExcludeAddress")),
                    strings(object.optJSONArray("inet6RouteExcludeAddress")),
                    strings(object.optJSONArray("dnsServerAddress")),
                    strings(object.optJSONArray("includePackage")),
                    strings(object.optJSONArray("excludePackage"))
            );
        }

        private static List<String> strings(JSONArray array) throws JSONException {
            if (array == null || array.length() == 0) {
                return List.of();
            }
            ArrayList<String> values = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                String value = array.getString(index);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        }

        String summary() {
            return "mtu=" + mtu
                    + " ipv4=" + inet4Address
                    + " ipv6=" + inet6Address
                    + " autoRoute=" + autoRoute;
        }
    }
}
