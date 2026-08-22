package io.github.qwqgong.androidcyaml;

import android.util.Log;

import java.io.IOException;

/** Owns the Android VPN, TUN, and embedded mihomo runtime resources. */
final class RuntimeLifecycle {
    private static final String TAG = "AndroidCyaml/Coordinator";

    private final MihomoFileStore fileStore;
    private final SelectorSession selectorSession;

    private AndroidVpnService service;
    private AndroidTunManager tunManager;
    private NativePlatformCallbacks platformCallbacks;
    private volatile MihomoRuntime runtime;
    private volatile boolean effectiveIpv6Enabled;
    private volatile boolean effectiveTcpConcurrent;

    RuntimeLifecycle(MihomoFileStore fileStore, SelectorSession selectorSession) {
        this.fileStore = fileStore;
        this.selectorSession = selectorSession;
    }

    AndroidVpnService service() {
        return service;
    }

    MihomoRuntime runtime() {
        return runtime;
    }

    boolean effectiveIpv6Enabled() {
        return effectiveIpv6Enabled;
    }

    boolean effectiveTcpConcurrent() {
        return effectiveTcpConcurrent;
    }

    boolean ownsService(AndroidVpnService requestedService) {
        return service == requestedService;
    }

    boolean hasActiveService() {
        return service != null && tunManager != null && platformCallbacks != null;
    }

    String start(
            AndroidVpnService requestedService,
            RuntimeOverrideSettings settings,
            Ipv6EnvironmentMonitor.State networkState,
            Runnable runtimeStarted
    ) throws IOException, InterruptedException {
        stop();
        service = requestedService;
        tunManager = new AndroidTunManager(requestedService);
        platformCallbacks = new NativePlatformCallbacks(requestedService);
        platformCallbacks.updateUnderlyingNetwork(networkState.networkHandle());
        return restart(settings, networkState, runtimeStarted);
    }

    String restart(
            RuntimeOverrideSettings settings,
            Ipv6EnvironmentMonitor.State networkState,
            Runnable runtimeStarted
    ) throws IOException, InterruptedException {
        boolean requestedIpv6 = settings.ipv6Enabled() && networkState.ipv6Usable();
        boolean requestedTcpConcurrent =
                settings.adaptiveTcpConcurrent() && networkState.wifi();
        try {
            return startRuntime(
                    settings,
                    networkState,
                    requestedIpv6,
                    requestedTcpConcurrent,
                    runtimeStarted
            );
        } catch (IOException | InterruptedException firstFailure) {
            if (!requestedIpv6) {
                throw firstFailure;
            }
            if (firstFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw firstFailure;
            }
            Log.w(TAG, "IPv6 runtime startup failed; retrying with IPv6 disabled", firstFailure);
            try {
                return startRuntime(
                        settings,
                        networkState,
                        false,
                        requestedTcpConcurrent,
                        runtimeStarted
                ) + " · IPv6 自动关闭";
            } catch (IOException | InterruptedException fallbackFailure) {
                if (fallbackFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException(
                        "IPv6 模式启动失败：" + usefulMessage(firstFailure)
                                + "；IPv4 回退也失败：" + usefulMessage(fallbackFailure),
                        fallbackFailure
                );
            }
        }
    }

    void updateUnderlyingNetwork(long networkHandle) {
        if (platformCallbacks != null) {
            platformCallbacks.updateUnderlyingNetwork(networkHandle);
        }
    }

    boolean applyTcpConcurrent(boolean enabled) throws IOException {
        if (runtime == null || enabled == effectiveTcpConcurrent) {
            return false;
        }
        runtime.setTcpConcurrent(enabled);
        effectiveTcpConcurrent = enabled;
        return true;
    }

    void setIdleEffectiveState(
            RuntimeOverrideSettings settings,
            Ipv6EnvironmentMonitor.State networkState
    ) {
        effectiveIpv6Enabled = settings.ipv6Enabled() && networkState.ipv6Usable();
        effectiveTcpConcurrent =
                settings.adaptiveTcpConcurrent() && networkState.wifi();
    }

    void stop() {
        // Stopping the VPN is also leaving the current selector session. Without
        // this checkpoint, starting again on the same network restores a stale
        // choice because no physical-network handover occurred.
        selectorSession.checkpoint(runtime);
        closeRuntime();
        if (tunManager != null) {
            tunManager.close();
            tunManager = null;
        }
        if (platformCallbacks != null) {
            platformCallbacks.close();
            platformCallbacks = null;
        }
        service = null;
        effectiveIpv6Enabled = false;
        effectiveTcpConcurrent = false;
    }

    private String startRuntime(
            RuntimeOverrideSettings settings,
            Ipv6EnvironmentMonitor.State networkState,
            boolean ipv6Enabled,
            boolean tcpConcurrentEnabled,
            Runnable runtimeStarted
    ) throws IOException, InterruptedException {
        if (!hasActiveService()) {
            throw new IOException("Android VPN 服务尚未初始化");
        }
        // A restart must not roll a selector back to the snapshot taken when the
        // current network was first entered. Capture the user's latest WebUI
        // choice while the old controller is still reachable.
        selectorSession.checkpoint(runtime);
        closeRuntime();
        MihomoRuntime candidate = new MihomoRuntime(
                fileStore,
                tunManager,
                platformCallbacks,
                settings,
                ipv6Enabled,
                tcpConcurrentEnabled,
                networkState.dnsServers()
        );
        runtime = candidate;
        try {
            String runningDetail = candidate.start();
            if (!tunManager.hasUsableTunnel()) {
                throw new IOException("mihomo 未建立 Android TUN");
            }
            effectiveIpv6Enabled = ipv6Enabled;
            effectiveTcpConcurrent = tcpConcurrentEnabled;
            if (runtimeStarted != null) {
                runtimeStarted.run();
            }
            selectorSession.begin(candidate, networkState);
            return runningDetail;
        } catch (IOException | InterruptedException exception) {
            candidate.close();
            runtime = null;
            throw exception;
        }
    }

    private void closeRuntime() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        selectorSession.reset();
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
