package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.IpPrefix;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

final class AndroidTunManager implements Closeable {
    private static final String TAG = "AndroidCyaml/TUN";

    private final VpnPlatformHost host;
    private final Object lock = new Object();
    private ParcelFileDescriptor tunnel;
    private AndroidPlatformProtocol.TunOptions activeOptions;

    AndroidTunManager(VpnPlatformHost host) {
        this.host = Objects.requireNonNull(host);
    }

    ParcelFileDescriptor open(AndroidPlatformProtocol.TunOptions options) throws IOException {
        synchronized (lock) {
            if (options.equals(activeOptions)
                    && tunnel != null
                    && tunnel.getFileDescriptor().valid()) {
                return tunnel;
            }
            ParcelFileDescriptor candidate = establish(options);
            ParcelFileDescriptor previous = tunnel;
            tunnel = candidate;
            activeOptions = options;
            closeQuietly(previous);
            return candidate;
        }
    }

    boolean hasUsableTunnel() {
        synchronized (lock) {
            return tunnel != null && tunnel.getFileDescriptor().valid();
        }
    }

    private ParcelFileDescriptor establish(AndroidPlatformProtocol.TunOptions options)
            throws IOException {
        if (options.inet4Address().isEmpty() && options.inet6Address().isEmpty()) {
            throw new IOException("mihomo did not provide a TUN interface address");
        }
        if (!options.includePackage().isEmpty() && !options.excludePackage().isEmpty()) {
            throw new IOException("Android cannot combine tun.include-package and tun.exclude-package");
        }

        Context context = host.platformContext();
        VpnService.Builder builder = host.newPlatformBuilder()
                .setSession(context.getString(R.string.app_name))
                .setMtu(options.mtu())
                .setBlocking(false)
                .setMetered(false)
                .setConfigureIntent(host.openAppPendingIntent());

        boolean hasIpv4 = addAddresses(builder, options.inet4Address());
        boolean hasIpv6 = addAddresses(builder, options.inet6Address());
        if (options.autoRoute()) {
            addRoutes(builder, options.inet4RouteAddress(), hasIpv4, false);
            addRoutes(builder, options.inet6RouteAddress(), hasIpv6, true);
            addExcludedRoutes(builder, options.inet4RouteExcludeAddress());
            addExcludedRoutes(builder, options.inet6RouteExcludeAddress());
        }
        for (String address : options.dnsServerAddress()) {
            builder.addDnsServer(parseNumericAddress(address));
        }
        applyPackageRouting(builder, options, context.getPackageName());

        ParcelFileDescriptor established = builder.establish();
        if (established == null) {
            throw new IOException("Android did not establish the VpnService TUN interface");
        }
        Log.i(TAG, "Established mihomo TUN: " + options.summary());
        return established;
    }

    private static void applyPackageRouting(
            VpnService.Builder builder,
            AndroidPlatformProtocol.TunOptions options,
            String ownPackage
    ) throws IOException {
        try {
            if (!options.includePackage().isEmpty()) {
                int accepted = 0;
                for (String packageName : options.includePackage()) {
                    if (ownPackage.equals(packageName)) {
                        continue;
                    }
                    try {
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
            for (String packageName : options.excludePackage()) {
                if (ownPackage.equals(packageName)) {
                    continue;
                }
                try {
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
            IpPrefix prefix = NetworkAddressParser.parsePrefix(value);
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
            builder.addRoute(NetworkAddressParser.parsePrefix(value));
        }
    }

    private static void addExcludedRoutes(VpnService.Builder builder, List<String> prefixes)
            throws IOException {
        for (String value : prefixes) {
            builder.excludeRoute(NetworkAddressParser.parsePrefix(value));
        }
    }

    private static InetAddress parseNumericAddress(String value) throws IOException {
        return NetworkAddressParser.parseAddress(value);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly(tunnel);
            tunnel = null;
            activeOptions = null;
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
}
