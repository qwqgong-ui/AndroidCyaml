package io.github.qwqgong.androidcyaml.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.qwqgong.androidcyaml.MihomoRuntime
import io.github.qwqgong.androidcyaml.DiagnosticsLog
import io.github.qwqgong.androidcyaml.NetworkDiagnostics
import io.github.qwqgong.androidcyaml.RuntimeLifecycle
import io.github.qwqgong.androidcyaml.RuntimeOverrideStore
import io.github.qwqgong.androidcyaml.RuntimeSnapshot
import java.io.IOException

/** Owns physical-network observation, per-network memory and mihomo cache transitions. */
class NetworkCoordinator(
    private val monitor: UnderlyingNetworkMonitor,
    private val overrideStore: RuntimeOverrideStore,
    private val lifecycle: RuntimeLifecycle,
    private val selectorSession: SelectorSession,
    private val host: Host,
) {
    interface Host {
        fun submit(operation: Runnable)
        fun snapshot(): RuntimeSnapshot
        fun publish(snapshot: RuntimeSnapshot)
        fun diagnostic(event: String, detail: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var state: NetworkState = monitor.currentState()

    private var pendingSelectionRestoration: Runnable? = null
    private var selectionRestorationGeneration = 0L

    init {
        lifecycle.setIdleEffectiveState(overrideStore.settings(), state)
    }

    fun start(): NetworkState {
        state = monitor.start { next -> host.submit { apply(next) } }
        updateDiagnostics(state)
        host.diagnostic("network.initial", stateDescription(state))
        Log.i(TAG, "Initial network state: " + stateDescription(state))
        return state
    }

    fun currentState(): NetworkState = monitor.currentState()

    fun refreshState(): NetworkState {
        state = monitor.currentState()
        return state
    }

    fun stop() {
        cancelSelectionRestoration()
        monitor.stop()
    }

    fun cancelSelectionRestoration() {
        selectionRestorationGeneration++
        pendingSelectionRestoration?.let(mainHandler::removeCallbacks)
        pendingSelectionRestoration = null
    }

    fun selectorCatalog(runtime: MihomoRuntime): String = selectorSession.catalog(runtime)

    fun select(
        runtime: MihomoRuntime,
        identity: String?,
        group: String?,
        target: String?,
    ): String = selectorSession.select(runtime, identity, group, target)

    private fun apply(next: NetworkState) {
        val previous = state
        val transition = next.transitionFrom(previous)
        if (!transition.changed()) return

        val identityChanged = selectorSession.moveTo(next, lifecycle.runtime())
        if (identityChanged) cancelSelectionRestoration()
        state = next
        if (transition.routeChanged) NetworkDiagnostics.onHandover()
        updateDiagnostics(next)
        val description = transitionDescription(transition, next)
        host.diagnostic(
            if (transition.routeChanged) "network.handover" else "network.transition",
            description,
        )

        // Only WebView XHTTP needs an explicit physical Network to avoid resolving through the
        // VPN recursively. Normal mihomo sockets remain protect-only and use Android's default.
        if (transition.routeChanged) {
            lifecycle.updateWebViewUnderlyingNetwork(next.networkHandle)
        }

        val settings = overrideStore.settings()
        if (!lifecycle.hasActiveService()) {
            lifecycle.setIdleEffectiveState(settings, next)
            host.publish(host.snapshot())
            return
        }

        applyTcpConcurrent(settings.adaptiveTcpConcurrent)
        applyRuntimeTransition(next, transition, settings.ipv6Enabled)
        if (identityChanged && next.available()) scheduleSelectionRestoration()
        host.publish(host.snapshot())
    }

    private fun applyRuntimeTransition(
        next: NetworkState,
        transition: NetworkTransition,
        configuredIpv6: Boolean,
    ) {
        val runtime = lifecycle.runtime() ?: return
        val description = transitionDescription(transition, next)
        try {
            if (transition.cacheChanged) {
                runtime.updateNetworkEnvironment(next.cacheIdentity())
            }
            if (transition.dnsChanged) {
                runtime.updateSystemDns(next.dnsServers)
            }
            if (transition.ipv6Changed) {
                runtime.updateIpv6Availability(next.ipv6Usable)
                lifecycle.updateEffectiveIpv6(configuredIpv6 && next.ipv6Usable)
            }
            if (transition.routeChanged) {
                runtime.onPhysicalRouteChanged()
            }
            Log.i(TAG, "Applied network transition: $description")
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to apply network transition", exception)
            NetworkDiagnostics.onRefreshFailed()
            host.diagnostic(
                "network.transition.failed",
                description + " error=" +
                    DiagnosticsLog.oneLine(exception.message ?: exception.javaClass.simpleName),
            )
        }
    }

    private fun applyTcpConcurrent(enabled: Boolean) {
        if (enabled == lifecycle.effectiveTcpConcurrent) return
        try {
            if (lifecycle.applyTcpConcurrent(enabled)) {
                Log.i(TAG, if (enabled) "Enabled tcp-concurrent" else "Disabled tcp-concurrent")
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to update tcp-concurrent", exception)
        }
    }

    private fun scheduleSelectionRestoration() {
        cancelSelectionRestoration()
        val generation = selectionRestorationGeneration
        val pending = Runnable { host.submit { restoreSelection(generation) } }
        pendingSelectionRestoration = pending
        mainHandler.postDelayed(pending, SELECTION_RESTORE_STABILIZATION_MILLIS)
    }

    private fun restoreSelection(generation: Long) {
        if (generation != selectionRestorationGeneration) return
        pendingSelectionRestoration = null
        selectorSession.restoreOrRemember(lifecycle.runtime())
    }

    private companion object {
        const val TAG = "AndroidCyaml/Network"
        const val SELECTION_RESTORE_STABILIZATION_MILLIS = 1_000L

        fun transitionDescription(transition: NetworkTransition, state: NetworkState): String =
            "route=${transition.routeChanged} " +
                "dns=${transition.dnsChanged}, ipv6=${transition.ipv6Changed}, " +
                "identity=${transition.identityChanged}, cache=${transition.cacheChanged}, " +
                stateDescription(state)

        fun stateDescription(state: NetworkState): String =
            "handle=${state.networkHandle} " +
                "kind=${state.selectionKind.ifBlank { "-" }} " +
                "available=${state.available()} " +
                "ipv6Usable=${state.ipv6Usable} " +
                "dnsCount=${state.dnsServers.size} " +
                "identityKnown=${state.selectionIdentity.isNotBlank()}"

        fun updateDiagnostics(state: NetworkState) {
            NetworkDiagnostics.setUnderlying(
                state.networkHandle,
                state.selectionKind,
                state.ipv6Usable,
                state.dnsServers.size,
            )
        }
    }
}
