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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracks the best validated non-VPN network.
 *
 * <p>The application's default network becomes its own VPN after establish(), so
 * registerDefaultNetworkCallback() and getActiveNetwork() cannot be used as the
 * authoritative underlying-network source while the tunnel is running.
 */
final class Ipv6EnvironmentMonitor {
    interface Listener {
        void onUnderlyingNetworkChanged(State state);
    }

    record State(long networkHandle, String linkSignature, boolean ipv6Usable) {
        State {
            if (networkHandle == 0L) {
                linkSignature = "";
                ipv6Usable = false;
            } else {
                linkSignature = linkSignature == null ? "" : linkSignature;
            }
        }

        static State unavailable() {
            return new State(0L, "", false);
        }

        boolean available() {
            return networkHandle != 0L;
        }

        boolean pathChangedFrom(State previous) {
            return previous == null
                    || networkHandle != previous.networkHandle
                    || !linkSignature.equals(previous.linkSignature);
        }
    }

    private record Snapshot(
            Network network,
            NetworkCapabilities capabilities,
            LinkProperties linkProperties
    ) {}

    private static final String TAG = "AndroidCyaml/Network";
    private static final long DEBOUNCE_MILLIS = 750L;

    private final ConnectivityManager connectivityManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Runnable evaluation = this::evaluateAndNotify;
    private final NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    synchronized (lock) {
                        selectedNetwork = network;
                        selectedCapabilities = null;
                        selectedLinkProperties = null;
                    }
                    handler.removeCallbacks(evaluation);
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities capabilities
                ) {
                    synchronized (lock) {
                        if (!network.equals(selectedNetwork)) {
                            return;
                        }
                        selectedCapabilities = capabilities;
                    }
                    scheduleIfComplete();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    synchronized (lock) {
                        if (!network.equals(selectedNetwork)) {
                            return;
                        }
                        selectedLinkProperties = properties;
                    }
                    scheduleIfComplete();
                }

                @Override
                public void onLost(Network network) {
                    synchronized (lock) {
                        if (!network.equals(selectedNetwork)) {
                            return;
                        }
                        selectedNetwork = null;
                        selectedCapabilities = null;
                        selectedLinkProperties = null;
                    }
                    scheduleEvaluation();
                }
            };

    private Listener listener;
    private boolean registered;
    private Network selectedNetwork;
    private NetworkCapabilities selectedCapabilities;
    private LinkProperties selectedLinkProperties;
    private State lastState;

    Ipv6EnvironmentMonitor(Context context) {
        connectivityManager = Objects.requireNonNull(
                context.getApplicationContext().getSystemService(ConnectivityManager.class)
        );
    }

    State start(Listener nextListener) {
        Objects.requireNonNull(nextListener);
        synchronized (lock) {
            listener = nextListener;
        }
        if (!registered) {
            connectivityManager.registerBestMatchingNetworkCallback(request, callback, handler);
            registered = true;
        }

        Snapshot initial = inspectBestAvailableNetwork();
        State initialState = stateOf(initial);
        synchronized (lock) {
            if (selectedNetwork == null && initial.network() != null) {
                selectedNetwork = initial.network();
                selectedCapabilities = initial.capabilities();
                selectedLinkProperties = initial.linkProperties();
            }
            lastState = initialState;
        }
        return initialState;
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
        synchronized (lock) {
            listener = null;
            selectedNetwork = null;
            selectedCapabilities = null;
            selectedLinkProperties = null;
            lastState = null;
        }
    }

    State currentState() {
        synchronized (lock) {
            if (selectedNetwork != null
                    && selectedCapabilities != null
                    && selectedLinkProperties != null) {
                return stateOf(new Snapshot(
                        selectedNetwork,
                        selectedCapabilities,
                        selectedLinkProperties
                ));
            }
            if (registered && lastState != null) {
                return lastState;
            }
        }
        return stateOf(inspectBestAvailableNetwork());
    }

    private void scheduleIfComplete() {
        synchronized (lock) {
            if (selectedCapabilities == null || selectedLinkProperties == null) {
                return;
            }
        }
        scheduleEvaluation();
    }

    private void scheduleEvaluation() {
        handler.removeCallbacks(evaluation);
        handler.postDelayed(evaluation, DEBOUNCE_MILLIS);
    }

    private void evaluateAndNotify() {
        State state;
        Listener current;
        synchronized (lock) {
            state = selectedNetwork == null
                    ? State.unavailable()
                    : stateOf(new Snapshot(
                            selectedNetwork,
                            selectedCapabilities,
                            selectedLinkProperties
                    ));
            if (state.equals(lastState)) {
                return;
            }
            lastState = state;
            current = listener;
        }
        if (current != null) {
            current.onUnderlyingNetworkChanged(state);
        }
    }

    private Snapshot inspectBestAvailableNetwork() {
        try {
            Network active = connectivityManager.getActiveNetwork();
            Snapshot activeSnapshot = inspect(active);
            if (activeSnapshot.network() != null) {
                return activeSnapshot;
            }

            // This is only a synchronous startup fallback. The registered
            // best-matching callback is authoritative once it delivers.
            Snapshot best = new Snapshot(null, null, null);
            int bestRank = Integer.MIN_VALUE;
            for (Network network : connectivityManager.getAllNetworks()) {
                Snapshot candidate = inspect(network);
                if (candidate.network() == null) {
                    continue;
                }
                int rank = transportRank(candidate.capabilities());
                if (rank > bestRank) {
                    best = candidate;
                    bestRank = rank;
                }
            }
            return best;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to inspect the underlying network", exception);
            return new Snapshot(null, null, null);
        }
    }

    private Snapshot inspect(Network network) {
        if (network == null) {
            return new Snapshot(null, null, null);
        }
        NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(network);
        LinkProperties properties = connectivityManager.getLinkProperties(network);
        if (!isUsableUnderlying(capabilities) || properties == null) {
            return new Snapshot(null, null, null);
        }
        return new Snapshot(network, capabilities, properties);
    }

    private static boolean isUsableUnderlying(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static int transportRank(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return 4;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return 3;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return 2;
        }
        return 1;
    }

    private static State stateOf(Snapshot snapshot) {
        if (snapshot == null
                || snapshot.network() == null
                || !isUsableUnderlying(snapshot.capabilities())
                || snapshot.linkProperties() == null) {
            return State.unavailable();
        }
        LinkProperties properties = snapshot.linkProperties();
        boolean ipv6Usable =
                hasGlobalIpv6Address(properties) && hasIpv6DefaultRoute(properties);
        return new State(
                snapshot.network().getNetworkHandle(),
                linkSignature(properties),
                ipv6Usable
        );
    }

    private static String linkSignature(LinkProperties properties) {
        List<String> values = new ArrayList<>();
        values.add("if=" + Objects.toString(properties.getInterfaceName(), ""));
        for (LinkAddress address : properties.getLinkAddresses()) {
            values.add("addr=" + address);
        }
        for (RouteInfo route : properties.getRoutes()) {
            values.add("route=" + route);
        }
        for (InetAddress dnsServer : properties.getDnsServers()) {
            values.add("dns=" + dnsServer.getHostAddress());
        }
        values.sort(String::compareTo);
        return String.join("|", values);
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
