package io.github.qwqgong.androidcyaml

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tracks the best validated non-VPN network.
 *
 * The application's default network becomes its own VPN after establish(), so
 * registerDefaultNetworkCallback() and getActiveNetwork() cannot be used as the
 * authoritative underlying-network source while the tunnel is running.
 */
class Ipv6EnvironmentMonitor(context: Context) {
    fun interface Listener {
        fun onUnderlyingNetworkChanged(state: State)
    }

    data class State(
        val networkHandle: Long,
        val linkSignature: String,
        val ipv6Usable: Boolean,
        val wifi: Boolean,
        val dnsServers: List<String>,
        val selectionIdentity: String,
        val selectionKind: String,
        val selectionLabel: String,
    ) {
        fun available(): Boolean = networkHandle != 0L

        fun cacheIdentity(): String {
            if (!available()) {
                return ""
            }
            if (selectionIdentity.isNotBlank()) {
                return selectionIdentity
            }
            val fallbackKind = selectionKind.ifBlank { if (wifi) "wifi" else "cellular" }
            return NetworkIdentityResolver.pathFingerprint(fallbackKind, linkSignature)
        }

        fun pathChangedFrom(previous: State?): Boolean = previous == null ||
            networkHandle != previous.networkHandle ||
            linkSignature != previous.linkSignature

        companion object {
            fun unavailable(): State = State(0L, "", false, false, emptyList(), "", "", "")

            fun of(
                networkHandle: Long,
                linkSignature: String?,
                ipv6Usable: Boolean,
                wifi: Boolean,
                dnsServers: List<String>?,
                selectionIdentity: String?,
                selectionKind: String?,
                selectionLabel: String?,
            ): State {
                if (networkHandle == 0L) {
                    return unavailable()
                }
                return State(
                    networkHandle,
                    linkSignature ?: "",
                    ipv6Usable,
                    wifi,
                    dnsServers?.toList() ?: emptyList(),
                    selectionIdentity ?: "",
                    selectionKind ?: "",
                    selectionLabel ?: "",
                )
            }

            fun of(
                networkHandle: Long,
                linkSignature: String?,
                ipv6Usable: Boolean,
                wifi: Boolean,
                dnsServers: List<String>?,
                selectionIdentity: String?,
            ): State = of(
                networkHandle,
                linkSignature,
                ipv6Usable,
                wifi,
                dnsServers,
                selectionIdentity,
                if (wifi) "wifi" else "cellular",
                if (wifi) "Wi-Fi" else "移动数据",
            )
        }
    }

    private data class Snapshot(
        val network: Network?,
        val capabilities: NetworkCapabilities?,
        val linkProperties: LinkProperties?,
    )

