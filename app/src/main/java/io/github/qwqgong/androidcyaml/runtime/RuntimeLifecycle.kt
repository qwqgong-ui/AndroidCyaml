package io.github.qwqgong.androidcyaml

import android.util.Log
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
        // The user's IPv6 intent owns the TUN shape. Physical IPv6 availability is a separate
        // runtime signal and must never rebuild the whole Android VPN.
        val requestedIpv6 = settings.ipv6Enabled
        val requestedTcpConcurrent = settings.adaptiveTcpConcurrent
        try {
            return startRuntime(
                settings,
                networkState,
                requestedIpv6,
                requestedTcpConcurrent,
                runtimeStarted,
            )
        } catch (firstFailure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw firstFailure
        } catch (firstFailure: IOException) {
            if (!requestedIpv6) {
                throw firstFailure
            }
            Log.w(TAG, "IPv6 runtime startup failed; retrying with IPv6 disabled", firstFailure)
            service?.let { host ->
                DiagnosticsLog.append(
                    host,
                    "runtime.ipv6.fallback",
                    "error=" + DiagnosticsLog.oneLine(Exceptions.usefulMessage(firstFailure)),
                )
            }
            try {
                return startRuntime(
                    settings,
                    networkState,
                    false,
                    requestedTcpConcurrent,
                    runtimeStarted,
                ) + " · IPv6 自动关闭"
            } catch (fallbackFailure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw combinedStartFailure(firstFailure, fallbackFailure)
            } catch (fallbackFailure: IOException) {
                throw combinedStartFailure(firstFailure, fallbackFailure)
            }
        }
    }

    private fun combinedStartFailure(firstFailure: Exception, fallbackFailure: Exception) =
        IOException(
            "IPv6 模式启动失败：" + Exceptions.usefulMessage(firstFailure) +
                "；IPv4 回退也失败：" + Exceptions.usefulMessage(fallbackFailure),
            fallbackFailure,
        )

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
            effectiveIpv6Enabled = ipv6Enabled && networkState.ipv6Usable
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

    private companion object {
        const val TAG = "AndroidCyaml/Coordinator"
    }
}
