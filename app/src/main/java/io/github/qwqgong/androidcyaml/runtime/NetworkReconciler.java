package io.github.qwqgong.androidcyaml;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/** Owns underlying-network handover and delayed runtime reconciliation. */
final class NetworkReconciler {
    interface Host {
        void submit(Runnable operation);

        String start() throws IOException, InterruptedException;

        RuntimeSnapshot snapshot();

        void publish(RuntimeSnapshot snapshot);

        void publish(RuntimeState state, String detail);

        void failActiveService(String message);
    }

    private static final String TAG = "AndroidCyaml/Network";
    private static final long IPV6_REBUILD_STABILIZATION_MILLIS = 750L;
    private static final long SELECTION_RESTORE_STABILIZATION_MILLIS = 1_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Ipv6EnvironmentMonitor networkMonitor;
    private final RuntimeOverrideStore overrideStore;
    private final RuntimeLifecycle lifecycle;
    private final SelectorSession selectorSession;
    private final Host host;

    private volatile Ipv6EnvironmentMonitor.State underlyingNetworkState;
    private Runnable pendingIpv6Reconciliation;
    private Runnable pendingSelectionRestoration;
    private long ipv6ReconciliationGeneration;
    private long selectionRestorationGeneration;

    NetworkReconciler(
            Ipv6EnvironmentMonitor networkMonitor,
            RuntimeOverrideStore overrideStore,
            RuntimeLifecycle lifecycle,
            SelectorSession selectorSession,
            Host host
    ) {
        this.networkMonitor = networkMonitor;
        this.overrideStore = overrideStore;
        this.lifecycle = lifecycle;
        this.selectorSession = selectorSession;
        this.host = host;
        underlyingNetworkState = networkMonitor.currentState();
        lifecycle.setIdleEffectiveState(overrideStore.settings(), underlyingNetworkState);
    }

    Ipv6EnvironmentMonitor.State start() {
        underlyingNetworkState = networkMonitor.start(
                state -> host.submit(() -> onUnderlyingNetworkChanged(state))
        );
        return underlyingNetworkState;
    }

    Ipv6EnvironmentMonitor.State currentState() {
        // Read straight from the (already thread-safe) monitor rather than the
        // locally cached field below: the cache only updates once the queued
        // onUnderlyingNetworkChanged() reconciliation runs on the coordinator
        // executor, which can be delayed behind a long-running start()/restart().
        // A start() that races ahead of that queued reconciliation must still see
        // the freshest known network, not a stale one left over from before the
        // switch.
        return networkMonitor.currentState();
    }

    Ipv6EnvironmentMonitor.State refreshState() {
        underlyingNetworkState = networkMonitor.currentState();
        return underlyingNetworkState;
    }

    void cancelSelectionRestoration() {
        selectionRestorationGeneration++;
        if (pendingSelectionRestoration != null) {
            mainHandler.removeCallbacks(pendingSelectionRestoration);
            pendingSelectionRestoration = null;
        }
    }

    void stop() {
        cancelIpv6Reconciliation();
        cancelSelectionRestoration();
        networkMonitor.stop();
    }

    private void onUnderlyingNetworkChanged(Ipv6EnvironmentMonitor.State state) {
        Ipv6EnvironmentMonitor.State previous = underlyingNetworkState;
        if (state.equals(previous)) {
            return;
        }
        boolean selectionIdentityChanged = selectorSession.moveTo(state, lifecycle.runtime());
        if (selectionIdentityChanged) {
            cancelSelectionRestoration();
        }
        underlyingNetworkState = state;
        lifecycle.updateUnderlyingNetwork(state.networkHandle());
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean targetTcpConcurrent = settings.adaptiveTcpConcurrent() && state.wifi();
        if (!lifecycle.hasActiveService()) {
            lifecycle.setIdleEffectiveState(settings, state);
            host.publish(host.snapshot());
            return;
        }

        applyTcpConcurrentForNetwork(targetTcpConcurrent);
        refreshRuntimeForNetworkChange(previous, state, state.pathChangedFrom(previous));
        if (selectionIdentityChanged && state.available()) {
            scheduleSelectionRestoration();
        }
        if (!state.available()) {
            cancelIpv6Reconciliation();
            host.publish(host.snapshot());
            return;
        }

        boolean targetIpv6 = settings.ipv6Enabled() && state.ipv6Usable();
        if (targetIpv6 == lifecycle.effectiveIpv6Enabled()) {
            cancelIpv6Reconciliation();
            host.publish(host.snapshot());
            return;
        }

        scheduleIpv6Reconciliation();
        host.publish(host.snapshot());
    }