    private val identityResolver = NetworkIdentityResolver(context)
    private val connectivityManager: ConnectivityManager =
        requireNotNull(
            context.applicationContext.getSystemService(ConnectivityManager::class.java),
        )
    private val callbackThread = HandlerThread("AndroidCyaml-NetworkMonitor")
    private val handler: Handler
    private val lock = Any()
    private val callbackSnapshots = LinkedHashMap<Long, Snapshot>()
    private val callbackCandidates = UnderlyingNetworkCandidates()
    private val evaluation = Runnable { evaluateAndNotify() }
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                callbackCandidates.onAvailable(network.networkHandle)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            if (!isUsableUnderlying(capabilities)) {
                removeCallbackCandidate(network)
                return
            }
            var properties = connectivityManager.getLinkProperties(network)
            synchronized(lock) {
                val handle = network.networkHandle
                val previous = callbackSnapshots[handle]
                if (properties == null && previous != null) {
                    properties = previous.linkProperties
                }
                callbackSnapshots[handle] = Snapshot(network, capabilities, properties)
                if (properties != null) {
                    callbackCandidates.update(handle, transportRank(capabilities))
                }
                selectBestCallbackCandidateLocked()
            }
            scheduleIfComplete()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            synchronized(lock) {
                val handle = network.networkHandle
                val capabilities = callbackSnapshots[handle]?.capabilities
                callbackSnapshots[handle] = Snapshot(network, capabilities, properties)
                if (isUsableUnderlying(capabilities)) {
                    callbackCandidates.update(handle, transportRank(capabilities!!))
                }
                selectBestCallbackCandidateLocked()
            }
            scheduleIfComplete()
        }

        override fun onLost(network: Network) {
            removeCallbackCandidate(network)
        }
    }

    private var listener: Listener? = null
    private var registered = false
    private var selectedNetwork: Network? = null
    private var selectedCapabilities: NetworkCapabilities? = null
    private var selectedLinkProperties: LinkProperties? = null
    private var lastState: State? = null

    init {
        callbackThread.start()
        handler = Handler(callbackThread.looper)
    }

    fun start(nextListener: Listener): State {
        synchronized(lock) {
            listener = nextListener
        }
        if (!registered) {
            connectivityManager.registerNetworkCallback(request, callback, handler)
            registered = true
        }

        val initial = inspectBestAvailableNetwork()
        val initialState = stateOf(initial)
        synchronized(lock) {
            if (selectedNetwork == null && initial.network != null) {
                cacheCallbackSnapshotLocked(initial)
                selectBestCallbackCandidateLocked()
            }
            lastState = initialState
        }
        return initialState
    }

    fun stop() {
        handler.removeCallbacks(evaluation)
        if (registered) {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (ignored: IllegalArgumentException) {
                // Callback may already have been removed by the framework.
            }
        }
        registered = false
        synchronized(lock) {
            listener = null
            selectedNetwork = null
            selectedCapabilities = null
            selectedLinkProperties = null
            callbackSnapshots.clear()
            callbackCandidates.clear()
            lastState = null
        }
    }

    fun currentState(): State {
        synchronized(lock) {
            val network = selectedNetwork
            val capabilities = selectedCapabilities
            val properties = selectedLinkProperties
            if (network != null && capabilities != null && properties != null) {
                return stateOf(Snapshot(network, capabilities, properties))
            }
            val known = lastState
            if (registered && known != null) {
                return known
            }
        }
        return stateOf(inspectBestAvailableNetwork())
    }

    fun availableProfiles(): List<NetworkIdentityResolver.Profile> {
        val profiles = ArrayList<NetworkIdentityResolver.Profile>()
        try {
            for (network in connectivityManager.allNetworks) {
                val snapshot = inspect(network)
                if (snapshot.network == null) {
                    continue
                }
                val profile = identityResolver.profile(snapshot.capabilities)
                if (profile.available() && profiles.none { it.identity == profile.identity }) {
                    profiles.add(profile)
                }
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to enumerate network profiles", exception)
        }
        return profiles.toList()
    }

    private fun scheduleIfComplete() {
        synchronized(lock) {
            if (selectedCapabilities == null || selectedLinkProperties == null) {
                return
            }
        }
        scheduleEvaluation(READY_DEBOUNCE_MILLIS)
    }

    private fun removeCallbackCandidate(network: Network) {
        val replacementAvailable: Boolean
        synchronized(lock) {
            val handle = network.networkHandle
            callbackCandidates.onLost(handle)
            callbackSnapshots.remove(handle)
            selectBestCallbackCandidateLocked()
            replacementAvailable = selectedNetwork != null
        }
        scheduleEvaluation(
            if (replacementAvailable) READY_DEBOUNCE_MILLIS else LOST_HANDOVER_GRACE_MILLIS,
        )
    }

    private fun scheduleEvaluation(delayMillis: Long) {
        handler.removeCallbacks(evaluation)
        handler.postDelayed(evaluation, delayMillis)
    }

    private fun evaluateAndNotify() {
        val callbackSnapshot: Snapshot
        val exclusion: UnderlyingNetworkCandidates.Exclusion
        synchronized(lock) {
            callbackSnapshot =
                Snapshot(selectedNetwork, selectedCapabilities, selectedLinkProperties)
            exclusion = callbackCandidates.exclusion()
        }

        var state = stateOf(callbackSnapshot)
        val fallback = if (state.available()) {
            Snapshot(null, null, null)
        } else {
            inspectBestAvailableNetwork(exclusion.networkHandle)
        }

        val current: Listener?
        synchronized(lock) {
            if (selectedNetwork != callbackSnapshot.network ||
                selectedCapabilities != callbackSnapshot.capabilities ||
                selectedLinkProperties != callbackSnapshot.linkProperties ||
                exclusion != callbackCandidates.exclusion()
            ) {
                scheduleIfComplete()
                return
            }
            val fallbackNetwork = fallback.network
            if (fallbackNetwork != null &&
                callbackCandidates.acceptsFallback(fallbackNetwork.networkHandle, exclusion)
            ) {
                cacheCallbackSnapshotLocked(fallback)
                selectBestCallbackCandidateLocked()
                state = stateOf(
                    Snapshot(selectedNetwork, selectedCapabilities, selectedLinkProperties),
                )
            }
            if (state == lastState) {
                return
            }
            lastState = state
            current = listener
        }
        current?.onUnderlyingNetworkChanged(state)
    }

    private fun inspectBestAvailableNetwork(excludedNetworkHandle: Long = 0L): Snapshot {
        try {
            val activeSnapshot = inspect(connectivityManager.activeNetwork)
            val activeNetwork = activeSnapshot.network
            if (activeNetwork != null && activeNetwork.networkHandle != excludedNetworkHandle) {
                return activeSnapshot
            }

            // Callback candidates are authoritative. This synchronous scan is
            // only a startup/delayed-loss fallback for coalesced callbacks.
            var best = Snapshot(null, null, null)
            var bestRank = Int.MIN_VALUE
            for (network in connectivityManager.allNetworks) {
                if (network.networkHandle == excludedNetworkHandle) {
                    continue
                }
                val candidate = inspect(network)
                val capabilities = candidate.capabilities
                if (candidate.network == null || capabilities == null) {
                    continue
                }
                val rank = transportRank(capabilities)
                if (rank > bestRank) {
                    best = candidate
                    bestRank = rank
                }
            }
            return best
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to inspect the underlying network", exception)
            return Snapshot(null, null, null)
        }
    }

    private fun cacheCallbackSnapshotLocked(snapshot: Snapshot) {
        val handle = snapshot.network!!.networkHandle
        callbackSnapshots[handle] = snapshot
        callbackCandidates.update(handle, transportRank(snapshot.capabilities!!))
    }

    private fun selectBestCallbackCandidateLocked() {
        val best = callbackCandidates.best()
        val snapshot = if (best == null) null else callbackSnapshots[best.networkHandle]
        selectedNetwork = snapshot?.network
        selectedCapabilities = snapshot?.capabilities
        selectedLinkProperties = snapshot?.linkProperties
    }

    private fun inspect(network: Network?): Snapshot {
        if (network == null) {
            return Snapshot(null, null, null)
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val properties = connectivityManager.getLinkProperties(network)
        if (!isUsableUnderlying(capabilities) || properties == null) {
            return Snapshot(null, null, null)
        }
        return Snapshot(network, capabilities, properties)
    }

    private fun stateOf(snapshot: Snapshot?): State {
        val network = snapshot?.network
        val capabilities = snapshot?.capabilities
        val properties = snapshot?.linkProperties
        if (network == null || !isUsableUnderlying(capabilities) || properties == null) {
            return State.unavailable()
        }
        val ipv6Usable = hasGlobalIpv6Address(properties) && hasIpv6DefaultRoute(properties)
        val profile = identityResolver.profile(capabilities)
        return State.of(
            network.networkHandle,
            linkSignature(properties),
            ipv6Usable,
            capabilities!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            dnsServers(properties),
            profile.identity,
            profile.kind,
            profile.label,
        )
    }

    private companion object {
        const val TAG = "AndroidCyaml/Network"
        const val READY_DEBOUNCE_MILLIS = 100L
        // VALIDATED can briefly disappear while Android re-checks an otherwise
        // healthy path. Keep the last usable path long enough to avoid tearing
        // down all proxy connections during that harmless validation flap.
        const val LOST_HANDOVER_GRACE_MILLIS = 2_000L

        fun isUsableUnderlying(capabilities: NetworkCapabilities?): Boolean =
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        fun transportRank(capabilities: NetworkCapabilities): Int = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            else -> 1
        }

        fun dnsServers(properties: LinkProperties): List<String> {
            val servers = ArrayList<String>()
            for (dnsServer in properties.dnsServers) {
                val address = dnsServer.hostAddress
                if (!address.isNullOrBlank()) {
                    servers.add(address)
                }
            }
            return servers.toList()
        }

        fun linkSignature(properties: LinkProperties): String {
            val values = ArrayList<String>()
            values.add("if=" + (properties.interfaceName ?: ""))
            for (address in properties.linkAddresses) {
                values.add("addr=$address")
            }
            for (route in properties.routes) {
                values.add("route=$route")
            }
            for (dnsServer in properties.dnsServers) {
                values.add("dns=" + dnsServer.hostAddress)
            }
            values.sort()
            return values.joinToString("|")
        }

        fun hasGlobalIpv6Address(properties: LinkProperties): Boolean {
            for (linkAddress in properties.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet6Address && isGlobalIpv6(address)) {
                    return true
                }
            }
            return false
        }

        fun hasIpv6DefaultRoute(properties: LinkProperties): Boolean {
            for (route in properties.routes) {
                if (route.isDefaultRoute && route.destination.address is Inet6Address) {
                    return true
                }
            }
            return false
        }

        fun isGlobalIpv6(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isMulticastAddress ||
                address.isSiteLocalAddress
            ) {
                return false
            }
            val bytes = address.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) != 0xfc
        }
    }
}
