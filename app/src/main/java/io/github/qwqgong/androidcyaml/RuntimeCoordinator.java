package io.github.qwqgong.androidcyaml;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single owner of the embedded runtime lifecycle. AndroidVpnService supplies
 * platform capabilities; UI and Binder services are observers/clients only.
 */
final class RuntimeCoordinator {
    enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED,
    }

    interface Listener {
        void onRuntimeStateChanged(
                State state,
                String detail,
                String dashboardUrl,
                int controllerPort
        );
    }

    interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private static final String TAG = "AndroidCyaml/Coordinator";
    private static volatile RuntimeCoordinator instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private volatile State state = State.STOPPED;
    private volatile String detail = "VPN 未连接";
    private volatile String dashboardUrl = "";
    private volatile int controllerPort;

    private AndroidVpnService service;
    private AndroidPlatformBridge platformBridge;
    private volatile MihomoRuntime runtime;

    private RuntimeCoordinator(Context context) {
        this.context = context.getApplicationContext();
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
        if (local == null) {
            return 0;
        }
        MihomoRuntime current = local.runtime;
        return current == null ? 0 : current.trimLogCache();
    }

    static boolean persistStateForMemoryKill() {
        // Controller credentials and config are committed synchronously when changed.
        return true;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        mainHandler.post(() -> listener.onRuntimeStateChanged(
                state,
                detail,
                dashboardUrl,
                controllerPort
        ));
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
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
            if (state != State.STOPPED) {
                publish(State.STOPPING, "正在停止 mihomo TUN…");
            }
            cleanupAll();
            service = null;
            publish(State.STOPPED, "VPN 未连接");
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
                publish(State.STARTING, "正在重启 mihomo 内核…");
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
        if (service == requestedService
                && (state == State.STARTING || state == State.RUNNING)) {
            publish(state, detail);
            return;
        }

        cleanupAll();
        service = requestedService;
        publish(State.STARTING, "正在启动 mihomo 并申请原生 TUN…");
        try {
            platformBridge = new AndroidPlatformBridge(requestedService);
            runtime = new MihomoRuntime(
                    context,
                    platformBridge.coreSocketAddress(),
                    this::onUnexpectedExit
            );
            String runningDetail = runtime.start();
            if (!platformBridge.hasUsableTunnel()) {
                throw new IOException("mihomo 未建立 Android TUN");
            }
            publish(State.RUNNING, runningDetail);
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
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        MihomoRuntime candidate = new MihomoRuntime(
                context,
                platformBridge.coreSocketAddress(),
                this::onUnexpectedExit
        );
        runtime = candidate;
        String runningDetail = candidate.start();
        if (!platformBridge.hasUsableTunnel()) {
            throw new IOException("mihomo 未建立 Android TUN");
        }
        publish(State.RUNNING, runningDetail);
    }

    private void importConfigInternal(Uri source, OperationCallback callback) {
        if (source == null) {
            postOperation(callback, false, "未选择配置文件");
            return;
        }

        File candidate = null;
        File backup = null;
        try {
            MihomoRuntime.RuntimeFiles files = MihomoRuntime.ensureRuntimeFiles(context);
            candidate = new File(files.home(), "config.importing.yaml");
            backup = new File(files.home(), "config.previous.yaml");
            copyUriWithLimit(source, candidate, MihomoRuntime.MAX_CONFIG_BYTES);
            MihomoRuntime.validateConfig(context, candidate);

            Files.deleteIfExists(backup.toPath());
            if (files.config().isFile()) {
                MihomoRuntime.moveReplacing(files.config(), backup);
            }
            MihomoRuntime.moveReplacing(candidate, files.config());
            MihomoRuntime.makeConfigReadOnly(files.config());

            if (service != null && platformBridge != null) {
                publish(State.STARTING, "配置已校验，正在重启 mihomo TUN…");
                try {
                    startRuntimeOnExistingBridge();
                } catch (Exception startFailure) {
                    restorePreviousConfig(files.config(), backup);
                    try {
                        startRuntimeOnExistingBridge();
                    } catch (Exception restoreFailure) {
                        startFailure.addSuppressed(restoreFailure);
                        throw startFailure;
                    }
                    throw new IOException(
                            "新配置无法启动，已恢复上一份配置：" + usefulMessage(startFailure),
                            startFailure
                    );
                }
            }

            Files.deleteIfExists(backup.toPath());
            postOperation(callback, true, "配置已通过 mihomo 校验并安装");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Unable to import config", exception);
            if (state == State.STARTING) {
                failActiveService("配置应用失败：" + usefulMessage(exception));
            }
            postOperation(callback, false, usefulMessage(exception));
        } finally {
            if (candidate != null) {
                try {
                    Files.deleteIfExists(candidate.toPath());
                } catch (IOException ignored) {
                    // A later import replaces the stale candidate.
                }
            }
        }
    }

    private static void restorePreviousConfig(File config, File backup) throws IOException {
        Files.deleteIfExists(config.toPath());
        if (!backup.isFile()) {
            throw new IOException("上一份配置不存在，无法回滚");
        }
        MihomoRuntime.moveReplacing(backup, config);
        MihomoRuntime.makeConfigReadOnly(config);
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
        publish(State.FAILED, message);
        if (failedService != null) {
            mainHandler.post(() -> failedService.onCoordinatorFailure(message));
        }
    }

    private void cleanupAll() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (platformBridge != null) {
            platformBridge.close();
            platformBridge = null;
        }
        dashboardUrl = "";
        controllerPort = 0;
    }

    private void publish(State newState, String newDetail) {
        state = newState;
        detail = newDetail == null ? "" : newDetail;
        if (newState == State.RUNNING && runtime != null) {
            dashboardUrl = runtime.dashboardUrl();
            controllerPort = runtime.getControllerPort();
        } else {
            dashboardUrl = "";
            controllerPort = 0;
        }
        State snapshotState = state;
        String snapshotDetail = detail;
        String snapshotDashboard = dashboardUrl;
        int snapshotPort = controllerPort;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onRuntimeStateChanged(
                        snapshotState,
                        snapshotDetail,
                        snapshotDashboard,
                        snapshotPort
                );
            }
        });
    }

    private void copyUriWithLimit(Uri source, File destination, int byteLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > byteLimit) {
                    throw new IOException("config.yaml 超过 32 MiB 限制");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
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
