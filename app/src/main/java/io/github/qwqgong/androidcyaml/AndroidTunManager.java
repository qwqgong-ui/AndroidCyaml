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
    private TunOptions activeOptions;

    AndroidTunManager(VpnPlatformHost host) {
        this.host = Objects.requireNonNull(host);
    }

    ParcelFileDescriptor open(TunOptions options) throws IOException {
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

    private ParcelFileDescriptor establish(TunOptions options) throws IOException {
        if (options.inet4Address().isEmpty() && options.inet6Address().isEmpty()) {
            throw new IOException("mihomo 未提供 TUN 接口地址");
        }
        if (!options.includePackage().isEmpty() && !options.excludePackage().isEmpty()) {
            throw new IOException("Android 不能同时应用 include-package 与 exclude-package");
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
            throw new IOException("Android 未建立 VpnService TUN 接口");
        }
        Log.i(TAG, "Established embedded mihomo TUN: " + options.summary());
        return established;
    }

    private static void applyPackageRouting(
            VpnService.Builder builder,
            TunOptions options,
            String ownPackage
    ) throws IOException {
        try {
            if (!options.includePackage().isEmpty()) {
                // The embedded core must remain inside VpnService routing. Its
                // real outbound sockets are excluded individually with protect().
                builder.addAllowedApplication(ownPackage);
                int acceptedTargets = 0;
                for (String packageName : options.includePackage()) {
                    if (ownPackage.equals(packageName)) {
                        continue;
                    }
                    try {
                        builder.addAllowedApplication(packageName);
                        acceptedTargets++;
                    } catch (PackageManager.NameNotFoundException exception) {
                        Log.w(TAG, "Ignoring unavailable included package " + packageName);
                    }
                }
                if (acceptedTargets == 0
                        && options.includePackage().stream().noneMatch(ownPackage::equals)) {
                    throw new IOException("tun.include-package 未匹配任何已安装应用");
                }
                return;
            }

            for (String packageName : options.excludePackage()) {
                if (ownPackage.equals(packageName)) {
                    Log.w(TAG, "Ignoring exclusion of the embedded core package");
                    continue;
                }
                try {
                    builder.addDisallowedApplication(packageName);
                } catch (PackageManager.NameNotFoundException exception) {
                    Log.w(TAG, "Ignoring unavailable excluded package " + packageName);
                }
            }
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("AndroidCyaml 自身包无法加入 VPN 白名单", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Android 应用路由配置失败", exception);
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
