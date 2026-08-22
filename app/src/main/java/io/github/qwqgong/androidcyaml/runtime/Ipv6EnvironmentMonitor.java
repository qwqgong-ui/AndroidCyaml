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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    record State(
            long networkHandle,
            String linkSignature,
            boolean ipv6Usable,
            boolean wifi,
            List<String> dnsServers,
            String selectionIdentity,
            String selectionKind,
            String selectionLabel
    ) {
        State(
                long networkHandle,
                String linkSignature,
                boolean ipv6Usable,
                boolean wifi,
                List<String> dnsServers,
                String selectionIdentity
        ) {
            this(
                    networkHandle,
                    linkSignature,
                    ipv6Usable,
                    wifi,
                    dnsServers,
                    selectionIdentity,
                    wifi ? "wifi" : "cellular",
                    wifi ? "Wi-Fi" : "移动数据"
            );
        }

        State {
            if (networkHandle == 0L) {
                linkSignature = "";
                ipv6Usable = false;
                wifi = false;
                dnsServers = List.of();
                selectionIdentity = "";
                selectionKind = "";
                selectionLabel = "";
            } else {
                linkSignature = linkSignature == null ? "" : linkSignature;
                dnsServers = dnsServers == null ? List.of() : List.copyOf(dnsServers);
                selectionIdentity = selectionIdentity == null ? "" : selectionIdentity;
                selectionKind = selectionKind == null ? "" : selectionKind;
                selectionLabel = selectionLabel == null ? "" : selectionLabel;
            }
        }

        static State unavailable() {
            return new State(0L, "", false, false, List.of(), "", "", "");
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
    private static final long READY_DEBOUNCE_MILLIS = 100L;
    private static final long LOST_HANDOVER_GRACE_MILLIS = 650L;

    private final ConnectivityManager connectivityManager;
    private final NetworkIdentityResolver identityResolver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Map<Long, Snapshot> callbackSnapshots = new LinkedHashMap<>();
    private final UnderlyingNetworkCandidates callbackCandidates =
            new UnderlyingNetworkCandidates();
    private final Runnable evaluation = this::evaluateAndNotify;
    private final NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build();
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
                @Override
                public void onAvailable(Network network) {
                    synchronized (lock) {
                        callbackCandidates.onAvailable(network.getNetworkHandle());
                    }
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities capabilities
                ) {
                    if (!isUsableUnderlying(capabilities)) {
                        removeCallbackCandidate(network);
                        return;
                    }
                    LinkProperties properties = connectivityManager.getLinkProperties(network);
                    synchronized (lock) {
                        long handle = network.getNetworkHandle();
                        Snapshot previous = callbackSnapshots.get(handle);
                        if (properties == null && previous != null) {
                            properties = previous.linkProperties();
                        }
                        callbackSnapshots.put(
                                handle,
                                new Snapshot(network, capabilities, properties)
                        );
                        if (properties != null) {
                            callbackCandidates.update(handle, transportRank(capabilities));
                        }
                        selectBestCallbackCandidateLocked();
                    }
                    scheduleIfComplete();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    synchronized (lock) {
                        long handle = network.getNetworkHandle();
                        Snapshot previous = callbackSnapshots.get(handle);
                        NetworkCapabilities capabilities = previous == null
                                ? null
                                : previous.capabilities();
                        callbackSnapshots.put(
                                handle,
                                new Snapshot(network, capabilities, properties)
                        );
                        if (isUsableUnderlying(capabilities)) {
                            callbackCandidates.update(handle, transportRank(capabilities));
                        }
                        selectBestCallbackCandidateLocked();
                    }
                    scheduleIfComplete();
                }

                @Override
                public void onLost(Network network) {
                    removeCallbackCandidate(network);
                }
            };

    private Listener listener;
    private boolean registered;
    private Network selectedNetwork;
    private NetworkCapabilities selectedCapabilities;
    private LinkProperties selectedLinkProperties;
    private State lastState;

    Ipv6EnvironmentMonitor(Context context) {
        identityResolver = new NetworkIdentityResolver(context);
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
            connectivityManager.registerNetworkCallback(request, callback, handler);
            registered = true;
        }

        Snapshot initial = inspectBestAvailableNetwork();
        State initialState = stateOf(initial);
        synchronized (lock) {
            if (selectedNetwork == null && initial.network() != null) {
                cacheCallbackSnapshotLocked(initial);
                selectBestCallbackCandidateLocked();
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
            callbackSnapshots.clear();
            callbackCandidates.clear();
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

    List<NetworkIdentityResolver.Profile> availableProfiles() {
        List<NetworkIdentityResolver.Profile> profiles = new ArrayList<>();
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                Snapshot snapshot = inspect(network);
                if (snapshot.network() == null) {
                    continue;
                }
                NetworkIdentityResolver.Profile profile =
                        identityResolver.profile(snapshot.capabilities());
                if (profile.available()
                        && profiles.stream().noneMatch(
                                existing -> existing.identity().equals(profile.identity())
                        )) {
                    profiles.add(profile);
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to enumerate network profiles", exception);
        }
        return List.copyOf(profiles);
    }

    private void scheduleIfComplete() {
        synchronized (lock) {
            if (selectedCapabilities == null || selectedLinkProperties == null) {
                return;
            }
        }
        scheduleEvaluation(READY_DEBOUNCE_MILLIS);
    }

    private void removeCallbackCandidate(Network network) {
        boolean replacementAvailable;
        synchronized (lock) {
            long handle = network.getNetworkHandle();
            callbackCandidates.onLost(handle);
            callbackSnapshots.remove(handle);
            selectBestCallbackCandidateLocked();
            replacementAvailable = selectedNetwork != null;
        }
        scheduleEvaluation(
                replacementAvailable ? READY_DEBOUNCE_MILLIS : LOST_HANDOVER_GRACE_MILLIS
        );
    }

    private void scheduleEvaluation(long delayMillis) {
        handler.removeCallbacks(evaluation);
        handler.postDelayed(evaluation, delayMillis);
    }

    private void evaluateAndNotify() {
        Snapshot callbackSnapshot;
        UnderlyingNetworkCandidates.Exclusion exclusion;
        synchronized (lock) {
            callbackSnapshot = new Snapshot(
                    selectedNetwork,
                    selectedCapabilities,
                    selectedLinkProperties
            );
            exclusion = callbackCandidates.exclusion();
        }

        State state = stateOf(callbackSnapshot);
        Snapshot fallback = state.available()
                ? new Snapshot(null, null, null)
                : inspectBestAvailableNetwork(exclusion.networkHandle());

        Listener current;
        synchronized (lock) {
            if (!Objects.equals(selectedNetwork, callbackSnapshot.network())
                    || !Objects.equals(
                            selectedCapabilities,
                            callbackSnapshot.capabilities()
                    )
                    || !Objects.equals(
                            selectedLinkProperties,
                            callbackSnapshot.linkProperties()
                    )
                    || !exclusion.equals(callbackCandidates.exclusion())) {
                scheduleIfComplete();
                return;
            }
            if (fallback.network() != null
                    && callbackCandidates.acceptsFallback(
                            fallback.network().getNetworkHandle(),
                            exclusion
                    )) {
                cacheCallbackSnapshotLocked(fallback);
                selectBestCallbackCandidateLocked();
                state = stateOf(new Snapshot(
                        selectedNetwork,
                        selectedCapabilities,
                        selectedLinkProperties
                ));
            }
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
        return inspectBestAvailableNetwork(0L);
    }

    private Snapshot inspectBestAvailableNetwork(long excludedNetworkHandle) {
        try {
            Network active = connectivityManager.getActiveNetwork();
            Snapshot activeSnapshot = inspect(active);
            if (activeSnapshot.network() != null
                    && activeSnapshot.network().getNetworkHandle() != excludedNetworkHandle) {
                return activeSnapshot;
            }

            // Callback candidates are authoritative. This synchronous scan is
            // only a startup/delayed-loss fallback for coalesced callbacks.
            Snapshot best = new Snapshot(null, null, null);
            int bestRank = Integer.MIN_VALUE;
            for (Network network : connectivityManager.getAllNetworks()) {
                if (network.getNetworkHandle() == excludedNetworkHandle) {
                    continue;
                }
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

    private void cacheCallbackSnapshotLocked(Snapshot snapshot) {
        long handle = snapshot.network().getNetworkHandle();
        callbackSnapshots.put(handle, snapshot);
        callbackCandidates.update(handle, transportRank(snapshot.capabilities()));
    }

    private void selectBestCallbackCandidateLocked() {
        UnderlyingNetworkCandidates.Candidate best = callbackCandidates.best();
        Snapshot snapshot = best == null
                ? null
                : callbackSnapshots.get(best.networkHandle());
        selectedNetwork = snapshot == null ? null : snapshot.network();
        selectedCapabilities = snapshot == null ? null : snapshot.capabilities();
        selectedLinkProperties = snapshot == null ? null : snapshot.linkProperties();
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

    private State stateOf(Snapshot snapshot) {
        if (snapshot == null
                || snapshot.network() == null
                || !isUsableUnderlying(snapshot.capabilities())
                || snapshot.linkProperties() == null) {
            return State.unavailable();
        }
        LinkProperties properties = snapshot.linkProperties();
        boolean ipv6Usable =
                hasGlobalIpv6Address(properties) && hasIpv6DefaultRoute(properties);
        NetworkIdentityResolver.Profile profile =
                identityResolver.profile(snapshot.capabilities());
        return new State(
                snapshot.network().getNetworkHandle(),
                linkSignature(properties),
                ipv6Usable,
                snapshot.capabilities().hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                dnsServers(properties),
                profile.identity(),
                profile.kind(),
                profile.label()
        );
    }

    private static List<String> dnsServers(LinkProperties properties) {
        List<String> servers = new ArrayList<>();
        for (InetAddress dnsServer : properties.getDnsServers()) {
            String address = dnsServer.getHostAddress();
            if (address != null && !address.isBlank()) {
                servers.add(address);
            }
        }
        return List.copyOf(servers);
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
