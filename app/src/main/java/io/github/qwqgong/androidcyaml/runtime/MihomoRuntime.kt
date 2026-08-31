package io.github.qwqgong.androidcyaml

import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MihomoRuntime(
    private val fileStore: MihomoFileStore,
    private val tunManager: AndroidTunManager,
    private val platformCallbacks: NativePlatformCallbacks,
    settings: RuntimeOverrideSettings?,
    private val ipv6Enabled: Boolean,
    private val tcpConcurrentEnabled: Boolean,
    systemDnsServers: List<String>?,
    private val networkEnvironment: String,
    private val physicalIpv6Available: Boolean,
) : AutoCloseable {
    private val settings: RuntimeOverrideSettings = settings ?: RuntimeOverrideSettings.defaults()
    private val systemDnsServers: List<String> = systemDnsServers?.toList() ?: emptyList()

    private var controller: MihomoController? = null
    private var started = false

    fun start(): String {
        val paths = fileStore.ensureReady()
        val activeController = MihomoController.reserve(settings.lanWebUiPublic)
        controller = activeController
        platformCallbacks.configureWebViewXhttp(settings.webViewXhttp)
        val tunOptions = MihomoNative.prepareTun(paths, settings, ipv6Enabled)
        val tunnel = tunManager.open(tunOptions)
        val duplicate = ParcelFileDescriptor.dup(tunnel.fileDescriptor)
        val nativeFd = duplicate.detachFd()
        var nativeAcceptedDescriptor = false
        try {
            MihomoNative.updateSystemDns(systemDnsServers)
            val controllerSecret = MihomoNative.start(
                paths,
                activeController,
                settings,
                ipv6Enabled,
                nativeFd,
                platformCallbacks,
                networkEnvironment,
            )
            nativeAcceptedDescriptor = true
            MihomoNative.updateIpv6Availability(ipv6Enabled && physicalIpv6Available)
            MihomoNative.setTcpConcurrent(tcpConcurrentEnabled)
            activeController.setSecret(controllerSecret)
            activeController.awaitReady(90, TimeUnit.SECONDS)
            activeController.awaitTun(10, TimeUnit.SECONDS)
            started = true
            return "mihomo " + BuildConfig.MIHOMO_CHANNEL +
                " · JNI/CGO" +
                (
                    if (platformCallbacks.protectEndpointPath().isEmpty()) {
                        " · protect JNI 回退"
                    } else {
                        " · protect unix socket"
                    }
                ) +
                " · system 全栈" +
                " · 进程匹配 " + settings.processMatchingMode.wireValue +
                (if (ipv6Enabled) " · IPv6" else " · IPv4-only") +
                (if (tcpConcurrentEnabled) " · TCP 并发" else " · TCP 并发关闭") +
                (if (settings.webViewXhttp) " · XHTTP WebView" else " · XHTTP 原生") +
                " · 日志 " + settings.logLevel.wireValue +
                " · WebUI " + activeController.listenerAddress() +
                " · zashboard " + fileStore.dashboardVersion()
        } catch (exception: IOException) {
            abandonFailedStart(exception, nativeAcceptedDescriptor, nativeFd)
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            abandonFailedStart(exception, nativeAcceptedDescriptor, nativeFd)
            throw exception
        }
    }

    private fun abandonFailedStart(
        failure: Exception,
        nativeAcceptedDescriptor: Boolean,
        nativeFd: Int,
    ) {
        try {
            MihomoNative.stop()
        } catch (stopFailure: IOException) {
            failure.addSuppressed(stopFailure)
        }
        if (!nativeAcceptedDescriptor) {
            closeDetachedDescriptor(nativeFd)
        }
        controller = null
    }

    fun controllerPort(): Int = controller?.port() ?: 0

    fun dashboardUrl(): String = controller?.dashboardUrl() ?: ""

    fun trimRebuildableCaches(): Int = MihomoNative.trimMemory()

    fun setTcpConcurrent(enabled: Boolean) {
        if (started && MihomoNative.isRunning()) {
            MihomoNative.setTcpConcurrent(enabled)
        }
    }

    fun updateNetworkEnvironment(networkEnvironment: String) = withActiveRuntime {
        MihomoNative.updateNetworkEnvironment(networkEnvironment)
    }

    fun retireNetworkScope(networkIdentity: String) = withActiveRuntime {
        MihomoNative.retireNetworkScope(networkIdentity)
    }

    fun updateSystemDns(dnsServers: List<String>) = withActiveRuntime {
        MihomoNative.updateSystemDns(dnsServers)
    }

    fun updateIpv6Availability(available: Boolean) = withActiveRuntime {
        MihomoNative.updateIpv6Availability(ipv6Enabled && available)
    }

    fun onPhysicalRouteChanged() = withActiveRuntime {
        MihomoNative.notifyNetworkChanged(true)
    }

    fun selectorSelections(): Map<String, String> {
        val active = activeController() ?: return emptyMap()
        return active.selectorSelections()
    }

    fun selectorSelections(proxies: JSONObject): Map<String, String> {
        val active = activeController() ?: return emptyMap()
        return active.selectorSelections(proxies)
    }

    fun proxySnapshot(): JSONObject = requireController().proxySnapshot()

    fun selectorCatalog(overrides: Map<String, String>): JSONObject =
        requireController().selectorCatalog(overrides)

    fun selectorCatalog(proxies: JSONObject, overrides: Map<String, String>): JSONObject =
        requireController().selectorCatalogFor(proxies, overrides)

    fun selectSelector(group: String, target: String) {
        requireController().selectSelector(group, target)
    }

    fun restoreSelectorSelections(
        selections: Map<String, String>,
    ): MihomoController.SelectionRestoreResult {
        val active = activeController()
            ?: return MihomoController.SelectionRestoreResult(0, 0, 0, 0)
        return active.restoreSelectorSelections(selections)
    }

    override fun close() {
        if (!started && !MihomoNative.isRunning()) {
            controller = null
            return
        }
        started = false
        try {
            MihomoNative.stop()
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to stop embedded mihomo cleanly", exception)
        }
        controller = null
    }

    private fun activeController(): MihomoController? {
        val current = controller
        return if (started && current != null && MihomoNative.isRunning()) current else null
    }

    private inline fun withActiveRuntime(operation: () -> Unit) {
        if (started && MihomoNative.isRunning()) operation()
    }

    private fun requireController(): MihomoController =
        activeController() ?: throw IOException("VPN 未启动")

    private companion object {
        const val TAG = "AndroidCyaml/Runtime"

        fun closeDetachedDescriptor(fileDescriptor: Int) {
            try {
                // Closing the adopted wrapper releases the duplicated descriptor.
                ParcelFileDescriptor.adoptFd(fileDescriptor).close()
            } catch (ignored: IOException) {
                // Startup is already failing; descriptor cleanup is best effort.
            } catch (ignored: RuntimeException) {
                // Startup is already failing; descriptor cleanup is best effort.
            }
        }

    }
}
