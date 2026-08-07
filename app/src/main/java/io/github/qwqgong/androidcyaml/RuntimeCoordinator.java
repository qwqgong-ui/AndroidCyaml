package io.github.qwqgong.androidcyaml;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes every transition that can replace the TUN or embedded mihomo runtime. */
final class RuntimeCoordinator {
    interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private static final String TAG = "AndroidCyaml/Coordinator";
    private static final int ANDROID_17_API = 37;
    private static final long IPV6_REBUILD_STABILIZATION_MILLIS = 750L;
    private static volatile RuntimeCoordinator instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable ipv6Reconciliation =
            () -> executor.execute(this::reconcileIpv6State);
    private final RuntimeStateBus stateBus = new RuntimeStateBus();
    private final MihomoFileStore fileStore;
    private final ConfigInstaller configInstaller;
    private final RuntimeOverrideStore overrideStore;
    private final Ipv6EnvironmentMonitor networkMonitor;

    private AndroidVpnService service;
    private AndroidTunManager tunManager;
    private NativePlatformCallbacks platformCallbacks;
    private volatile MihomoRuntime runtime;
    private volatile Ipv6EnvironmentMonitor.State underlyingNetworkState;
    private volatile boolean effectiveIpv6Enabled;
    private volatile boolean effectiveTcpConcurrent;

    private RuntimeCoordinator(Context context) {
        this.context = context.getApplicationContext();
        fileStore = new MihomoFileStore(this.context);
        configInstaller = new ConfigInstaller(this.context, fileStore);
        overrideStore = new RuntimeOverrideStore(this.context);
        networkMonitor = new Ipv6EnvironmentMonitor(this.context);
        RuntimeOverrideSettings settings = overrideStore.settings();
        underlyingNetworkState = networkMonitor.currentState();
        effectiveIpv6Enabled =
                settings.ipv6Enabled() && underlyingNetworkState.ipv6Usable();
        effectiveTcpConcurrent =
                settings.adaptiveTcpConcurrent() && underlyingNetworkState.wifi();
    }

