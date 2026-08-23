package io.github.qwqgong.androidcyaml

import android.net.Uri
import android.util.Log
import java.io.IOException

/** Owns atomic config installation and persisted runtime-override transitions. */
class RuntimeConfigTransactions(
    private val configInstaller: ConfigInstaller,
    private val overrideStore: RuntimeOverrideStore,
    private val lifecycle: Lifecycle,
    private val host: Host,
) {
    interface Lifecycle {
        fun active(): Boolean

        fun start(): String

        fun setIdleEffectiveState(
            settings: RuntimeOverrideSettings,
            networkState: Ipv6EnvironmentMonitor.State,
        )

        fun effectiveIpv6Enabled(): Boolean

        fun effectiveTcpConcurrent(): Boolean

        fun controllerPort(): Int
    }

    interface Host {
        fun hasLocalNetworkAccess(): Boolean

        fun refreshUnderlyingNetworkState(): Ipv6EnvironmentMonitor.State

        fun snapshot(): RuntimeSnapshot

        fun publish(snapshot: RuntimeSnapshot)

        fun publish(state: RuntimeState, detail: String)

        fun failActiveService(message: String)

        fun complete(
            callback: RuntimeCoordinator.OperationCallback?,
            success: Boolean,
            detail: String,
        )
    }

    fun importConfig(source: Uri?, callback: RuntimeCoordinator.OperationCallback?) {
        try {
            configInstaller.install(source).use { transaction ->
                if (lifecycle.active()) {
                    host.publish(RuntimeState.STARTING, "配置已校验，正在重启 mihomo JNI TUN…")
                    try {
                        host.publish(RuntimeState.RUNNING, lifecycle.start())
                    } catch (startFailure: Exception) {
                        transaction.rollback()
                        try {
                            host.publish(RuntimeState.RUNNING, lifecycle.start())
                        } catch (restoreFailure: Exception) {
                            if (restoreFailure is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            val combined = IOException(
                                "新配置无法启动，且恢复上一份配置也失败：" +
                                    Exceptions.usefulMessage(startFailure) +
                                    "；" + Exceptions.usefulMessage(restoreFailure),
                                restoreFailure,
                            )
                            combined.addSuppressed(startFailure)
                            throw combined
                        }
                        throw IOException(
                            "新配置无法启动，已恢复上一份配置：" +
                                Exceptions.usefulMessage(startFailure),
                            startFailure,
                        )
                    }
                }
                transaction.commit()
                host.complete(callback, true, "配置已通过嵌入式 mihomo 校验并安装")
            }
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Log.e(TAG, "Unable to import config", exception)
            if (host.snapshot().state == RuntimeState.STARTING) {
                host.failActiveService("配置应用失败：" + Exceptions.usefulMessage(exception))
            }
            host.complete(callback, false, Exceptions.usefulMessage(exception))
        }
    }

    fun setRuntimeOverrides(
        requested: RuntimeOverrideSettings,
        callback: RuntimeCoordinator.OperationCallback?,
    ) {
        if (requested.lanWebUiPublic && !host.hasLocalNetworkAccess()) {
            host.complete(callback, false, "未授予 Android 17 局域网访问权限")
            return
        }
        val previous = overrideStore.settings()
        if (requested == previous) {
            host.publish(host.snapshot())
            host.complete(callback, true, "覆写未变更")
            return
        }

        try {
            overrideStore.setSettings(requested)
        } catch (exception: RuntimeException) {
            host.complete(callback, false, Exceptions.usefulMessage(exception))
            return
        }

        val networkState = host.refreshUnderlyingNetworkState()
        if (!lifecycle.active()) {
            lifecycle.setIdleEffectiveState(requested, networkState)
            host.publish(host.snapshot())
            host.complete(
                callback,
                true,
                describeOverrides(requested, lifecycle.effectiveIpv6Enabled(), 0),
            )
            return
        }

        host.publish(RuntimeState.STARTING, "正在应用运行时覆写并重建 JNI TUN…")
        try {
            host.publish(RuntimeState.RUNNING, lifecycle.start())
            val applied = overrideStore.settings()
            host.complete(
                callback,
                true,
                describeOverrides(
                    applied,
                    lifecycle.effectiveIpv6Enabled(),
                    lifecycle.controllerPort(),
                ),
            )
        } catch (applyFailure: Exception) {
            try {
                overrideStore.setSettings(previous)
                host.refreshUnderlyingNetworkState()
                host.publish(RuntimeState.STARTING, "覆写应用失败，正在恢复上一状态…")
                host.publish(RuntimeState.RUNNING, lifecycle.start())
                host.complete(
                    callback,
                    false,
                    "无法应用运行时覆写，已恢复上一状态：" +
                        Exceptions.usefulMessage(applyFailure),
                )
            } catch (restoreFailure: Exception) {
                val message = "运行时覆写失败且无法恢复：" +
                    Exceptions.usefulMessage(applyFailure) +
                    "；" +
                    Exceptions.usefulMessage(restoreFailure)
                host.failActiveService(message)
                host.complete(callback, false, message)
            }
        }
    }

    private companion object {
        const val TAG = "AndroidCyaml/ConfigTx"

        fun describeOverrides(
            settings: RuntimeOverrideSettings,
            ipv6Effective: Boolean,
            controllerPort: Int,
        ): String {
            val process = "进程匹配 " + settings.processMatchingMode.wireValue
            val ipv6 = when {
                !settings.ipv6Enabled -> "IPv6 已关闭"
                ipv6Effective -> "IPv6 已开启"
                else -> "IPv6 已开启，但当前环境不可用，已自动使用 IPv4"
            }
            val tcpConcurrent = if (settings.adaptiveTcpConcurrent) {
                "TCP 并发已全局开启"
            } else {
                "TCP 并发已全局关闭"
            }
            val xhttp = if (settings.webViewXhttp) {
                "XHTTP WebView 已开启"
            } else {
                "XHTTP 使用 mihomo 原生栈"
            }
            val logLevel = "日志 " + settings.logLevel.wireValue
            var webUi = if (settings.lanWebUiPublic) {
                "WebUI 监听 0.0.0.0"
            } else {
                "WebUI 监听 127.0.0.1"
            }
            if (controllerPort > 0) {
                webUi += ":$controllerPort"
            }
            return "system 全栈；$process；$ipv6；$tcpConcurrent；$xhttp；$logLevel；$webUi"
        }
    }
}
