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

    // Dimensions a previous transition observed but could not reconcile with the
    // runtime. They are replayed on the next transition; see mergePending.
    private var pendingTransition: NetworkTransition = NetworkTransition.none()

    init {
        lifecycle.setIdleEffectiveState(overrideStore.settings(), state)
    }

    fun start(): NetworkState {
        // The runtime is built from this state, so it starts fully reconciled.
        pendingTransition = NetworkTransition.none()
        state = monitor.start { next -> host.submit { apply(next) } }
        updateDiagnostics(state)
        host.diagnostic("network.initial", stateDescription(state))
        Log.i(TAG, "Initial network state: " + stateDescription(state))
        return state
    }

    fun currentState(): NetworkState = monitor.currentState()

    /**
     * Advances the observed state without reconciling anything, for callers that
     * rebuild the runtime from the returned state immediately afterwards. That
     * rebuild reconciles every dimension, so nothing stays owed.
     */
    fun refreshState(): NetworkState {
        pendingTransition = NetworkTransition.none()
        state = monitor.currentState()
        return state
    }

    fun stop() {
        pendingTransition = NetworkTransition.none()
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
        val transition = next.transitionFrom(previous).mergePending(pendingTransition)
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
            // Nothing to reconcile against, and a later start rebuilds the runtime
            // from the state observed at that moment, so no work is owed.
            pendingTransition = NetworkTransition.none()
            lifecycle.setIdleEffectiveState(settings, next)
            host.publish(host.snapshot())
            return
        }

        applyTcpConcurrent(settings.adaptiveTcpConcurrent)
        pendingTransition = applyRuntimeTransition(next, transition, settings.ipv6Enabled)
        if (identityChanged && next.available()) scheduleSelectionRestoration()
        host.publish(host.snapshot())
    }

    /**
     * Reconciles the runtime with [next] and reports the dimensions that failed.
     *
     * Every dimension commits on its own. A single `try` around all four used to
     * mean that one failing native call skipped the rest -- including the closing
     * of the old network's connections, which runs last -- and because the
     * observed state had already advanced, no later transition would report those
     * dimensions as changed again. The work was dropped silently. The returned
     * transition is replayed on the next transition instead.
     */
    private fun applyRuntimeTransition(
        next: NetworkState,
        transition: NetworkTransition,
        configuredIpv6: Boolean,
    ): NetworkTransition {
        val description = transitionDescription(transition, next)
        // No runtime means nothing was reconciled; the whole transition stays owed.
        val runtime = lifecycle.runtime() ?: return transition

        val cacheFailed = transition.cacheChanged && !reconcile(description, "cache") {
            runtime.updateNetworkEnvironment(next.cacheIdentity())
        }
        val dnsFailed = transition.dnsChanged && !reconcile(description, "dns") {
            runtime.updateSystemDns(next.dnsServers)
        }
        var ipv6Failed = false
        if (transition.ipv6Changed) {
            ipv6Failed = !reconcile(description, "ipv6") {
                runtime.updateIpv6Availability(next.ipv6Usable)
            }
            if (!ipv6Failed) {
                lifecycle.updateEffectiveIpv6(configuredIpv6 && next.ipv6Usable)
            }
        }
        val routeFailed = transition.routeChanged && !reconcile(description, "route") {
            runtime.onPhysicalRouteChanged()
        }

        val failed = NetworkTransition(
            routeChanged = routeFailed,
            dnsChanged = dnsFailed,
            ipv6Changed = ipv6Failed,
            // Selector memory is not reconciled here, so it is never owed.
            identityChanged = false,
            cacheChanged = cacheFailed,
        )
        if (!failed.changed()) {
            Log.i(TAG, "Applied network transition: $description")
        }
        return failed
    }

    private fun reconcile(
        description: String,
        dimension: String,
        operation: () -> Unit,
    ): Boolean = try {
        operation()
        true
    } catch (exception: IOException) {
        Log.w(TAG, "Unable to apply $dimension network transition", exception)
        NetworkDiagnostics.onRefreshFailed()
        host.diagnostic(
            "network.transition.failed",
            "dimension=" + dimension + " " + description + " error=" +
                DiagnosticsLog.oneLine(exception.message ?: exception.javaClass.simpleName),
        )
        false
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
