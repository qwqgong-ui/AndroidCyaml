package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Watches validated, non-VPN Wi-Fi networks and reports whether their usable
 * uplink is IPv4-only. A short grace period lets IPv6 router advertisements
 * arrive after DHCP without briefly disabling mihomo IPv6.
 */
final class WifiIpv6Monitor {
    interface Listener {
        void onWifiIpv6UnavailableChanged(boolean unavailable);
    }

    private static final String TAG = "AndroidCyaml/WifiIPv6";
    private static final long IPV6_ABSENCE_GRACE_MILLIS = 5_000;

    private final ConnectivityManager connectivityManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Set<Network> availableNetworks = new HashSet<>();
    private final Map<Network, LinkProperties> linkPropertiesByNetwork = new HashMap<>();

    private final Runnable reportIpv6Unavailable = () -> {
        ipv6AbsencePending = false;
        report(true);
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    LinkProperties properties =
                            connectivityManager.getLinkProperties(network);
                    if (properties != null) {
                        linkPropertiesByNetwork.put(network, properties);
                    }
                    updateAvailability(
                            network,
                            connectivityManager.getNetworkCapabilities(network)
                    );
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities networkCapabilities
                ) {
                    updateAvailability(network, networkCapabilities);
                }

                @Override
                public void onLinkPropertiesChanged(
                        Network network,
                        LinkProperties linkProperties
                ) {
                    linkPropertiesByNetwork.put(network, linkProperties);
                    evaluate();
                }

                @Override
                public void onLost(Network network) {
                    availableNetworks.remove(network);
                    linkPropertiesByNetwork.remove(network);
                    evaluate();
                }
            };

    private boolean registered;
    private boolean ipv6AbsencePending;
    private Boolean lastReportedUnavailable;

    private WifiIpv6Monitor(Context context, Listener listener) {
        connectivityManager = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
    }

    static WifiIpv6Monitor start(Context context, Listener listener) {
        WifiIpv6Monitor monitor = new WifiIpv6Monitor(
                context.getApplicationContext(),
                listener
        );
        monitor.register();
        return monitor;
    }

    void stop() {
        if (!registered || connectivityManager == null) {
            return;
        }
        registered = false;
        handler.removeCallbacks(reportIpv6Unavailable);
        ipv6AbsencePending = false;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to unregister Wi-Fi network callback", exception);
        }
    }

    private void register() {
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager is unavailable");
            report(false);
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback, handler);
            registered = true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to monitor Wi-Fi IPv6 state", exception);
            report(false);
        }
    }

    private void updateAvailability(
            Network network,
            NetworkCapabilities capabilities
    ) {
        boolean validatedWifi = capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        if (validatedWifi) {
            availableNetworks.add(network);
        } else {
            availableNetworks.remove(network);
        }
        evaluate();
    }

    private void evaluate() {
        if (availableNetworks.isEmpty()) {
            cancelPendingAbsence();
            report(false);
            return;
        }

        // Keep the previous decision until LinkProperties are known for every
        // matching network. This avoids an IPv4-only blip during connection setup.
        if (!linkPropertiesByNetwork.keySet().containsAll(availableNetworks)) {
            cancelPendingAbsence();
            return;
        }

        for (Network network : availableNetworks) {
            LinkProperties properties = linkPropertiesByNetwork.get(network);
            if (properties != null && hasUsableIpv6(properties)) {
                cancelPendingAbsence();
                report(false);
                return;
            }
        }

        if (!ipv6AbsencePending && !Boolean.TRUE.equals(lastReportedUnavailable)) {
            ipv6AbsencePending = true;
            handler.postDelayed(reportIpv6Unavailable, IPV6_ABSENCE_GRACE_MILLIS);
        }
    }

    private void cancelPendingAbsence() {
        if (!ipv6AbsencePending) {
            return;
        }
        handler.removeCallbacks(reportIpv6Unavailable);
        ipv6AbsencePending = false;
    }

    private void report(boolean unavailable) {
        if (lastReportedUnavailable != null
                && lastReportedUnavailable == unavailable) {
            return;
        }
        lastReportedUnavailable = unavailable;
        listener.onWifiIpv6UnavailableChanged(unavailable);
    }

    static boolean hasUsableIpv6(LinkProperties properties) {
        boolean hasGlobalAddress = false;
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            if (isGlobalUnicastIpv6(linkAddress.getAddress())) {
                hasGlobalAddress = true;
                break;
            }
        }
        if (!hasGlobalAddress) {
            return false;
        }

        for (RouteInfo route : properties.getRoutes()) {
            InetAddress destination = route.getDestination().getAddress();
            if (route.getType() == RouteInfo.RTN_UNICAST
                    && route.isDefaultRoute()
                    && destination instanceof Inet6Address) {
                return true;
            }
        }
        return false;
    }

    static boolean isGlobalUnicastIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        // RFC 4291 currently allocates 2000::/3 as global unicast space.
        return bytes.length == 16 && (bytes[0] & 0xe0) == 0x20;
    }
}