    private void applyTcpConcurrentForNetwork(boolean targetEnabled) {
        if (targetEnabled == lifecycle.effectiveTcpConcurrent()) {
            return;
        }
        try {
            if (!lifecycle.applyTcpConcurrent(targetEnabled)) {
                return;
            }
            Log.i(
                    TAG,
                    targetEnabled
                            ? "Enabled tcp-concurrent for Wi-Fi"
                            : "Disabled tcp-concurrent outside Wi-Fi"
            );
        } catch (IOException exception) {
            // A best-effort dialing optimization must not tear down the VPN.
            Log.w(TAG, "Unable to update tcp-concurrent after network change", exception);
        }
    }

    private void scheduleIpv6Reconciliation() {
        cancelIpv6Reconciliation();
        long generation = ipv6ReconciliationGeneration;
        pendingIpv6Reconciliation = () -> host.submit(
                () -> reconcileIpv6State(generation)
        );
        mainHandler.postDelayed(
                pendingIpv6Reconciliation,
                IPV6_REBUILD_STABILIZATION_MILLIS
        );
    }

    private void cancelIpv6Reconciliation() {
        ipv6ReconciliationGeneration++;
        if (pendingIpv6Reconciliation != null) {
            mainHandler.removeCallbacks(pendingIpv6Reconciliation);
            pendingIpv6Reconciliation = null;
        }
    }

    private void reconcileIpv6State(long generation) {
        if (generation != ipv6ReconciliationGeneration) {
            return;
        }
        pendingIpv6Reconciliation = null;
        Ipv6EnvironmentMonitor.State state = underlyingNetworkState;
        if (!lifecycle.hasActiveService() || state == null || !state.available()) {
            return;
        }
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean targetIpv6 = settings.ipv6Enabled() && state.ipv6Usable();
        if (targetIpv6 == lifecycle.effectiveIpv6Enabled()) {
            return;
        }

        host.publish(
                RuntimeState.STARTING,
                targetIpv6
                        ? "IPv6 环境已恢复，正在重建 JNI TUN…"
                        : "当前 IPv6 环境不可用，正在重建为 IPv4-only…"
        );
        try {
            String detail = host.start();
            host.publish(RuntimeState.RUNNING, detail);
        } catch (Exception exception) {
            host.failActiveService("IPv6 环境切换失败：" + Exceptions.usefulMessage(exception));
        }
    }

    private void scheduleSelectionRestoration() {
        cancelSelectionRestoration();
        long generation = selectionRestorationGeneration;
        pendingSelectionRestoration = () -> host.submit(
                () -> restoreSelection(generation)
        );
        mainHandler.postDelayed(
                pendingSelectionRestoration,
                SELECTION_RESTORE_STABILIZATION_MILLIS
        );
    }

    private void restoreSelection(long generation) {
        if (generation != selectionRestorationGeneration) {
            return;
        }
        pendingSelectionRestoration = null;
        selectorSession.restoreOrRemember(lifecycle.runtime());
    }

    private void refreshRuntimeForNetworkChange(
            Ipv6EnvironmentMonitor.State previous,
            Ipv6EnvironmentMonitor.State currentState,
            boolean pathChanged
    ) {
        MihomoRuntime currentRuntime = lifecycle.runtime();
        if (currentRuntime == null || !pathChanged) {
            return;
        }
        try {
            currentRuntime.onUnderlyingNetworkChanged(currentState.dnsServers());
            Log.i(
                    TAG,
                    "Refreshed mihomo after underlying network change: "
                            + networkDescription(previous) + " -> "
                            + networkDescription(currentState)
            );
        } catch (IOException exception) {
            // A transient handover failure must not tear down the VPN.
            Log.w(TAG, "Unable to refresh mihomo after network handover", exception);
        }
    }

    private static String networkDescription(Ipv6EnvironmentMonitor.State state) {
        return state == null || !state.available()
                ? "unavailable"
                : state.networkHandle()
                        + (state.wifi() ? "/Wi-Fi" : "/non-Wi-Fi")
                        + (state.ipv6Usable() ? "/dual-stack" : "/IPv4");
    }
}
