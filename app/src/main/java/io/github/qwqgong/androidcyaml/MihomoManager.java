package io.github.qwqgong.androidcyaml;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MihomoManager {
    public static final String PROCESS_MATCH_CONFIG = "config";
    public static final String PROCESS_MATCH_STRICT = "strict";
    public static final String PROCESS_MATCH_ALWAYS = "always";
    public static final String PROCESS_MATCH_OFF = "off";

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
    }

    public interface Listener {
        void onCoreStateChanged(State state, String detail);
    }

    public interface ImportCallback {
        void onComplete(boolean success, String detail);
    }

    public interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private static final String TAG = "AndroidCyaml/Mihomo";
    private static final String PREFERENCES = "androidcyaml";
    private static final String SECRET_KEY = "controller_secret";
    private static final String PROCESS_MATCH_OVERRIDE_KEY = "process_match_override";
    private static final String HOST = "127.0.0.1";
    // A stable WebView origin lets Zashboard keep localStorage/IndexedDB data
    // across core and VPN restarts. Fall back to an ephemeral port only when a
    // different process already owns this loopback port.
    private static final int PREFERRED_CONTROLLER_PORT = 17890;
    private static final int MAX_CONFIG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LOG_LINES = 80;
    private static final int MAX_PROCESS_QUERY_BYTES = 512;

    private static volatile MihomoManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object processLock = new Object();
    private final Object logLock = new Object();
    private final ArrayDeque<String> recentLogs = new ArrayDeque<>();
    private final String controllerSecret;

    private volatile State state = State.STOPPED;
    private volatile String detail = "";
    private volatile int controllerPort;
    private volatile String runtimeOverrideError = "";
    private boolean wifiIpv6Unavailable;
    private boolean wifiIpv6OverrideApplied;
    private boolean configuredIpv6Known;
    private boolean configuredIpv6Enabled;
    private ParcelFileDescriptor vpnTunnel;
    private Process process;
    private ProcessLookupBridge processLookupBridge;

    private MihomoManager(Context context) {
        this.context = context.getApplicationContext();
        controllerSecret = loadOrCreateSecret();
    }

    public static MihomoManager getInstance(Context context) {
        MihomoManager local = instance;
        if (local == null) {
            synchronized (MihomoManager.class) {
                local = instance;
                if (local == null) {
                    local = new MihomoManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    static int trimMemoryCachesIfCreated() {
        MihomoManager local = instance;
        if (local == null) {
            return 0;
        }
        synchronized (local.logLock) {
            int removed = local.recentLogs.size();
            local.recentLogs.clear();
            return removed;
        }
    }

    static boolean persistStateForMemoryKill() {
        MihomoManager local = instance;
        if (local == null) {
            return true;
        }
        SharedPreferences preferences = local.context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        SharedPreferences.Editor editor = preferences.edit()
                .putString(SECRET_KEY, local.controllerSecret);
        String processMatchOverride = preferences.getString(
                PROCESS_MATCH_OVERRIDE_KEY,
                PROCESS_MATCH_CONFIG
        );
        if (PROCESS_MATCH_CONFIG.equals(processMatchOverride)) {
            editor.remove(PROCESS_MATCH_OVERRIDE_KEY);
        } else {
            editor.putString(PROCESS_MATCH_OVERRIDE_KEY, processMatchOverride);
        }
        return editor.commit();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        mainHandler.post(() -> listener.onCoreStateChanged(state, detail));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void ensureStarted() {
        controlExecutor.execute(() -> {
            if (state == State.STARTING || isProcessAlive()) {
                return;
            }
            startInternal();
        });
    }

    public void restart() {
        controlExecutor.execute(() -> {
            stopInternal(false);
            startInternal();
        });
    }

    public void activateVpn(ParcelFileDescriptor tunnel, OperationCallback callback) {
        controlExecutor.execute(() -> {
            if (tunnel == null || !tunnel.getFileDescriptor().valid()) {
                postOperationResult(callback, false, "系统返回了无效的 VPN TUN 接口");
                return;
            }
            vpnTunnel = tunnel;
            stopInternal(false);
            boolean success = startInternal();
            if (!success && vpnTunnel == tunnel) {
                vpnTunnel = null;
            }
            postOperationResult(callback, success, detail);
        });
    }

    public void deactivateVpn(ParcelFileDescriptor tunnel, Runnable callback) {
        controlExecutor.execute(() -> {
            if (tunnel == null || vpnTunnel == tunnel) {
                vpnTunnel = null;
            }
            stopInternal(false);
            mainHandler.post(callback);
            startInternal();
        });
    }

    public String getDashboardUrl() {
        int port = controllerPort;
        return "http://" + HOST + ":" + port
                + "/ui/#/setup?hostname=" + HOST
                + "&port=" + port
                + "&secret=" + controllerSecret
                + "&disableUpgradeCore=1&disableTunMode=1&type=clash";
    }

    public boolean isControllerPort(int port) {
        return port > 0 && port == controllerPort;
    }

    public int getControllerPort() {
        return controllerPort;
    }

    public String getProcessMatchOverride() {
        String mode = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PROCESS_MATCH_OVERRIDE_KEY, PROCESS_MATCH_CONFIG);
        return isValidProcessMatchMode(mode) ? mode : PROCESS_MATCH_CONFIG;
    }

    public void setProcessMatchOverride(String mode, OperationCallback callback) {
        controlExecutor.execute(() -> setProcessMatchOverrideInternal(mode, callback));
    }

    public void setWifiIpv6Unavailable(boolean unavailable) {
        controlExecutor.execute(() -> {
            if (wifiIpv6Unavailable == unavailable) {
                return;
            }
            wifiIpv6Unavailable = unavailable;
            if (!isProcessAlive()) {
                return;
            }
            try {
                boolean restarted = reconcileWifiIpv6Override();
                if (!restarted && state == State.RUNNING) {
                    publish(State.RUNNING, runningCoreDetail(vpnTunnel != null));
                }
            } catch (IOException exception) {
                Log.w(TAG, "Unable to update Wi-Fi IPv6 runtime override", exception);
            }
        });
    }

    public void importConfig(Uri source, ImportCallback callback) {
        controlExecutor.execute(() -> importConfigInternal(source, callback));
    }

    private boolean startInternal() {
        synchronized (logLock) {
            recentLogs.clear();
        }
        publish(State.STARTING, "正在准备内置面板和 mihomo 核心…");
        Process candidate = null;
        LocalServerSocket descriptorServer = null;
        Future<Void> descriptorTransfer = null;
        ProcessLookupBridge candidateProcessLookupBridge = null;
        try {
            RuntimeFiles files = ensureRuntimeFiles();
            terminateStaleCores();
            ParcelFileDescriptor activeTunnel = vpnTunnel;
            int activeControllerPort = findAvailableControllerPort();
            controllerPort = activeControllerPort;
            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libmihomo.so");
            if (!binary.isFile()) {
                throw new IOException("APK 中缺少 arm64 mihomo 核心：" + binary);
            }

            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            command.add("-d");
            command.add(files.home.getAbsolutePath());
            command.add("-f");
            command.add(files.config.getAbsolutePath());
            command.add("-ext-ui");
            command.add(files.ui.getAbsolutePath());
            command.add("-ext-ctl");
            command.add(HOST + ":" + activeControllerPort);
            command.add("-secret");
            command.add(controllerSecret);
            if (activeTunnel == null) {
                command.add("-android-disable-tun");
            } else {
                String socketName = "androidcyaml-vpn-" + UUID.randomUUID();
                descriptorServer = new LocalServerSocket(socketName);
                LocalServerSocket transferServer = descriptorServer;
                descriptorTransfer = ioExecutor.submit(
                        () -> sendTunDescriptor(transferServer, activeTunnel)
                );
                command.add("-android-vpn-fd-socket");
                command.add("@" + socketName);

                candidateProcessLookupBridge = new ProcessLookupBridge();
                candidateProcessLookupBridge.start();
                command.add("-android-process-socket");
                command.add("@" + candidateProcessLookupBridge.socketName);
            }

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(files.home)
                    .redirectErrorStream(true);
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

            candidate = builder.start();
            synchronized (processLock) {
                process = candidate;
                processLookupBridge = candidateProcessLookupBridge;
            }
            readCoreLogs(candidate);
            watchForExit(candidate);

            if (descriptorTransfer != null) {
                waitForTunDescriptorTransfer(descriptorTransfer, descriptorServer);
                descriptorTransfer = null;
                descriptorServer = null;
            }

            if (!waitForController(candidate, activeControllerPort, 90, TimeUnit.SECONDS)) {
                String logs = diagnosticLogSummary();
                throw new IOException(logs.isEmpty()
                        ? "mihomo 控制器未在 90 秒内就绪"
                        : "mihomo 启动失败：" + logs);
            }

            if (activeTunnel != null) {
                waitForAndroidTunActive(candidate, activeControllerPort, 5, TimeUnit.SECONDS);
            }

            captureConfiguredIpv6(activeControllerPort);
            try {
                reconcileWifiIpv6Override();
            } catch (IOException exception) {
                Log.w(TAG, "Unable to apply Wi-Fi IPv6 runtime override", exception);
            }
            applyPersistedProcessMatchOverride();

            publish(State.RUNNING, runningCoreDetail(activeTunnel != null));
            return true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (descriptorTransfer != null) {
                descriptorTransfer.cancel(true);
            }
            closeQuietly(descriptorServer);
            if (candidate != null) {
                synchronized (processLock) {
                    if (process == candidate) {
                        process = null;
                    }
                    if (processLookupBridge == candidateProcessLookupBridge) {
                        processLookupBridge = null;
                    }
                }
                stopSpecificProcess(candidate);
            }
            closeQuietly(candidateProcessLookupBridge);
            Log.e(TAG, "Unable to start mihomo", exception);
            publish(State.FAILED, usefulMessage(exception));
            return false;
        }
    }

    private void setProcessMatchOverrideInternal(String mode, OperationCallback callback) {
        if (!isValidProcessMatchMode(mode)) {
            postOperationResult(callback, false, "不支持的进程匹配模式");
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        String previous = getProcessMatchOverride();
        if (PROCESS_MATCH_CONFIG.equals(mode)) {
            preferences.edit().remove(PROCESS_MATCH_OVERRIDE_KEY).apply();
            stopInternal(false);
            boolean success = startInternal();
            if (!success) {
                restoreProcessMatchPreference(preferences, previous);
                stopInternal(false);
                startInternal();
                postOperationResult(callback, false, detail);
                return;
            }
            postOperationResult(callback, true, "已恢复 config.yaml 中的进程匹配设置");
            return;
        }

        preferences.edit().putString(PROCESS_MATCH_OVERRIDE_KEY, mode).apply();
        try {
            if (isProcessAlive()) {
                patchProcessMatchOverride(mode);
                runtimeOverrideError = "";
            } else if (!startInternal() || !runtimeOverrideError.isEmpty()) {
                throw new IOException(
                        runtimeOverrideError.isEmpty() ? detail : runtimeOverrideError
                );
            }
            postOperationResult(callback, true, "进程匹配已覆写为 " + mode);
        } catch (IOException exception) {
            restoreProcessMatchPreference(preferences, previous);
            if (isProcessAlive() && !PROCESS_MATCH_CONFIG.equals(previous)) {
                try {
                    patchProcessMatchOverride(previous);
                } catch (IOException restoreException) {
                    Log.w(TAG, "Unable to restore process matching override", restoreException);
                }
            }
            postOperationResult(callback, false, usefulMessage(exception));
        }
    }

    private void applyPersistedProcessMatchOverride() {
        runtimeOverrideError = "";
        String mode = getProcessMatchOverride();
        if (PROCESS_MATCH_CONFIG.equals(mode)) {
            return;
        }
        try {
            patchProcessMatchOverride(mode);
        } catch (IOException exception) {
            runtimeOverrideError = usefulMessage(exception);
            Log.w(TAG, "Unable to apply process matching override", exception);
        }
    }

    private void patchProcessMatchOverride(String mode) throws IOException {
        patchRuntimeConfig("{\"find-process-mode\":\"" + mode + "\"}");
    }

    private void captureConfiguredIpv6(int port) {
        configuredIpv6Known = false;
        configuredIpv6Enabled = false;
        wifiIpv6OverrideApplied = false;

        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + HOST + ":" + port + "/configs");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(2_000);
            connection.setRequestProperty("Authorization", "Bearer " + controllerSecret);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("mihomo 控制器无法读取 IPv6 配置");
            }
            JSONObject configs = new JSONObject(readResponseBody(connection));
            if (!configs.has("ipv6")) {
                throw new IOException("mihomo 控制器未返回 IPv6 配置");
            }
            configuredIpv6Enabled = configs.getBoolean("ipv6");
            configuredIpv6Known = true;
        } catch (IOException | JSONException exception) {
            Log.w(TAG, "Unable to capture config.yaml IPv6 value", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Applies the Wi-Fi-specific runtime value. Returns true when restoring an
     * unknown base value required a full config reload.
     */
    private boolean reconcileWifiIpv6Override() throws IOException {
        if (wifiIpv6Unavailable) {
            if (wifiIpv6OverrideApplied
                    || (configuredIpv6Known && !configuredIpv6Enabled)) {
                return false;
            }
            patchRuntimeConfig("{\"ipv6\":false}");
            wifiIpv6OverrideApplied = true;
            Log.i(TAG, "Disabled mihomo IPv6 for IPv4-only Wi-Fi");
            return false;
        }

        if (!wifiIpv6OverrideApplied) {
            return false;
        }
        if (!configuredIpv6Known) {
            Log.i(TAG, "Reloading config.yaml to restore its IPv6 value");
            stopInternal(false);
            if (!startInternal()) {
                throw new IOException(detail);
            }
            return true;
        }

        patchRuntimeConfig("{\"ipv6\":" + configuredIpv6Enabled + "}");
        wifiIpv6OverrideApplied = false;
        Log.i(TAG, "Restored config.yaml IPv6 value after leaving IPv4-only Wi-Fi");
        return false;
    }

    private void patchRuntimeConfig(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, controllerPort), 1_000);
            socket.setSoTimeout(2_000);
            String headers = "PATCH /configs HTTP/1.1\r\n"
                    + "Host: " + HOST + ":" + controllerPort + "\r\n"
                    + "Authorization: Bearer " + controllerSecret + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();

            try (BufferedReader response = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String statusLine = response.readLine();
                if (statusLine == null
                        || (!statusLine.contains(" 200 ") && !statusLine.contains(" 204 "))) {
                    throw new IOException(
                            statusLine == null ? "mihomo 控制器未响应" : statusLine
                    );
                }
            }
        }
    }

    private String runningCoreDetail(boolean androidVpn) {
        String runningDetail = "mihomo " + BuildConfig.MIHOMO_COMMIT.substring(0, 8)
                + " · zashboard " + BuildConfig.ZASHBOARD_VERSION
                + (androidVpn ? " · Android VPN" : " · 本地代理");
        if (wifiIpv6Unavailable
                && (wifiIpv6OverrideApplied
                || (configuredIpv6Known && !configuredIpv6Enabled))) {
            runningDetail += " · Wi-Fi 无可用 IPv6";
        }
        return runningDetail;
    }

    private static boolean isValidProcessMatchMode(String mode) {
        return PROCESS_MATCH_CONFIG.equals(mode)
                || PROCESS_MATCH_STRICT.equals(mode)
                || PROCESS_MATCH_ALWAYS.equals(mode)
                || PROCESS_MATCH_OFF.equals(mode);
    }

    private static void restoreProcessMatchPreference(
            SharedPreferences preferences,
            String previous
    ) {
        if (PROCESS_MATCH_CONFIG.equals(previous)) {
            preferences.edit().remove(PROCESS_MATCH_OVERRIDE_KEY).apply();
        } else {
            preferences.edit().putString(PROCESS_MATCH_OVERRIDE_KEY, previous).apply();
        }
    }

    private Void sendTunDescriptor(
            LocalServerSocket descriptorServer,
            ParcelFileDescriptor tunnel
    ) throws IOException {
        try (LocalServerSocket server = descriptorServer;
             LocalSocket connection = server.accept()) {
            FileDescriptor descriptor = tunnel.getFileDescriptor();
            if (!descriptor.valid()) {
                throw new IOException("VPN TUN 文件描述符已关闭");
            }
            connection.setFileDescriptorsForSend(new FileDescriptor[]{descriptor});
            connection.getOutputStream().write(1);
            connection.getOutputStream().flush();
        }
        return null;
    }

    private void waitForTunDescriptorTransfer(
            Future<Void> transfer,
            LocalServerSocket server
    ) throws IOException, InterruptedException {
        try {
            transfer.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            closeQuietly(server);
            transfer.cancel(true);
            throw new IOException("mihomo 未在 5 秒内接收 VPN TUN 接口", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IOException(
                    cause == null ? "无法向 mihomo 传递 VPN TUN 接口" : cause.getMessage(),
                    cause
            );
        }
    }

    private void handleProcessLookup(LocalSocket connection) throws IOException {
        connection.setSoTimeout(1_000);
        String request = readBoundedLine(connection.getInputStream(), MAX_PROCESS_QUERY_BYTES);
        String response = resolveProcessOwner(request);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private String resolveProcessOwner(String request) {
        if (request == null) {
            return "-1\t\n";
        }
        try {
            String[] fields = request.split("\t", -1);
            if (fields.length != 5) {
                return "-1\t\n";
            }

            int protocol;
            if (fields[0].startsWith("tcp")) {
                protocol = OsConstants.IPPROTO_TCP;
            } else if (fields[0].startsWith("udp")) {
                protocol = OsConstants.IPPROTO_UDP;
            } else {
                return "-1\t\n";
            }

            InetSocketAddress local = parseProcessEndpoint(fields[1], fields[2]);
            InetSocketAddress remote = parseProcessEndpoint(fields[3], fields[4]);
            ConnectivityManager connectivityManager = context.getSystemService(
                    ConnectivityManager.class
            );
            if (connectivityManager == null) {
                return "-1\t\n";
            }
            int uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote);
            if (uid == android.os.Process.INVALID_UID) {
                return "-1\t\n";
            }

            PackageManager packageManager = context.getPackageManager();
            String[] packages = packageManager.getPackagesForUid(uid);
            String packageName = null;
            if (packages != null && packages.length > 0) {
                Arrays.sort(packages);
                packageName = packages[0];
            }
            if (packageName == null || packageName.isBlank()) {
                packageName = packageManager.getNameForUid(uid);
            }
            if (packageName == null || packageName.isBlank()) {
                packageName = "uid:" + uid;
            }
            return uid + "\t" + packageName + "\n";
        } catch (RuntimeException exception) {
            Log.w(TAG, "Android connection-owner lookup failed", exception);
            return "-1\t\n";
        }
    }

    private static InetSocketAddress parseProcessEndpoint(
            String address,
            String portText
    ) {
        if (address.isEmpty() || !address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("Invalid numeric IP address");
        }
        int port = Integer.parseInt(portText);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(address), port);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid numeric IP address", exception);
        }
    }

    private static String readBoundedLine(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() <= maximumBytes) {
            int value = input.read();
            if (value == -1) {
                return output.size() == 0
                        ? null
                        : output.toString(StandardCharsets.UTF_8);
            }
            if (value == '\n') {
                return output.toString(StandardCharsets.UTF_8);
            }
            if (value != '\r') {
                output.write(value);
            }
        }
        throw new IOException("进程查询请求过长");
    }

    private void importConfigInternal(Uri source, ImportCallback callback) {
        File candidate = null;
        File backup = null;
        try {
            RuntimeFiles files = ensureRuntimeFiles();
            candidate = new File(files.home, "config.importing.yaml");
            backup = new File(files.home, "config.previous.yaml");
            copyUriWithLimit(source, candidate, MAX_CONFIG_BYTES);

            Files.deleteIfExists(backup.toPath());
            if (files.config.isFile()) {
                moveReplacing(files.config, backup);
            }
            moveReplacing(candidate, files.config);

            stopInternal(false);
            if (!startInternal()) {
                String failedConfigDetail = detail;
                stopInternal(false);
                Files.deleteIfExists(files.config.toPath());
                if (backup.isFile()) {
                    moveReplacing(backup, files.config);
                    startInternal();
                }
                throw new IOException(
                        "新配置无法启动，已恢复原配置：" + failedConfigDetail
                );
            }

            try {
                Files.deleteIfExists(backup.toPath());
            } catch (IOException exception) {
                Log.w(TAG, "Unable to remove previous config backup", exception);
            }
            postImportResult(callback, true, "配置已导入并由 mihomo 成功启动");
        } catch (Exception exception) {
            Log.e(TAG, "Unable to import config", exception);
            if (backup != null && backup.isFile()) {
                File config = new File(backup.getParentFile(), "config.yaml");
                if (!config.exists()) {
                    try {
                        moveReplacing(backup, config);
                    } catch (IOException restoreException) {
                        Log.e(TAG, "Unable to restore previous config", restoreException);
                    }
                }
            }
            postImportResult(callback, false, usefulMessage(exception));
        } finally {
            if (candidate != null) {
                try {
                    Files.deleteIfExists(candidate.toPath());
                } catch (IOException ignored) {
                    // A stale candidate is harmless and will be replaced on the next import.
                }
            }
        }
    }

    private void copyUriWithLimit(Uri source, File destination, int byteLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > byteLimit) {
                    throw new IOException("config.yaml 超过 32 MiB 限制");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private RuntimeFiles ensureRuntimeFiles() throws IOException {
        File home = new File(context.getNoBackupFilesDir(), "mihomo");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建 mihomo 工作目录");
        }

        File config = new File(home, "config.yaml");
        if (!config.isFile()) {
            copyAssetFile("default-config.yaml", config);
        }
        makeConfigReadOnly(config);

        // Ship the large rule databases with the APK so a first import never
        // depends on GitHub being reachable from the phone. Existing user or
        // previously downloaded databases are deliberately preserved.
        copyAssetFileIfMissing("geodata/GeoIP.dat", new File(home, "GeoIP.dat"));
        copyAssetFileIfMissing("geodata/GeoSite.dat", new File(home, "GeoSite.dat"));

        File ui = new File(home, "ui");
        File marker = new File(ui, ".androidcyaml-version");
        String installedVersion = marker.isFile() ? readFile(marker).trim() : "";
        if (!BuildConfig.ZASHBOARD_VERSION.equals(installedVersion)
                || !new File(ui, "index.html").isFile()) {
            installDashboard(home, ui);
        }
        return new RuntimeFiles(home, config, ui);
    }

    private void copyAssetFileIfMissing(String assetPath, File destination) throws IOException {
        if (!destination.isFile()) {
            copyAssetFile(assetPath, destination);
        }
    }

    private void makeConfigReadOnly(File config) throws IOException {
        try {
            // The selected SAF document is only read. Its byte-for-byte copy is
            // owner-readable so neither mihomo nor its dashboard can write
            // runtime overrides back into config.yaml.
            Os.chmod(config.getAbsolutePath(), OsConstants.S_IRUSR);
        } catch (ErrnoException exception) {
            throw new IOException("无法将 config.yaml 设为只读", exception);
        }
    }

    private void installDashboard(File home, File ui) throws IOException {
        File staging = new File(home, "ui.installing");
        File previous = new File(home, "ui.previous");
        deleteRecursively(staging);
        deleteRecursively(previous);
        if (!staging.mkdirs()) {
            throw new IOException("无法创建面板暂存目录");
        }
        copyAssetTree("zashboard", staging);
        writeText(new File(staging, ".androidcyaml-version"), BuildConfig.ZASHBOARD_VERSION);

        if (ui.exists()) {
            moveReplacing(ui, previous);
        }
        try {
            moveReplacing(staging, ui);
        } catch (IOException exception) {
            if (previous.exists() && !ui.exists()) {
                moveReplacing(previous, ui);
            }
            throw exception;
        }
        deleteRecursively(previous);
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination);
        }
        for (String entry : entries) {
            copyAssetTree(assetPath + "/" + entry, new File(destination, entry));
        }
    }

    private void copyAssetFile(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private boolean waitForController(
            Process candidate,
            int port,
            long timeout,
            TimeUnit unit
    ) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline && candidate.isAlive()) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + HOST + ":" + port + "/version");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(300);
                connection.setReadTimeout(300);
                connection.setRequestProperty("Authorization", "Bearer " + controllerSecret);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return true;
                }
            } catch (IOException ignored) {
                // The controller is expected to reject connections while it starts.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void waitForAndroidTunActive(
            Process candidate,
            int port,
            long timeout,
            TimeUnit unit
    ) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline && candidate.isAlive()) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + HOST + ":" + port + "/configs");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                connection.setRequestProperty("Authorization", "Bearer " + controllerSecret);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("mihomo 控制器无法读取 TUN 状态");
                }
                JSONObject tun = new JSONObject(readResponseBody(connection)).optJSONObject("tun");
                if (tun != null
                        && tun.optBoolean("enable", false)
                        && tun.optInt("file-descriptor", 0) > 0) {
                    return;
                }
                lastFailure = new IOException(
                        "config.yaml 必须设置 tun.enable: true，且 TUN 监听必须成功启动"
                );
            } catch (JSONException | IOException exception) {
                lastFailure = exception instanceof IOException
                        ? (IOException) exception
                        : new IOException("无法解析 mihomo TUN 状态", exception);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(120);
        }
        if (!candidate.isAlive()) {
            throw new IOException("mihomo 在建立 Android TUN 时退出");
        }
        throw lastFailure == null
                ? new IOException("mihomo TUN 未在 5 秒内就绪")
                : lastFailure;
    }

    private static String readResponseBody(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 1024 * 1024) {
                    throw new IOException("mihomo 控制器响应过大");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int findAvailableControllerPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            // mihomo restarts while WebView connections to the previous
            // listener can still be in TIME_WAIT. SO_REUSEADDR permits the
            // same loopback origin to be reclaimed without allowing a second
            // live listener to take the port.
            reservation.setReuseAddress(true);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), PREFERRED_CONTROLLER_PORT),
                    1
            );
            return PREFERRED_CONTROLLER_PORT;
        } catch (IOException exception) {
            Log.w(
                    TAG,
                    "Preferred controller port is unavailable; Zashboard uses a temporary origin",
                    exception
            );
        }
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), 0),
                    1
            );
            return reservation.getLocalPort();
        }
    }

    private void readCoreLogs(Process source) {
        ioExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                    synchronized (logLock) {
                        recentLogs.addLast(line);
                        while (recentLogs.size() > MAX_LOG_LINES) {
                            recentLogs.removeFirst();
                        }
                    }
                }
            } catch (IOException exception) {
                if (source.isAlive()) {
                    Log.w(TAG, "Core log reader stopped", exception);
                }
            }
        });
    }

    private void watchForExit(Process source) {
        ioExecutor.execute(() -> {
            try {
                int exitCode = source.waitFor();
                ProcessLookupBridge bridge;
                synchronized (processLock) {
                    if (process != source) {
                        return;
                    }
                    process = null;
                    bridge = processLookupBridge;
                    processLookupBridge = null;
                }
                closeQuietly(bridge);
                String logs = recentLogSummary();
                publish(
                        State.FAILED,
                        "mihomo 已退出（" + exitCode + "）"
                                + (logs.isEmpty() ? "" : "：" + logs)
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void stopInternal(boolean publishStopped) {
        configuredIpv6Known = false;
        configuredIpv6Enabled = false;
        wifiIpv6OverrideApplied = false;
        Process current;
        ProcessLookupBridge bridge;
        synchronized (processLock) {
            current = process;
            process = null;
            bridge = processLookupBridge;
            processLookupBridge = null;
        }
        closeQuietly(bridge);
        if (current != null) {
            stopSpecificProcess(current);
        }
        if (publishStopped) {
            publish(State.STOPPED, "mihomo 已停止");
        }
    }

    private void stopSpecificProcess(Process target) {
        target.destroy();
        try {
            if (!target.waitFor(2, TimeUnit.SECONDS)) {
                target.destroyForcibly();
                target.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }

    private boolean isProcessAlive() {
        synchronized (processLock) {
            return process != null && process.isAlive();
        }
    }

    private void terminateStaleCores() throws IOException {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        File proc = new File("/proc");
        File[] processes = proc.listFiles();
        if (processes != null) {
            for (File processDirectory : processes) {
                try {
                    candidates.add(Integer.parseInt(processDirectory.getName()));
                } catch (NumberFormatException ignored) {
                    // Non-process entries such as /proc/net are expected.
                }
            }
        }

        String expectedBinary = new File(
                context.getApplicationInfo().nativeLibraryDir,
                "libmihomo.so"
        ).getAbsolutePath();
        for (int pid : candidates) {
            if (pid <= 0 || pid == android.os.Process.myPid()) {
                continue;
            }
            String command = readProcessCommand(pid);
            if (!expectedBinary.equals(command)) {
                continue;
            }
            Log.w(TAG, "Stopping stale mihomo process " + pid);
            signalProcess(pid, OsConstants.SIGTERM);
            waitForProcessExit(pid, 800);
            if (isProcessAlive(pid)) {
                signalProcess(pid, OsConstants.SIGKILL);
                waitForProcessExit(pid, 800);
            }
            if (isProcessAlive(pid)) {
                throw new IOException("无法停止残留的 mihomo 进程 " + pid);
            }
        }
    }

    private String readProcessCommand(int pid) {
        try {
            String commandLine = readFile(new File("/proc/" + pid + "/cmdline"));
            int separator = commandLine.indexOf('\0');
            return separator < 0 ? commandLine : commandLine.substring(0, separator);
        } catch (IOException ignored) {
            return "";
        }
    }

    private void signalProcess(int pid, int signal) throws IOException {
        try {
            Os.kill(pid, signal);
        } catch (ErrnoException exception) {
            if (exception.errno != OsConstants.ESRCH) {
                throw new IOException("无法向残留 mihomo 进程发送信号", exception);
            }
        }
    }

    private void waitForProcessExit(int pid, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (isProcessAlive(pid) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("等待残留 mihomo 进程退出时被中断", exception);
            }
        }
    }

    private static boolean isProcessAlive(int pid) {
        return new File("/proc/" + pid).isDirectory();
    }

    private String loadOrCreateSecret() {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String existing = preferences.getString(SECRET_KEY, null);
        if (existing != null && existing.length() == 64) {
            return existing;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder secret = new StringBuilder(random.length * 2);
        for (byte value : random) {
            secret.append(String.format("%02x", value & 0xff));
        }
        String generated = secret.toString();
        preferences.edit().putString(SECRET_KEY, generated).apply();
        return generated;
    }

    private void publish(State newState, String newDetail) {
        state = newState;
        detail = newDetail == null ? "" : newDetail;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onCoreStateChanged(state, detail);
            }
        });
    }

    private void postImportResult(ImportCallback callback, boolean success, String message) {
        mainHandler.post(() -> callback.onComplete(success, message));
    }

    private void postOperationResult(OperationCallback callback, boolean success, String message) {
        mainHandler.post(() -> callback.onComplete(success, message));
    }

    private String recentLogSummary() {
        synchronized (logLock) {
            if (recentLogs.isEmpty()) {
                return "";
            }
            String last = recentLogs.peekLast();
            return last == null ? "" : last.trim();
        }
    }

    private String diagnosticLogSummary() {
        synchronized (logLock) {
            String lastError = "";
            for (String line : recentLogs) {
                if (line.contains("level=error") || line.contains("level=fatal")) {
                    lastError = line.trim();
                }
            }
            return lastError.isEmpty() ? recentLogSummary() : lastError;
        }
    }

    private static String usefulMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static void moveReplacing(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String readFile(File source) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File destination, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void closeQuietly(LocalServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket may already have been closed by the descriptor sender.
        }
    }

    private static void closeQuietly(ProcessLookupBridge bridge) {
        if (bridge != null) {
            bridge.close();
        }
    }

    private final class ProcessLookupBridge implements AutoCloseable {
        final String socketName = "androidcyaml-process-" + UUID.randomUUID();
        final LocalServerSocket server;
        volatile boolean closed;
        Future<?> serverTask;

        ProcessLookupBridge() throws IOException {
            server = new LocalServerSocket(socketName);
        }

        void start() {
            serverTask = ioExecutor.submit(this::serve);
        }

        private void serve() {
            while (!closed) {
                try (LocalSocket connection = server.accept()) {
                    handleProcessLookup(connection);
                } catch (IOException exception) {
                    if (!closed) {
                        Log.w(TAG, "Android process lookup bridge failed", exception);
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(server);
            Future<?> task = serverTask;
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (!target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!target.delete()) {
            throw new IOException("无法删除：" + target);
        }
    }

    private static final class RuntimeFiles {
        final File home;
        final File config;
        final File ui;

        RuntimeFiles(File home, File config, File ui) {
            this.home = home;
            this.config = config;
            this.ui = ui;
        }
    }

}
