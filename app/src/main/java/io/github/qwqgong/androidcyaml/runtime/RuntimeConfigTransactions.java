package io.github.qwqgong.androidcyaml;

import android.net.Uri;
import android.util.Log;

import java.io.IOException;

/** Owns atomic config installation and persisted runtime-override transitions. */
final class RuntimeConfigTransactions {
    interface Lifecycle {
        boolean active();

        String start() throws IOException, InterruptedException;

        void setIdleEffectiveState(
                RuntimeOverrideSettings settings,
                Ipv6EnvironmentMonitor.State networkState
        );

        boolean effectiveIpv6Enabled();

        boolean effectiveTcpConcurrent();

        int controllerPort();
    }

    interface Host {
        boolean hasLocalNetworkAccess();

        Ipv6EnvironmentMonitor.State refreshUnderlyingNetworkState();

        RuntimeSnapshot snapshot();

        void publish(RuntimeSnapshot snapshot);

        void publish(RuntimeState state, String detail);

        void failActiveService(String message);

        void complete(
                RuntimeCoordinator.OperationCallback callback,
                boolean success,
                String detail
        );
    }

    private static final String TAG = "AndroidCyaml/ConfigTx";

    private final ConfigInstaller configInstaller;
    private final RuntimeOverrideStore overrideStore;
    private final Lifecycle lifecycle;
    private final Host host;

    RuntimeConfigTransactions(
            ConfigInstaller configInstaller,
            RuntimeOverrideStore overrideStore,
            Lifecycle lifecycle,
            Host host
    ) {
        this.configInstaller = configInstaller;
        this.overrideStore = overrideStore;
        this.lifecycle = lifecycle;
        this.host = host;
    }

    void importConfig(Uri source, RuntimeCoordinator.OperationCallback callback) {
        try (ConfigInstaller.Transaction transaction = configInstaller.install(source)) {
            if (lifecycle.active()) {
                host.publish(RuntimeState.STARTING, "配置已校验，正在重启 mihomo JNI TUN…");
                try {
                    String detail = lifecycle.start();
                    host.publish(RuntimeState.RUNNING, detail);
                } catch (Exception startFailure) {
                    transaction.rollback();
                    try {
                        String restoredDetail = lifecycle.start();
                        host.publish(RuntimeState.RUNNING, restoredDetail);
                    } catch (Exception restoreFailure) {
                        if (restoreFailure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        IOException combined = new IOException(
                                "新配置无法启动，且恢复上一份配置也失败："
                                        + Exceptions.usefulMessage(startFailure)
                                        + "；" + Exceptions.usefulMessage(restoreFailure),
                                restoreFailure
                        );
                        combined.addSuppressed(startFailure);
                        throw combined;
                    }
                    throw new IOException(
                            "新配置无法启动，已恢复上一份配置："
                                    + Exceptions.usefulMessage(startFailure),
                            startFailure
                    );
                }
            }
            transaction.commit();
            host.complete(callback, true, "配置已通过嵌入式 mihomo 校验并安装");
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Unable to import config", exception);
            if (host.snapshot().state() == RuntimeState.STARTING) {
                host.failActiveService("配置应用失败：" + Exceptions.usefulMessage(exception));
            }
            host.complete(callback, false, Exceptions.usefulMessage(exception));
        }
    }

    void setRuntimeOverrides(
            RuntimeOverrideSettings requested,
            RuntimeCoordinator.OperationCallback callback
    ) {
        if (requested.lanWebUiPublic() && !host.hasLocalNetworkAccess()) {
            host.complete(callback, false, "未授予 Android 17 局域网访问权限");
            return;
        }
        RuntimeOverrideSettings previous = overrideStore.settings();
        if (requested.equals(previous)) {
            host.publish(host.snapshot());
            host.complete(callback, true, "覆写未变更");
            return;
        }

        try {
            overrideStore.setSettings(requested);
        } catch (RuntimeException exception) {
            host.complete(callback, false, Exceptions.usefulMessage(exception));
            return;
        }

        Ipv6EnvironmentMonitor.State networkState = host.refreshUnderlyingNetworkState();
        if (!lifecycle.active()) {
            lifecycle.setIdleEffectiveState(requested, networkState);
            host.publish(host.snapshot());
            host.complete(
                    callback,
                    true,
                    describeOverrides(
                            requested,
                            lifecycle.effectiveIpv6Enabled(),
                            lifecycle.effectiveTcpConcurrent(),
                            0
                    )
            );
            return;
        }

        host.publish(RuntimeState.STARTING, "正在应用运行时覆写并重建 JNI TUN…");
        try {
            String detail = lifecycle.start();
            host.publish(RuntimeState.RUNNING, detail);
            RuntimeOverrideSettings applied = overrideStore.settings();
            host.complete(
                    callback,
                    true,
                    describeOverrides(
                            applied,
                            lifecycle.effectiveIpv6Enabled(),
                            lifecycle.effectiveTcpConcurrent(),
                            lifecycle.controllerPort()
                    )
            );
        } catch (Exception applyFailure) {
            try {
                overrideStore.setSettings(previous);
                host.refreshUnderlyingNetworkState();
                host.publish(RuntimeState.STARTING, "覆写应用失败，正在恢复上一状态…");
                String detail = lifecycle.start();
                host.publish(RuntimeState.RUNNING, detail);
                host.complete(
                        callback,
                        false,
                        "无法应用运行时覆写，已恢复上一状态：" + Exceptions.usefulMessage(applyFailure)
                );
            } catch (Exception restoreFailure) {
                String message = "运行时覆写失败且无法恢复："
                        + Exceptions.usefulMessage(applyFailure)
                        + "；"
                        + Exceptions.usefulMessage(restoreFailure);
                host.failActiveService(message);
                host.complete(callback, false, message);
            }
        }
    }

    private static String describeOverrides(
            RuntimeOverrideSettings settings,
            boolean ipv6Effective,
            boolean tcpConcurrentEffective,
            int controllerPort
    ) {
        String process = "进程匹配 " + settings.processMatchingMode().wireValue();
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
        return "system 全栈；" + process + "；" + ipv6 + "；" + tcpConcurrent
                + "；" + xhttp + "；" + logLevel + "；" + webUi;
    }
}
