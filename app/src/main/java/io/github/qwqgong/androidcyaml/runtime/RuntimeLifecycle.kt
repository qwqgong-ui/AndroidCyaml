package io.github.qwqgong.androidcyaml

import io.github.qwqgong.androidcyaml.network.NetworkState
import io.github.qwqgong.androidcyaml.network.SelectorSession
import java.io.IOException

/** Owns the Android VPN, TUN, and embedded mihomo runtime resources. */
class RuntimeLifecycle(
    private val fileStore: MihomoFileStore,
    private val selectorSession: SelectorSession,
) {
    private var service: AndroidVpnService? = null
    private var tunManager: AndroidTunManager? = null
    private var platformCallbacks: NativePlatformCallbacks? = null

    @Volatile
    private var runtime: MihomoRuntime? = null

    @Volatile
    var effectiveIpv6Enabled = false
        private set

    @Volatile
    var effectiveTcpConcurrent = false
        private set

    fun service(): AndroidVpnService? = service

    fun runtime(): MihomoRuntime? = runtime

    fun ownsService(requestedService: AndroidVpnService?): Boolean = service === requestedService

    fun hasActiveService(): Boolean =
        service != null && tunManager != null && platformCallbacks != null

    fun start(
        requestedService: AndroidVpnService,
        settings: RuntimeOverrideSettings,
        networkState: NetworkState,
        runtimeStarted: Runnable?,
    ): String {
        stop()
        service = requestedService
        tunManager = AndroidTunManager(requestedService)
        val callbacks = NativePlatformCallbacks(requestedService)
        platformCallbacks = callbacks
        callbacks.updateWebViewUnderlyingNetwork(networkState.networkHandle)
        try {
            return restart(settings, networkState, runtimeStarted)
        } catch (failure: IOException) {
            // A failed restart() already leaves runtime == null, but service/
            // tunManager/platformCallbacks would otherwise stay set, making
            // hasActiveService() report "active" for a lifecycle that never
            // came up. Self-clean rather than depend on every caller doing it.
            stop()
            throw failure
        } catch (failure: InterruptedException) {
            stop()
            throw failure
        }
    }

    fun restart(
        settings: RuntimeOverrideSettings,
        networkState: NetworkState,
        runtimeStarted: Runnable?,
    ): String {
        // The TUN shape follows the user's IPv6 intent *and* whether the physical
        // network actually carries IPv6. A TUN that advertises IPv6 the core will
        // refuse to dial is worse than no IPv6 at all: apps that reach past DNS --
        // an HTTPDNS client, or one replaying addresses it cached on another
        // network -- keep dialing addresses that can never connect, with no DNS
        // answer left to steer them back. Losing or gaining physical IPv6 therefore
        // restarts the core and re-establishes the tunnel. The VpnService itself
        // stays up, and that restart is affordable because the change coincides
        // with the handover that already drops every connection.
        val requestedIpv6 = settings.ipv6Enabled && networkState.ipv6Usable
        return startRuntime(
            settings,
            networkState,
            requestedIpv6,
            settings.adaptiveTcpConcurrent,
            runtimeStarted,
        )
    }

    fun updateWebViewUnderlyingNetwork(networkHandle: Long) {
        platformCallbacks?.updateWebViewUnderlyingNetwork(networkHandle)
    }

    fun applyTcpConcurrent(enabled: Boolean): Boolean {
        val current = runtime
        if (current == null || enabled == effectiveTcpConcurrent) {
            return false
        }
        current.setTcpConcurrent(enabled)
        effectiveTcpConcurrent = enabled
        return true
    }

    fun updateEffectiveIpv6(enabled: Boolean) {
        effectiveIpv6Enabled = enabled
    }

    fun setIdleEffectiveState(
        settings: RuntimeOverrideSettings,
        networkState: NetworkState,
    ) {
        effectiveIpv6Enabled = settings.ipv6Enabled && networkState.ipv6Usable
        effectiveTcpConcurrent = settings.adaptiveTcpConcurrent
    }

    fun stop() {
        // Stopping the VPN is also leaving the current selector session. Without
        // this checkpoint, starting again on the same network restores a stale
        // choice because no physical-network handover occurred.
        selectorSession.checkpoint(runtime)
        closeRuntime()
        tunManager?.close()
        tunManager = null
        platformCallbacks?.close()
        platformCallbacks = null
        service = null
        effectiveIpv6Enabled = false
        effectiveTcpConcurrent = false
    }

    private fun startRuntime(
        settings: RuntimeOverrideSettings,
        networkState: NetworkState,
        ipv6Enabled: Boolean,
        tcpConcurrentEnabled: Boolean,
        runtimeStarted: Runnable?,
    ): String {
        val activeTunManager = tunManager
        val activeCallbacks = platformCallbacks
        if (!hasActiveService() || activeTunManager == null || activeCallbacks == null) {
            throw IOException("Android VPN 服务尚未初始化")
        }
        // A restart must not roll a selector back to the snapshot taken when the
        // current network was first entered. Capture the user's latest WebUI
        // choice while the old controller is still reachable.
        selectorSession.checkpoint(runtime)
        closeRuntime()
        val candidate = MihomoRuntime(
            fileStore,
            activeTunManager,
            activeCallbacks,
            settings,
            ipv6Enabled,
            tcpConcurrentEnabled,
            networkState.dnsServers,
            networkState.cacheIdentity(),
            networkState.ipv6Usable,
        )
        runtime = candidate
        try {
            val runningDetail = candidate.start()
            if (!activeTunManager.hasUsableTunnel()) {
                throw IOException("mihomo 未建立 Android TUN")
            }
            // ipv6Enabled already carries the physical-availability mask.
            effectiveIpv6Enabled = ipv6Enabled
            effectiveTcpConcurrent = tcpConcurrentEnabled
            runtimeStarted?.run()
            selectorSession.begin(candidate, networkState)
            return runningDetail
        } catch (exception: IOException) {
            candidate.close()
            runtime = null
            throw exception
        } catch (exception: InterruptedException) {
            candidate.close()
            runtime = null
            throw exception
        }
    }

    private fun closeRuntime() {
        runtime?.close()
        runtime = null
        selectorSession.reset()
    }
}
