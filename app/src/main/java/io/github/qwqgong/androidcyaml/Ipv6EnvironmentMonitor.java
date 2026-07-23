package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;

final class Ipv6EnvironmentMonitor {
    interface Listener {
        void onIpv6UsabilityChanged(boolean usable);
    }

    private static final String TAG = "AndroidCyaml/IPv6";
    private static final long DEBOUNCE_MILLIS = 750L;

    private final ConnectivityManager connectivityManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable evaluation = this::evaluateAndNotify;
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleEvaluation();
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities capabilities
                ) {
                    scheduleEvaluation();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    scheduleEvaluation();
                }

                @Override
                public void onLost(Network network) {
                    scheduleEvaluation();
                }
            };

    private Listener listener;
    private boolean registered;
    private Boolean lastUsable;

    Ipv6EnvironmentMonitor(Context context) {
        connectivityManager = Objects.requireNonNull(
                context.getApplicationContext().getSystemService(ConnectivityManager.class)
        );
    }

    boolean start(Listener nextListener) {
        listener = Objects.requireNonNull(nextListener);
        if (!registered) {
            connectivityManager.registerDefaultNetworkCallback(callback, handler);
            registered = true;
        }
        boolean current = currentUsable();
        lastUsable = current;
        return current;
    }

    void stop() {
        handler.removeCallbacks(evaluation);
        if (registered) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // Callback may already have been removed by the framework.
            }
        }
        registered = false;
        listener = null;
        lastUsable = null;
    }

    boolean currentUsable() {
        try {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            if (capabilities == null || properties == null) {
                return false;
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return false;
            }
            return hasGlobalIpv6Address(properties) && hasIpv6DefaultRoute(properties);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to inspect the active IPv6 environment", exception);
            return false;
        }
    }

    private void scheduleEvaluation() {
        handler.removeCallbacks(evaluation);
        handler.postDelayed(evaluation, DEBOUNCE_MILLIS);
    }

    private void evaluateAndNotify() {
        boolean usable = currentUsable();
        if (lastUsable != null && lastUsable == usable) {
            return;
        }
        lastUsable = usable;
        Listener current = listener;
        if (current != null) {
            current.onIpv6UsabilityChanged(usable);
        }
    }

    private static boolean hasGlobalIpv6Address(LinkProperties properties) {
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet6Address && isGlobalIpv6(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIpv6DefaultRoute(LinkProperties properties) {
        for (RouteInfo route : properties.getRoutes()) {
            if (route.isDefaultRoute()
                    && route.getDestination().getAddress() instanceof Inet6Address) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGlobalIpv6(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) != 0xfc;
    }
}
