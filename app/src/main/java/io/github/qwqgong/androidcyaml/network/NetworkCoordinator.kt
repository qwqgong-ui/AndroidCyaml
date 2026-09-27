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
        fun rebuildForNetwork(state: NetworkState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var state: NetworkState = monitor.currentState()

    private var pendingSelectionRestoration: Runnable? = null
    private var selectionRestorationGeneration = 0L

    // Dimensions a previous transition could not reconcile. Retry on a timer as
    // well as on new callbacks: the monitor suppresses identical network states.
    private var pendingTransition: NetworkTransition = NetworkTransition.none()
    private val transitionRetry = NetworkTransitionRetry(
        schedule = { task, delay -> mainHandler.postDelayed(task, delay) },
        remove = { task -> mainHandler.removeCallbacks(task) },
        dispatch = host::submit,
        retry = { apply(state) },
    )

    init {
        lifecycle.setIdleEffectiveState(overrideStore.settings(), state)
    }

    fun start(): NetworkState {
        transitionRetry.clear()
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
        transitionRetry.clear()
        pendingTransition = NetworkTransition.none()
        state = monitor.currentState()
        return state
    }

    fun stop() {
        transitionRetry.clear()
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

        // WebView XHTTP resolves through an explicit physical Network. Ordinary
        // protected sockets follow Android's default route across handovers.
        if (transition.routeChanged) {
            lifecycle.updateWebViewUnderlyingNetwork(next.networkHandle)
        }

        val settings = overrideStore.settings()
        if (!lifecycle.hasActiveService()) {
            // Nothing to reconcile against, and a later start rebuilds the runtime
            // from the state observed at that moment, so no work is owed.
            pendingTransition = NetworkTransition.none()
            transitionRetry.clear()
            lifecycle.setIdleEffectiveState(settings, next)
            host.publish(host.snapshot())
            return
        }

        // The core does not own the Android VPN descriptor. When a real network
        // changes address family, rebuild the platform TUN too; otherwise apps
        // continue choosing cached IPv6 destinations after core gating turns off.
        // Network loss is not a change of address family. Wait for the next
        // physical network before deciding whether the platform TUN must change.
        if (next.requiresTunRebuild(
                settings.ipv6Enabled,
                lifecycle.tunIpv6Enabled,
                lifecycle.runtime() != null,
            )
        ) {
            val rebuilt = reconcile(description, "tun") { host.rebuildForNetwork(next) }
            pendingTransition = if (rebuilt) NetworkTransition.none()
                else NetworkTransition(true, true, true, false, true)
            transitionRetry.update(pendingTransition.changed())
            host.publish(host.snapshot())
            return
        }
        applyTcpConcurrent(settings.adaptiveTcpConcurrent)
        pendingTransition = applyRuntimeTransition(next, transition, settings.ipv6Enabled)
        transitionRetry.update(pendingTransition.changed())
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
     * transition is replayed by the retry timer or the next network transition.
     */
    private fun applyRuntimeTransition(
        next: NetworkState,
        transition: NetworkTransition,
        configuredIpv6: Boolean,
    ): NetworkTransition {
        val description = transitionDescription(transition, next)
        // No runtime means nothing was reconciled; the whole transition stays owed.
        val runtime = lifecycle.runtime() ?: return transition

        val failed = transition.reconcile { dimension ->
            reconcile(description, dimension.name.lowercase()) {
                when (dimension) {
                    NetworkDimension.CACHE -> runtime.updateNetworkEnvironment(next.cacheIdentity())
                    NetworkDimension.DNS -> runtime.updateSystemDns(next.dnsServers)
                    NetworkDimension.IPV6 -> {
                        runtime.updateIpv6Availability(next.ipv6Usable)
                        lifecycle.updateEffectiveIpv6(configuredIpv6 && next.ipv6Usable)
                    }
                    NetworkDimension.ROUTE -> runtime.onPhysicalRouteChanged()
                }
            }
        }
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
