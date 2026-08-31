package io.github.qwqgong.androidcyaml.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.qwqgong.androidcyaml.NetworkDiagnostics
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Observes Android's best validated non-VPN network.
 *
 * Routing remains in system-default mode: this monitor does not request a network and does not
 * impose its own Wi-Fi/cellular ranking. The selected handle is used for network identity,
 * per-network memory and WebView XHTTP's direct escape path only.
 */
class UnderlyingNetworkMonitor(context: Context) {
    fun interface Listener {
        fun onUnderlyingNetworkChanged(state: NetworkState)
    }

    private data class Snapshot(
        val network: Network?,
        val capabilities: NetworkCapabilities?,
        val linkProperties: LinkProperties?,
    )

    private val identityResolver = NetworkIdentityResolver(context)
    private val diagnosticsContext = context.applicationContext
    private val connectivityManager: ConnectivityManager = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    )
    private val callbackThread = HandlerThread("AndroidCyaml-NetworkMonitor")
    private val handler: Handler
    private val lock = Any()
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .build()

    private var listener: Listener? = null
    private var registered = false
    private var bestCallbackRegistered = false
    private var inventoryCallbackRegistered = false
    private var generation = 0L
    private var selectedNetwork: Network? = null
    private var selectedCapabilities: NetworkCapabilities? = null
    private var selectedLinkProperties: LinkProperties? = null
    private var lastState: NetworkState? = null
    private val observedProfiles = LinkedHashMap<Long, NetworkIdentityResolver.Profile>()

    private val callback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onAvailable(network: Network) {
            NetworkDiagnostics.onAvailable(diagnosticsContext, network.networkHandle)
            synchronized(lock) {
                if (!registered) return
                if (selectedNetwork != network) {
                    selectedNetwork = network
                    selectedCapabilities = null
                    selectedLinkProperties = null
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            if (isUsableUnderlying(capabilities)) {
                NetworkDiagnostics.onCapabilitiesChanged(
                    diagnosticsContext,
                    network.networkHandle,
                    capabilities,
                )
            } else {
                NetworkDiagnostics.onUnusable(
                    diagnosticsContext,
                    network.networkHandle,
                    capabilities,
                )
            }
            synchronized(lock) {
                if (!registered || selectedNetwork != network) return
                selectedCapabilities = capabilities
            }
            scheduleIfComplete(READY_DEBOUNCE_MILLIS)
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            NetworkDiagnostics.onLinkPropertiesChanged()
            synchronized(lock) {
                if (!registered || selectedNetwork != network) return
                selectedLinkProperties = properties
            }
            scheduleIfComplete(READY_DEBOUNCE_MILLIS)
        }

        override fun onLost(network: Network) {
            NetworkDiagnostics.onLost(diagnosticsContext, network.networkHandle)
            val lostGeneration: Long
            synchronized(lock) {
                if (!registered || selectedNetwork != network) return
                selectedNetwork = null
                selectedCapabilities = null
                selectedLinkProperties = null
                lostGeneration = generation
            }
            handler.postDelayed(
                { publishIfCurrent(lostGeneration) },
                LOST_HANDOVER_GRACE_MILLIS,
            )
        }
    }

    /** Observes every validated option for the selector UI without affecting system routing. */
    private val inventoryCallback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            synchronized(lock) {
                if (!registered) return
                val profile = identityResolver.profile(capabilities)
                if (isUsableUnderlying(capabilities) && profile.available()) {
                    observedProfiles[network.networkHandle] = profile
                } else {
                    observedProfiles.remove(network.networkHandle)
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                observedProfiles.remove(network.networkHandle)
            }
        }
    }

    init {
        callbackThread.start()
        handler = Handler(callbackThread.looper)
    }

    fun start(nextListener: Listener): NetworkState {
        val initial = inspect(connectivityManager.activeNetwork)
        val initialState = stateOf(initial)
        synchronized(lock) {
            listener = nextListener
            generation++
            selectedNetwork = initial.network
            selectedCapabilities = initial.capabilities
            selectedLinkProperties = initial.linkProperties
            lastState = initialState
            observedProfiles.clear()
            initial.capabilities?.let { capabilities ->
                val profile = identityResolver.profile(capabilities)
                if (initial.network != null && profile.available()) {
                    observedProfiles[initial.network.networkHandle] = profile
                }
            }
            if (!registered) {
                registered = true
                try {
                    connectivityManager.registerNetworkCallback(request, inventoryCallback, handler)
                    inventoryCallbackRegistered = true
                    connectivityManager.registerBestMatchingNetworkCallback(request, callback, handler)
                    bestCallbackRegistered = true
                } catch (exception: RuntimeException) {
                    unregisterCallbacksLocked()
                    registered = false
                    throw exception
                }
            }
        }
        return initialState
    }

    fun stop() {
        synchronized(lock) {
            generation++
            unregisterCallbacksLocked()
            registered = false
            listener = null
            selectedNetwork = null
            selectedCapabilities = null
            selectedLinkProperties = null
            lastState = null
            observedProfiles.clear()
        }
        handler.removeCallbacksAndMessages(null)
    }

    fun currentState(): NetworkState = synchronized(lock) {
        val current = stateOf(
            Snapshot(selectedNetwork, selectedCapabilities, selectedLinkProperties),
        )
        if (current.available()) current else lastState ?: NetworkState.unavailable()
    }

    fun availableProfiles(): List<NetworkIdentityResolver.Profile> {
        return synchronized(lock) {
            val profiles = LinkedHashMap<String, NetworkIdentityResolver.Profile>()
            for (profile in observedProfiles.values) {
                profiles.putIfAbsent(profile.identity, profile)
            }
            profiles.values.toList()
        }
    }

    private fun unregisterCallbacksLocked() {
        if (bestCallbackRegistered) {
            unregisterCallback(callback)
            bestCallbackRegistered = false
        }
        if (inventoryCallbackRegistered) {
            unregisterCallback(inventoryCallback)
            inventoryCallbackRegistered = false
        }
    }

    private fun unregisterCallback(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (ignored: IllegalArgumentException) {
            // The framework may already have removed the callback.
        }
    }

    private fun scheduleIfComplete(delayMillis: Long) {
        val expectedGeneration: Long
        synchronized(lock) {
            if (!registered || selectedCapabilities == null || selectedLinkProperties == null) return
            expectedGeneration = generation
        }
        handler.removeCallbacksAndMessages(EVALUATION_TOKEN)
        handler.postDelayed(
            { publishIfCurrent(expectedGeneration) },
            EVALUATION_TOKEN,
            delayMillis,
        )
    }

    private fun publishIfCurrent(expectedGeneration: Long) {
        val currentListener: Listener
        val state: NetworkState
        synchronized(lock) {
            if (!registered || expectedGeneration != generation) return
            state = stateOf(
                Snapshot(selectedNetwork, selectedCapabilities, selectedLinkProperties),
            )
            if (state == lastState) return
            lastState = state
            currentListener = listener ?: return
        }
        currentListener.onUnderlyingNetworkChanged(state)
    }

    private fun inspect(network: Network?): Snapshot {
        if (network == null) return Snapshot(null, null, null)
        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val properties = connectivityManager.getLinkProperties(network)
            if (!isUsableUnderlying(capabilities) || properties == null) {
                Snapshot(null, null, null)
            } else {
                Snapshot(network, capabilities, properties)
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to inspect the initial underlying network", exception)
            Snapshot(null, null, null)
        }
    }

    private fun stateOf(snapshot: Snapshot?): NetworkState {
        val network = snapshot?.network
        val capabilities = snapshot?.capabilities
        val properties = snapshot?.linkProperties
        if (network == null || !isUsableUnderlying(capabilities) || properties == null) {
            return NetworkState.unavailable()
        }
        val profile = identityResolver.profile(capabilities)
        return NetworkState.of(
            network.networkHandle,
            cachePathSignature(properties),
            hasGlobalIpv6Address(properties) && hasIpv6DefaultRoute(properties),
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
        const val LOST_HANDOVER_GRACE_MILLIS = 650L
        val EVALUATION_TOKEN = Any()

        fun isUsableUnderlying(capabilities: NetworkCapabilities?): Boolean =
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        fun dnsServers(properties: LinkProperties): List<String> = properties.dnsServers
            .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }

        fun cachePathSignature(properties: LinkProperties): String {
            val values = ArrayList<String>()
            values.add("if=" + (properties.interfaceName ?: ""))
            properties.linkAddresses
                .filter { it.address is Inet4Address }
                .forEach { values.add("addr=$it") }
            properties.routes
                .filter { it.destination.address is Inet4Address }
                .forEach { values.add("route=$it") }
            values.sort()
            return values.joinToString("|")
        }

        fun hasGlobalIpv6Address(properties: LinkProperties): Boolean =
            properties.linkAddresses.any { linkAddress ->
                val address = linkAddress.address
                address is Inet6Address && isGlobalIpv6(address)
            }

        fun hasIpv6DefaultRoute(properties: LinkProperties): Boolean =
            properties.routes.any { route ->
                route.isDefaultRoute && route.destination.address is Inet6Address
            }

        fun isGlobalIpv6(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isMulticastAddress ||
                address.isSiteLocalAddress
            ) {
                return false
            }
            val bytes = address.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) != 0xfc
        }
    }
}