    static RuntimeCoordinator getInstance(Context context) {
        RuntimeCoordinator local = instance;
        if (local == null) {
            synchronized (RuntimeCoordinator.class) {
                local = instance;
                if (local == null) {
                    local = new RuntimeCoordinator(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    static int trimMemoryCachesIfCreated() {
        RuntimeCoordinator local = instance;
        MihomoRuntime current = local == null ? null : local.runtime;
        // Do not initialize the large native core merely because Android asks
        // an otherwise idle service process to trim memory.
        return current == null ? 0 : current.trimRebuildableCaches();
    }

    static boolean persistStateForMemoryKill() {
        return true;
    }

    RuntimeOverrideSettings runtimeOverrideSettings() {
        return overrideStore.settings();
    }

    boolean effectiveIpv6Enabled() {
        return effectiveIpv6Enabled;
    }

    void addListener(RuntimeStateBus.Listener listener) {
        stateBus.addListener(listener);
    }

    void removeListener(RuntimeStateBus.Listener listener) {
        stateBus.removeListener(listener);
    }

    void start(AndroidVpnService requestedService) {
        executor.execute(() -> startInternal(requestedService));
    }

    void stop(AndroidVpnService requestedService, Runnable completion) {
        executor.execute(() -> {
            if (service != null && requestedService != null && service != requestedService) {
                postCompletion(completion);
                return;
            }
            if (stateBus.snapshot().state() != RuntimeState.STOPPED) {
                publish(RuntimeState.STOPPING, "正在停止 mihomo JNI TUN…");
            }
            cleanupAll();
            service = null;
            publish(RuntimeState.STOPPED, "VPN 未连接");
            postCompletion(completion);
        });
    }

    void restart(OperationCallback callback) {
        executor.execute(() -> {
            if (!hasActiveService()) {
                postOperation(callback, false, "VPN 未启动");
                return;
            }
            try {
                publish(RuntimeState.STARTING, "正在重启 mihomo JNI 核心…");
                String detail = startRuntimeOnExistingService();
                publish(RuntimeState.RUNNING, detail);
                postOperation(callback, true, "mihomo 已重启");
            } catch (Exception exception) {
                String message = usefulMessage(exception);
                failActiveService("mihomo 重启失败：" + message);
                postOperation(callback, false, message);
            }
        });
    }

    void importConfig(Uri source, OperationCallback callback) {
        executor.execute(() -> importConfigInternal(source, callback));
    }

    void setRuntimeOverrides(
            String stackValue,
            boolean processMatching,
            boolean ipv6Enabled,
            String logLevelValue,
            boolean adaptiveTcpConcurrent,
            boolean webViewXhttp,
            boolean lanWebUiPublic,
            OperationCallback callback
    ) {
        final TunStackMode stack;
        final RuntimeLogLevel logLevel;
        try {
            stack = TunStackMode.fromWireValue(stackValue);
            logLevel = RuntimeLogLevel.fromWireValue(logLevelValue);
        } catch (IllegalArgumentException exception) {
            postOperation(callback, false, usefulMessage(exception));
            return;
        }
        executor.execute(() -> setRuntimeOverridesInternal(
                new RuntimeOverrideSettings(
                        stack,
                        processMatching,
                        ipv6Enabled,
                        logLevel,
                        adaptiveTcpConcurrent,
                        webViewXhttp,
                        lanWebUiPublic
                ),
                callback
        ));
    }

    private void startInternal(AndroidVpnService requestedService) {
        if (requestedService == null) {
            return;
        }
        RuntimeState current = stateBus.snapshot().state();
        if (service == requestedService
                && (current == RuntimeState.STARTING || current == RuntimeState.RUNNING)) {
            stateBus.publish(stateBus.snapshot());
            return;
        }

        cleanupAll();
        service = requestedService;
        tunManager = new AndroidTunManager(requestedService);
        platformCallbacks = new NativePlatformCallbacks(requestedService);
        try {
            underlyingNetworkState = networkMonitor.start(
                    state -> executor.execute(() -> onUnderlyingNetworkChanged(state))
            );
            publish(RuntimeState.STARTING, "正在启动同进程 mihomo JNI TUN…");
            String detail = startRuntimeOnExistingService();
            publish(RuntimeState.RUNNING, detail);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failActiveService("VPN 启动失败：" + usefulMessage(exception));
        }
    }

    private String startRuntimeOnExistingService() throws IOException, InterruptedException {
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean localNetworkFallback = settings.lanWebUiPublic()
                && !hasLocalNetworkAccess();
        if (localNetworkFallback) {
            settings = new RuntimeOverrideSettings(
                    settings.tunStack(),
                    settings.processMatching(),
                    settings.ipv6Enabled(),
                    settings.logLevel(),
                    settings.adaptiveTcpConcurrent(),
                    settings.webViewXhttp(),
                    false
            );
            overrideStore.setSettings(settings);
        }
        boolean requestedIpv6 =
                settings.ipv6Enabled() && underlyingNetworkState.ipv6Usable();
        boolean requestedTcpConcurrent =
                settings.adaptiveTcpConcurrent() && underlyingNetworkState.wifi();
        String localNetworkDetail = localNetworkFallback
                ? " · 局域网权限未授权，WebUI 已恢复本机"
                : "";
        try {
            return startRuntime(settings, requestedIpv6, requestedTcpConcurrent)
                    + localNetworkDetail;
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
                return startRuntime(settings, false, requestedTcpConcurrent)
                        + " · IPv6 自动关闭"
                        + localNetworkDetail;
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

    private String startRuntime(
            RuntimeOverrideSettings settings,
            boolean ipv6Enabled,
            boolean tcpConcurrentEnabled
    ) throws IOException, InterruptedException {
        if (!hasActiveService()) {
            throw new IOException("Android VPN 服务尚未初始化");
        }
        closeRuntime();
        MihomoRuntime candidate = new MihomoRuntime(
                fileStore,
                tunManager,
                platformCallbacks,
                settings,
                ipv6Enabled,
                tcpConcurrentEnabled
        );
        runtime = candidate;
        try {
            String runningDetail = candidate.start();
            if (!tunManager.hasUsableTunnel()) {
                throw new IOException("mihomo 未建立 Android TUN");
            }
            effectiveIpv6Enabled = ipv6Enabled;
            effectiveTcpConcurrent = tcpConcurrentEnabled;
            return runningDetail;
        } catch (IOException | InterruptedException exception) {
            candidate.close();
            runtime = null;
            throw exception;
        }
    }

    private void importConfigInternal(Uri source, OperationCallback callback) {
        try (ConfigInstaller.Transaction transaction = configInstaller.install(source)) {
            if (hasActiveService()) {
                publish(RuntimeState.STARTING, "配置已校验，正在重启 mihomo JNI TUN…");
                try {
                    String detail = startRuntimeOnExistingService();
                    publish(RuntimeState.RUNNING, detail);
                } catch (Exception startFailure) {
                    transaction.rollback();
                    String restoredDetail = startRuntimeOnExistingService();
                    publish(RuntimeState.RUNNING, restoredDetail);
                    throw new IOException(
                            "新配置无法启动，已恢复上一份配置：" + usefulMessage(startFailure),
                            startFailure
                    );
                }
            }
            transaction.commit();
            postOperation(callback, true, "配置已通过嵌入式 mihomo 校验并安装");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Unable to import config", exception);
            if (stateBus.snapshot().state() == RuntimeState.STARTING) {
                failActiveService("配置应用失败：" + usefulMessage(exception));
            }
            postOperation(callback, false, usefulMessage(exception));
        }
    }

    private void setRuntimeOverridesInternal(
            RuntimeOverrideSettings requested,
            OperationCallback callback
    ) {
        if (requested.lanWebUiPublic() && !hasLocalNetworkAccess()) {
            postOperation(callback, false, "未授予 Android 17 局域网访问权限");
            return;
        }
        RuntimeOverrideSettings previous = overrideStore.settings();
        if (requested.equals(previous)) {
            stateBus.publish(stateBus.snapshot());
            postOperation(callback, true, "覆写未变更");
            return;
        }

        try {
            overrideStore.setSettings(requested);
        } catch (RuntimeException exception) {
            postOperation(callback, false, usefulMessage(exception));
            return;
        }

        underlyingNetworkState = networkMonitor.currentState();
        if (!hasActiveService()) {
            effectiveIpv6Enabled =
                    requested.ipv6Enabled() && underlyingNetworkState.ipv6Usable();
            effectiveTcpConcurrent =
                    requested.adaptiveTcpConcurrent() && underlyingNetworkState.wifi();
            stateBus.publish(stateBus.snapshot());
            postOperation(
                    callback,
                    true,
                    describeOverrides(
                            requested,
                            effectiveIpv6Enabled,
                            effectiveTcpConcurrent,
                            0
                    )
            );
            return;
        }

        publish(RuntimeState.STARTING, "正在应用运行时覆写并重建 JNI TUN…");
        try {
            String detail = startRuntimeOnExistingService();
            publish(RuntimeState.RUNNING, detail);
            MihomoRuntime current = runtime;
            RuntimeOverrideSettings applied = overrideStore.settings();
            postOperation(
                    callback,
                    true,
                    describeOverrides(
                            applied,
                            effectiveIpv6Enabled,
                            effectiveTcpConcurrent,
                            current == null ? 0 : current.controllerPort()
                    )
            );
        } catch (Exception applyFailure) {
            try {
                overrideStore.setSettings(previous);
                underlyingNetworkState = networkMonitor.currentState();
                publish(RuntimeState.STARTING, "覆写应用失败，正在恢复上一状态…");
                String detail = startRuntimeOnExistingService();
                publish(RuntimeState.RUNNING, detail);
                postOperation(
                        callback,
                        false,
                        "无法应用运行时覆写，已恢复上一状态：" + usefulMessage(applyFailure)
                );
            } catch (Exception restoreFailure) {
                String message = "运行时覆写失败且无法恢复："
                        + usefulMessage(applyFailure)
                        + "；"
                        + usefulMessage(restoreFailure);
                failActiveService(message);
                postOperation(callback, false, message);
            }
        }
    }

    private void onUnderlyingNetworkChanged(Ipv6EnvironmentMonitor.State state) {
        Ipv6EnvironmentMonitor.State previous = underlyingNetworkState;
        if (state.equals(previous)) {
            return;
        }
        underlyingNetworkState = state;
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean targetTcpConcurrent =
                settings.adaptiveTcpConcurrent() && state.wifi();
        if (!hasActiveService()) {
            effectiveIpv6Enabled = settings.ipv6Enabled() && state.ipv6Usable();
            effectiveTcpConcurrent = targetTcpConcurrent;
            stateBus.publish(stateBus.snapshot());
            return;
        }

        applyTcpConcurrentForNetwork(targetTcpConcurrent);
        boolean pathChanged = state.pathChangedFrom(previous);
        refreshRuntimeForNetworkChange(previous, state, pathChanged);
        if (!state.available()) {
            mainHandler.removeCallbacks(ipv6Reconciliation);
            stateBus.publish(stateBus.snapshot());
            return;
        }

        boolean targetIpv6 = settings.ipv6Enabled() && state.ipv6Usable();
        if (targetIpv6 == effectiveIpv6Enabled) {
            mainHandler.removeCallbacks(ipv6Reconciliation);
            stateBus.publish(stateBus.snapshot());
            return;
        }

        mainHandler.removeCallbacks(ipv6Reconciliation);
        mainHandler.postDelayed(
                ipv6Reconciliation,
                IPV6_REBUILD_STABILIZATION_MILLIS
        );
        stateBus.publish(stateBus.snapshot());
    }

    private void applyTcpConcurrentForNetwork(boolean targetEnabled) {
        if (targetEnabled == effectiveTcpConcurrent) {
            return;
        }
        MihomoRuntime current = runtime;
        if (current == null) {
            return;
        }
        try {
            current.setTcpConcurrent(targetEnabled);
            effectiveTcpConcurrent = targetEnabled;
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

    private void reconcileIpv6State() {
        Ipv6EnvironmentMonitor.State state = underlyingNetworkState;
        if (!hasActiveService() || state == null || !state.available()) {
            return;
        }
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean targetIpv6 = settings.ipv6Enabled() && state.ipv6Usable();
        if (targetIpv6 == effectiveIpv6Enabled) {
            return;
        }

        publish(
                RuntimeState.STARTING,
                targetIpv6
                        ? "IPv6 环境已恢复，正在重建 JNI TUN…"
                        : "当前 IPv6 环境不可用，正在重建为 IPv4-only…"
        );
        try {
            String detail = startRuntimeOnExistingService();
            publish(RuntimeState.RUNNING, detail);
        } catch (Exception exception) {
            failActiveService("IPv6 环境切换失败：" + usefulMessage(exception));
        }
    }

    private void refreshRuntimeForNetworkChange(
            Ipv6EnvironmentMonitor.State previous,
            Ipv6EnvironmentMonitor.State currentState,
            boolean pathChanged
    ) {
        MihomoRuntime currentRuntime = runtime;
        if (currentRuntime == null || !pathChanged) {
            return;
        }
        try {
            currentRuntime.onUnderlyingNetworkChanged();
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

    private boolean hasActiveService() {
        return service != null && tunManager != null && platformCallbacks != null;
    }

    private boolean hasLocalNetworkAccess() {
        return Build.VERSION.SDK_INT < ANDROID_17_API
                || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void failActiveService(String message) {
        AndroidVpnService failedService = service;
        cleanupAll();
        service = null;
        publish(RuntimeState.FAILED, message);
        if (failedService != null) {
            mainHandler.post(() -> failedService.onCoordinatorFailure(message));
        }
    }

    private void cleanupAll() {
        mainHandler.removeCallbacks(ipv6Reconciliation);
        networkMonitor.stop();
        closeRuntime();
        if (tunManager != null) {
            tunManager.close();
            tunManager = null;
        }
        if (platformCallbacks != null) {
            platformCallbacks.close();
            platformCallbacks = null;
        }
        effectiveIpv6Enabled = false;
        effectiveTcpConcurrent = false;
    }

    private void closeRuntime() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    private void publish(RuntimeState state, String detail) {
        MihomoRuntime current = runtime;
        boolean running = state == RuntimeState.RUNNING && current != null;
        stateBus.publish(new RuntimeSnapshot(
                state,
                detail == null ? "" : detail,
                running ? current.dashboardUrl() : "",
                running ? current.controllerPort() : 0
        ));
    }

    private void postOperation(OperationCallback callback, boolean success, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onComplete(success, message == null ? "" : message));
        }
    }

    private void postCompletion(Runnable completion) {
        if (completion != null) {
            mainHandler.post(completion);
        }
    }

    private static String describeOverrides(
            RuntimeOverrideSettings settings,
            boolean ipv6Effective,
            boolean tcpConcurrentEffective,
            int controllerPort
    ) {
        String stack = switch (settings.tunStack()) {
            case SYSTEM -> "system 全栈";
            case GVISOR -> "gVisor 全栈";
            case MIXED -> "mixed（TCP system / UDP gVisor）";
        };
        String process = settings.processMatching() ? "进程匹配已开启" : "进程匹配已关闭";
        String ipv6;
        if (!settings.ipv6Enabled()) {
            ipv6 = "IPv6 已关闭";
        } else if (ipv6Effective) {
            ipv6 = "IPv6 已开启";
        } else {
            ipv6 = "IPv6 已开启，但当前环境不可用，已自动使用 IPv4";
        }
        String tcpConcurrent;
        if (!settings.adaptiveTcpConcurrent()) {
            tcpConcurrent = "TCP 并发已全局关闭";
        } else if (tcpConcurrentEffective) {
            tcpConcurrent = "TCP 并发已在 Wi-Fi 开启";
        } else {
            tcpConcurrent = "TCP 并发已在当前非 Wi-Fi 网络关闭";
        }
        String xhttp = settings.webViewXhttp()
                ? "XHTTP WebView 已开启"
                : "XHTTP 使用 mihomo 原生栈";
        String logLevel = "日志 " + settings.logLevel().wireValue();
        String webUi = settings.lanWebUiPublic()
                ? "WebUI 监听 0.0.0.0"
                : "WebUI 监听 127.0.0.1";
        if (controllerPort > 0) {
            webUi += ":" + controllerPort;
        }
        return stack + "；" + process + "；" + ipv6 + "；" + tcpConcurrent
                + "；" + xhttp + "；" + logLevel + "；" + webUi;
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
