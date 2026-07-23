package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes every transition that can replace the TUN or mihomo process. */
final class RuntimeCoordinator {
    interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private static final String TAG = "AndroidCyaml/Coordinator";
    private static volatile RuntimeCoordinator instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RuntimeStateBus stateBus = new RuntimeStateBus();
    private final MihomoFileStore fileStore;
    private final ConfigInstaller configInstaller;

    private AndroidVpnService service;
    private AndroidPlatformBridge platformBridge;
    private volatile MihomoRuntime runtime;

    private RuntimeCoordinator(Context context) {
        this.context = context.getApplicationContext();
        fileStore = new MihomoFileStore(this.context);
        configInstaller = new ConfigInstaller(this.context, fileStore);
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
        return current == null ? 0 : current.trimLogCache();
    }

    static boolean persistStateForMemoryKill() {
        return true;
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
                publish(RuntimeState.STOPPING, "正在停止 mihomo TUN…");
            }
            cleanupAll();
            service = null;
            publish(RuntimeState.STOPPED, "VPN 未连接");
            postCompletion(completion);
        });
    }

    void restart(OperationCallback callback) {
        executor.execute(() -> {
            if (service == null || platformBridge == null) {
                postOperation(callback, false, "VPN 未启动");
                return;
            }
            try {
                publish(RuntimeState.STARTING, "正在重启 mihomo 内核…");
                startRuntimeOnExistingBridge();
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
        publish(RuntimeState.STARTING, "正在启动 mihomo 并申请原生 TUN…");
        try {
            platformBridge = new AndroidPlatformBridge(requestedService);
            startRuntimeOnExistingBridge();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failActiveService("VPN 启动失败：" + usefulMessage(exception));
        }
    }

    private void startRuntimeOnExistingBridge() throws IOException, InterruptedException {
        if (platformBridge == null) {
            throw new IOException("Android 平台桥未启动");
        }
        closeRuntime();
        MihomoRuntime candidate = new MihomoRuntime(
                context,
                platformBridge.coreSocketAddress(),
                fileStore,
                this::onUnexpectedExit
        );
        runtime = candidate;
        String runningDetail = candidate.start();
        if (!platformBridge.hasUsableTunnel()) {
            throw new IOException("mihomo 未建立 Android TUN");
        }
        publish(RuntimeState.RUNNING, runningDetail);
    }

    private void importConfigInternal(Uri source, OperationCallback callback) {
        try (ConfigInstaller.Transaction transaction = configInstaller.install(source)) {
            if (service != null && platformBridge != null) {
                publish(RuntimeState.STARTING, "配置已校验，正在重启 mihomo TUN…");
                try {
                    startRuntimeOnExistingBridge();
                } catch (Exception startFailure) {
                    transaction.rollback();
                    startRuntimeOnExistingBridge();
                    throw new IOException(
                            "新配置无法启动，已恢复上一份配置：" + usefulMessage(startFailure),
                            startFailure
                    );
                }
            }
            transaction.commit();
            postOperation(callback, true, "配置已通过 mihomo 校验并安装");
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

    private void onUnexpectedExit(MihomoRuntime source, int exitCode, String diagnostics) {
        executor.execute(() -> {
            if (runtime != source) {
                return;
            }
            String message = "mihomo 异常退出（" + exitCode + ")"
                    + (diagnostics == null || diagnostics.isBlank() ? "" : "：" + diagnostics);
            failActiveService(message);
        });
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
        closeRuntime();
        if (platformBridge != null) {
            platformBridge.close();
            platformBridge = null;
        }
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

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
