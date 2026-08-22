package io.github.qwqgong.androidcyaml;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Serializes every transition that can replace the TUN or embedded mihomo runtime. */
final class RuntimeCoordinator implements
        RuntimeConfigTransactions.Lifecycle,
        RuntimeConfigTransactions.Host,
        NetworkReconciler.Host {
    interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private record RuntimeStartRequest(
            RuntimeOverrideSettings settings,
            String detailSuffix
    ) {
    }

    private static final String TAG = "AndroidCyaml/Coordinator";
    private static final int ANDROID_17_API = 37;
    private static volatile RuntimeCoordinator instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RuntimeStateBus stateBus = new RuntimeStateBus();
    private final MihomoFileStore fileStore;
    private final ConfigInstaller configInstaller;
    private final RuntimeOverrideStore overrideStore;
    private final RuntimeConfigTransactions configTransactions;
    private final SelectorSession selectorSession;
    private final RuntimeLifecycle lifecycle;
    private final NetworkReconciler networkReconciler;

    private RuntimeCoordinator(Context context) {
        this.context = context.getApplicationContext();
        fileStore = new MihomoFileStore(this.context);
        configInstaller = new ConfigInstaller(this.context, fileStore);
        overrideStore = new RuntimeOverrideStore(this.context);
        Ipv6EnvironmentMonitor networkMonitor = new Ipv6EnvironmentMonitor(this.context);
        selectorSession = new SelectorSession(
                new NetworkSelectionStore(this.context),
                networkMonitor
        );
        lifecycle = new RuntimeLifecycle(fileStore, selectorSession);
        networkReconciler = new NetworkReconciler(
                networkMonitor,
                overrideStore,
                lifecycle,
                selectorSession,
                this
        );
        configTransactions = new RuntimeConfigTransactions(
                configInstaller,
                overrideStore,
                this,
                this
        );
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
        MihomoRuntime current = local == null ? null : local.lifecycle.runtime();
        // Do not initialize the large native core merely because Android asks
        // an otherwise idle service process to trim memory.
        return current == null ? 0 : current.trimRebuildableCaches();
    }

    static boolean persistStateForMemoryKill() {
        RuntimeCoordinator local = instance;
        if (local == null || local.lifecycle.runtime() == null) {
            return true;
        }
        Future<Boolean> checkpoint = local.executor.submit(
                () -> local.selectorSession.checkpoint(local.lifecycle.runtime())
        );
        try {
            return checkpoint.get(2L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            Log.w(TAG, "Unable to persist selector choices before memory kill", exception);
            return false;
        }
    }

    RuntimeOverrideSettings runtimeOverrideSettings() {
        return overrideStore.settings();
    }

    @Override
    public boolean effectiveIpv6Enabled() {
        return lifecycle.effectiveIpv6Enabled();
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
            if (lifecycle.service() != null
                    && requestedService != null
                    && !lifecycle.ownsService(requestedService)) {
                postCompletion(completion);
                return;
            }
            if (stateBus.snapshot().state() != RuntimeState.STOPPED) {
                publish(RuntimeState.STOPPING, "正在停止 mihomo JNI TUN…");
            }
            cleanupAll();
            publish(RuntimeState.STOPPED, "VPN 未连接");
            postCompletion(completion);
        });
    }

    void restart(OperationCallback callback) {
        executor.execute(() -> {
            if (!active()) {
                complete(callback, false, "VPN 未启动");
                return;
            }
            try {
                publish(RuntimeState.STARTING, "正在重启 mihomo JNI 核心…");
                String detail = start();
                publish(RuntimeState.RUNNING, detail);
                complete(callback, true, "mihomo 已重启");
            } catch (Exception exception) {
                String message = Exceptions.usefulMessage(exception);
                failActiveService("mihomo 重启失败：" + message);
                complete(callback, false, message);
            }
        });
    }

    void importConfig(Uri source, OperationCallback callback) {
        executor.execute(() -> configTransactions.importConfig(source, callback));
    }

    void setRuntimeOverrides(
            String processMatchingModeValue,
            boolean ipv6Enabled,
            String logLevelValue,
            boolean adaptiveTcpConcurrent,
            boolean webViewXhttp,
            boolean lanWebUiPublic,
            OperationCallback callback
    ) {
        final RuntimeLogLevel logLevel;
        final ProcessMatchingMode processMatchingMode;
        try {
            logLevel = RuntimeLogLevel.fromWireValue(logLevelValue);
            processMatchingMode = ProcessMatchingMode.fromWireValue(processMatchingModeValue);
        } catch (IllegalArgumentException exception) {
            complete(callback, false, Exceptions.usefulMessage(exception));
            return;
        }
        executor.execute(() -> configTransactions.setRuntimeOverrides(
                new RuntimeOverrideSettings(
                        processMatchingMode,
                        ipv6Enabled,
                        logLevel,
                        adaptiveTcpConcurrent,
                        webViewXhttp,
                        lanWebUiPublic
                ),
                callback
        ));
    }

    void networkSelectionCatalog(OperationCallback callback) {
        executor.execute(() -> {
            MihomoRuntime current = lifecycle.runtime();
            if (current == null) {
                complete(callback, false, "请先启动 VPN，再设置网络节点");
                return;
            }
            try {
                complete(callback, true, selectorSession.catalog(current));
            } catch (IOException | JSONException exception) {
                complete(callback, false, Exceptions.usefulMessage(exception));
            }
        });
    }

    void setNetworkSelection(
            String identity,
            String group,
            String target,
            OperationCallback callback
    ) {
        executor.execute(() -> {
            MihomoRuntime current = lifecycle.runtime();
            if (current == null) {
                complete(callback, false, "请先启动 VPN，再设置网络节点");
                return;
            }
            try {
                complete(callback, true, selectorSession.select(
                        current,
                        identity,
                        group,
                        target
                ));
            } catch (IOException exception) {
                complete(callback, false, Exceptions.usefulMessage(exception));
            }
        });
    }

    private void startInternal(AndroidVpnService requestedService) {
        if (requestedService == null) {
            return;
        }
        RuntimeState current = stateBus.snapshot().state();
        if (lifecycle.ownsService(requestedService)
                && (current == RuntimeState.STARTING || current == RuntimeState.RUNNING)) {
            stateBus.publish(stateBus.snapshot());
            return;
        }

        cleanupAll();
        try {
            Ipv6EnvironmentMonitor.State underlyingNetworkState = networkReconciler.start();
            publish(RuntimeState.STARTING, "正在启动同进程 mihomo JNI TUN…");
            RuntimeStartRequest request = runtimeStartRequest();
            String detail = lifecycle.start(
                    requestedService,
                    request.settings(),
                    underlyingNetworkState,
                    networkReconciler::cancelSelectionRestoration
            ) + request.detailSuffix();
            publish(RuntimeState.RUNNING, detail);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failActiveService("VPN 启动失败：" + Exceptions.usefulMessage(exception));
        }
    }

    @Override
    public String start() throws IOException, InterruptedException {
        RuntimeStartRequest request = runtimeStartRequest();
        return lifecycle.restart(
                request.settings(),
                networkReconciler.currentState(),
                networkReconciler::cancelSelectionRestoration
        ) + request.detailSuffix();
    }

    private RuntimeStartRequest runtimeStartRequest() {
        RuntimeOverrideSettings settings = overrideStore.settings();
        boolean localNetworkFallback = settings.lanWebUiPublic()
                && !hasLocalNetworkAccess();
        if (localNetworkFallback) {
            settings = new RuntimeOverrideSettings(
                    settings.processMatchingMode(),
                    settings.ipv6Enabled(),
                    settings.logLevel(),
                    settings.adaptiveTcpConcurrent(),
                    settings.webViewXhttp(),
                    false
            );
            overrideStore.setSettings(settings);
        }
        return new RuntimeStartRequest(
                settings,
                localNetworkFallback
                        ? " · 局域网权限未授权，WebUI 已恢复本机"
                        : ""
        );
    }

    @Override
    public boolean active() {
        return lifecycle.hasActiveService();
    }

    @Override
    public void setIdleEffectiveState(
            RuntimeOverrideSettings settings,
            Ipv6EnvironmentMonitor.State networkState
    ) {
        lifecycle.setIdleEffectiveState(settings, networkState);
    }

    @Override
    public boolean effectiveTcpConcurrent() {
        return lifecycle.effectiveTcpConcurrent();
    }

    @Override
    public int controllerPort() {
        MihomoRuntime current = lifecycle.runtime();
        return current == null ? 0 : current.controllerPort();
    }

    @Override
    public boolean hasLocalNetworkAccess() {
        return Build.VERSION.SDK_INT < ANDROID_17_API
                || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Ipv6EnvironmentMonitor.State refreshUnderlyingNetworkState() {
        return networkReconciler.refreshState();
    }

    @Override
    public void failActiveService(String message) {
        AndroidVpnService failedService = lifecycle.service();
        cleanupAll();
        publish(RuntimeState.FAILED, message);
        if (failedService != null) {
            mainHandler.post(() -> failedService.onCoordinatorFailure(message));
        }
    }

    private void cleanupAll() {
        networkReconciler.stop();
        lifecycle.stop();
    }

    @Override
    public void submit(Runnable operation) {
        executor.execute(operation);
    }

    @Override
    public RuntimeSnapshot snapshot() {
        return stateBus.snapshot();
    }

    @Override
    public void publish(RuntimeSnapshot snapshot) {
        stateBus.publish(snapshot);
    }

    @Override
    public void publish(RuntimeState state, String detail) {
        MihomoRuntime current = lifecycle.runtime();
        boolean running = state == RuntimeState.RUNNING && current != null;
        stateBus.publish(new RuntimeSnapshot(
                state,
                detail == null ? "" : detail,
                running ? current.dashboardUrl() : "",
                running ? current.controllerPort() : 0
        ));
    }

    @Override
    public void complete(OperationCallback callback, boolean success, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onComplete(success, message == null ? "" : message));
        }
    }

    private void postCompletion(Runnable completion) {
        if (completion != null) {
            mainHandler.post(completion);
        }
    }
}
