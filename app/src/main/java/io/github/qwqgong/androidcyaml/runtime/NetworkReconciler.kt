package io.github.qwqgong.androidcyaml

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

/** Owns underlying-network handover and delayed runtime reconciliation. */
class NetworkReconciler(
    private val networkMonitor: Ipv6EnvironmentMonitor,
    private val overrideStore: RuntimeOverrideStore,
    private val lifecycle: RuntimeLifecycle,
    private val selectorSession: SelectorSession,
    private val host: Host,
) {
    interface Host {
        fun submit(operation: Runnable)

        fun start(): String

        fun snapshot(): RuntimeSnapshot

        fun publish(snapshot: RuntimeSnapshot)

        fun publish(state: RuntimeState, detail: String)

        fun failActiveService(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var underlyingNetworkState: Ipv6EnvironmentMonitor.State

    private var pendingIpv6Reconciliation: Runnable? = null
    private var pendingSelectionRestoration: Runnable? = null
    private var ipv6ReconciliationGeneration = 0L
    private var selectionRestorationGeneration = 0L

    init {
        underlyingNetworkState = networkMonitor.currentState()
        lifecycle.setIdleEffectiveState(overrideStore.settings(), underlyingNetworkState)
    }

    fun start(): Ipv6EnvironmentMonitor.State {
        underlyingNetworkState = networkMonitor.start { state ->
            host.submit { onUnderlyingNetworkChanged(state) }
        }
        return underlyingNetworkState
    }

    fun currentState(): Ipv6EnvironmentMonitor.State {
        // Read straight from the (already thread-safe) monitor rather than the
        // locally cached field below: the cache only updates once the queued
        // onUnderlyingNetworkChanged() reconciliation runs on the coordinator
        // executor, which can be delayed behind a long-running start()/restart().
        // A start() that races ahead of that queued reconciliation must still see
        // the freshest known network, not a stale one left over from before the
        // switch.
        return networkMonitor.currentState()
    }

    fun refreshState(): Ipv6EnvironmentMonitor.State {
        underlyingNetworkState = networkMonitor.currentState()
        return underlyingNetworkState
    }

    fun cancelSelectionRestoration() {
        selectionRestorationGeneration++
        pendingSelectionRestoration?.let { mainHandler.removeCallbacks(it) }
        pendingSelectionRestoration = null
    }

    fun stop() {
        cancelIpv6Reconciliation()
        cancelSelectionRestoration()
        networkMonitor.stop()
    }

    private fun onUnderlyingNetworkChanged(state: Ipv6EnvironmentMonitor.State) {
        val previous = underlyingNetworkState
        if (state == previous) {
            return
        }
        val selectionIdentityChanged = selectorSession.moveTo(state, lifecycle.runtime())
        if (selectionIdentityChanged) {
            cancelSelectionRestoration()
        }
        underlyingNetworkState = state
        lifecycle.updateUnderlyingNetwork(state.networkHandle)
        val settings = overrideStore.settings()
        val targetTcpConcurrent = settings.adaptiveTcpConcurrent
        if (!lifecycle.hasActiveService()) {
            lifecycle.setIdleEffectiveState(settings, state)
            host.publish(host.snapshot())
            return
        }

        applyTcpConcurrentForNetwork(targetTcpConcurrent)
        refreshRuntimeForNetworkChange(previous, state, state.pathChangedFrom(previous))
        if (selectionIdentityChanged && state.available()) {
            scheduleSelectionRestoration()
        }
        if (!state.available()) {
            cancelIpv6Reconciliation()
            host.publish(host.snapshot())
            return
        }

        val targetIpv6 = settings.ipv6Enabled && state.ipv6Usable
        if (targetIpv6 == lifecycle.effectiveIpv6Enabled) {
            cancelIpv6Reconciliation()
            host.publish(host.snapshot())
            return
        }

        scheduleIpv6Reconciliation()
        host.publish(host.snapshot())
    }

    private fun applyTcpConcurrentForNetwork(targetEnabled: Boolean) {
        if (targetEnabled == lifecycle.effectiveTcpConcurrent) {
            return
        }
        try {
            if (!lifecycle.applyTcpConcurrent(targetEnabled)) {
                return
            }
            Log.i(TAG, if (targetEnabled) "Enabled tcp-concurrent" else "Disabled tcp-concurrent")
        } catch (exception: IOException) {
            // A best-effort dialing optimization must not tear down the VPN.
            Log.w(TAG, "Unable to update tcp-concurrent after network change", exception)
        }
    }

    private fun scheduleIpv6Reconciliation() {
        cancelIpv6Reconciliation()
        val generation = ipv6ReconciliationGeneration
        val pending = Runnable { host.submit { reconcileIpv6State(generation) } }
        pendingIpv6Reconciliation = pending
        mainHandler.postDelayed(pending, IPV6_REBUILD_STABILIZATION_MILLIS)
    }

    private fun cancelIpv6Reconciliation() {
        ipv6ReconciliationGeneration++
        pendingIpv6Reconciliation?.let { mainHandler.removeCallbacks(it) }
        pendingIpv6Reconciliation = null
    }

    private fun reconcileIpv6State(generation: Long) {
        if (generation != ipv6ReconciliationGeneration) {
            return
        }
        pendingIpv6Reconciliation = null
        val state = underlyingNetworkState
        if (!lifecycle.hasActiveService() || !state.available()) {
            return
        }
        val settings = overrideStore.settings()
        val targetIpv6 = settings.ipv6Enabled && state.ipv6Usable
        if (targetIpv6 == lifecycle.effectiveIpv6Enabled) {
            return
        }

        host.publish(
            RuntimeState.STARTING,
            if (targetIpv6) {
                "IPv6 环境已恢复，正在重建 JNI TUN…"
            } else {
                "当前 IPv6 环境不可用，正在重建为 IPv4-only…"
            },
        )
        try {
            host.publish(RuntimeState.RUNNING, host.start())
        } catch (exception: Exception) {
            host.failActiveService("IPv6 环境切换失败：" + Exceptions.usefulMessage(exception))
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
        if (generation != selectionRestorationGeneration) {
            return
        }
        pendingSelectionRestoration = null
        selectorSession.restoreOrRemember(lifecycle.runtime())
    }

    private fun refreshRuntimeForNetworkChange(
        previous: Ipv6EnvironmentMonitor.State?,
        currentState: Ipv6EnvironmentMonitor.State,
        pathChanged: Boolean,
    ) {
        val currentRuntime = lifecycle.runtime()
        if (currentRuntime == null || !pathChanged) {
            return
        }
        try {
            currentRuntime.onUnderlyingNetworkChanged(
                currentState.dnsServers,
                currentState.cacheIdentity(),
                requiresConnectionReset(previous, currentState),
            )
            Log.i(
                TAG,
                "Refreshed mihomo after underlying network change: " +
                    networkDescription(previous) + " -> " +
                    networkDescription(currentState),
            )
        } catch (exception: IOException) {
            // A transient handover failure must not tear down the VPN.
            Log.w(TAG, "Unable to refresh mihomo after network handover", exception)
        }
    }

    private companion object {
        const val TAG = "AndroidCyaml/Network"
        const val IPV6_REBUILD_STABILIZATION_MILLIS = 750L
        const val SELECTION_RESTORE_STABILIZATION_MILLIS = 1_000L

        fun networkDescription(state: Ipv6EnvironmentMonitor.State?): String =
            if (state == null || !state.available()) {
                "unavailable"
            } else {
                state.networkHandle.toString() +
                    (if (state.wifi) "/Wi-Fi" else "/non-Wi-Fi") +
                    (if (state.ipv6Usable) "/dual-stack" else "/IPv4")
            }
    }
}

/**
 * LinkProperties changes frequently on an otherwise unchanged Android network
 * (DHCP renewal, DNS rotation, route metric updates). Those changes need cache
 * refreshes, but closing every proxied connection is only justified when
 * traffic must move to another Network object.
 */
internal fun requiresConnectionReset(
    previous: Ipv6EnvironmentMonitor.State?,
    current: Ipv6EnvironmentMonitor.State,
): Boolean = previous == null || previous.networkHandle != current.networkHandle
