package io.github.qwqgong.androidcyaml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

object MihomoNative {
    init {
        System.loadLibrary("androidcyaml")
    }

    fun validate(paths: MihomoPaths, candidate: File) {
        requireSuccess(nativeValidate(paths.home.absolutePath, candidate.absolutePath))
    }

    fun prepareTun(
        paths: MihomoPaths,
        settings: RuntimeOverrideSettings,
        ipv6Enabled: Boolean,
    ): TunOptions {
        val payload = requireSuccess(
            nativePrepareTun(
                paths.home.absolutePath,
                paths.config.absolutePath,
                ipv6Enabled,
                settings.processMatchingMode.wireValue,
            ),
        ) ?: throw IOException("mihomo 未返回 Android TUN 参数")
        return TunOptionsCodec.fromJson(payload)
    }

    fun start(
        paths: MihomoPaths,
        controller: MihomoController,
        settings: RuntimeOverrideSettings,
        ipv6Enabled: Boolean,
        tunFileDescriptor: Int,
        callbacks: NativePlatformCallbacks,
        networkEnvironment: String,
    ): String {
        requireSuccess(
            nativeSetWebViewXhttpEnabled(
                settings.webViewXhttp,
                settings.webViewXhttp && callbacks.supportsWebViewXhttpStreamUp(),
            ),
        )
        val payload = try {
            requireSuccess(
                nativeStart(
                    paths.home.absolutePath,
                    paths.config.absolutePath,
                    paths.ui.absolutePath,
                    controller.listenerAddress(),
                    settings.logLevel.wireValue,
                    tunFileDescriptor,
                    ipv6Enabled,
                    settings.processMatchingMode.wireValue,
                    settings.lanWebUiPublic,
                    callbacks,
                    networkEnvironment,
                ),
            )
        } catch (startFailure: IOException) {
            try {
                requireSuccess(nativeSetWebViewXhttpEnabled(false, false))
            } catch (disableFailure: IOException) {
                startFailure.addSuppressed(disableFailure)
            }
            throw startFailure
        }
        if (payload == null || !payload.has("controllerSecret")) {
            throw IOException("mihomo 未返回控制器鉴权信息")
        }
        controller.setPrimarySelector(payload.optString("primarySelector", ""))
        return payload.optString("controllerSecret", "")
    }

    fun setTcpConcurrent(enabled: Boolean) {
        requireSuccess(nativeSetTcpConcurrent(enabled))
    }

    fun stop() {
        requireSuccess(nativeStop())
    }

    fun notifyNetworkChanged(closeConnections: Boolean) {
        requireSuccess(nativeNotifyNetworkChanged(closeConnections))
    }

    fun updateNetworkEnvironment(networkEnvironment: String) {
        requireSuccess(nativeUpdateNetworkEnvironment(networkEnvironment))
    }

    /**
     * Drops the direct DNS candidates of a network AndroidCyaml has stopped
     * remembering, so the two long-term stores retire a network together.
     *
     * Safe to call while the core is stopped: the caches outlive any single core
     * instance, and refusing then would leave the answers behind until their own
     * expiry with no profile left to explain them.
     */
    fun retireNetworkScope(networkIdentity: String) {
        requireSuccess(nativeRetireNetworkScope(networkIdentity))
    }

    fun updateSystemDns(servers: List<String>?) {
        requireSuccess(nativeUpdateSystemDns(JSONArray(servers ?: emptyList<String>()).toString()))
    }

    fun updateIpv6Availability(available: Boolean) {
        requireSuccess(nativeUpdateIpv6Availability(available))
    }

    fun isRunning(): Boolean = nativeIsRunning()

    fun trimMemory(): Int = nativeTrimMemory()

    /** One diagnostics sample of Go runtime and mihomo counters. */
    fun runtimeMetrics(): JSONObject? = requireSuccess(nativeRuntimeMetrics())

    /** Attaches or detaches the core's warning/error classifier. */
    fun setDiagnostics(enabled: Boolean) {
        requireSuccess(nativeSetDiagnostics(enabled))
    }

    /**
     * Chooses whether mihomo's per-connection lines are retained for the
     * diagnostics log, on top of the warnings and errors that always are.
     */
    fun setLogCapture(enabled: Boolean) {
        requireSuccess(nativeSetLogCapture(enabled))
    }

    fun readBrowserRequestBody(bodyId: Long, destination: ByteArray): Int =
        nativeReadBrowserRequestBody(bodyId, destination)

    fun closeBrowserRequestBody(bodyId: Long) {
        if (bodyId > 0L) {
            nativeCloseBrowserRequestBody(bodyId)
        }
    }

    fun pushBrowserResponseChunk(requestId: Long, chunk: ByteArray?): Boolean =
        requestId > 0L &&
            chunk != null &&
            chunk.isNotEmpty() &&
            nativePushBrowserResponseChunk(requestId, chunk)

    fun finishBrowserResponse(requestId: Long, error: String?): Boolean =
        requestId > 0L && nativeFinishBrowserResponse(requestId, error ?: "")

    private fun requireSuccess(raw: String?): JSONObject? {
        if (raw == null || raw.isBlank()) {
            throw IOException("JNI 核心返回空结果")
        }
        try {
            val response = JSONObject(raw)
            if (!response.optBoolean("ok", false)) {
                val error = response.optString("error", "mihomo JNI 操作失败")
                throw IOException(if (error.isBlank()) "mihomo JNI 操作失败" else error)
            }
            return response.optJSONObject("payload")
        } catch (exception: JSONException) {
            throw IOException("无法解析 mihomo JNI 结果", exception)
        }
    }

    private external fun nativeValidate(home: String, configPath: String): String?

    private external fun nativePrepareTun(
        home: String,
        configPath: String,
        ipv6Enabled: Boolean,
        processMatchingMode: String,
    ): String?

    private external fun nativeSetWebViewXhttpEnabled(
        enabled: Boolean,
        requestStreamsSupported: Boolean,
    ): String?

    private external fun nativeReadBrowserRequestBody(bodyId: Long, destination: ByteArray): Int

    private external fun nativeCloseBrowserRequestBody(bodyId: Long)

    private external fun nativePushBrowserResponseChunk(requestId: Long, chunk: ByteArray): Boolean

    private external fun nativeFinishBrowserResponse(requestId: Long, error: String): Boolean

    private external fun nativeStart(
        home: String,
        configPath: String,
        uiPath: String,
        controllerAddress: String,
        logLevel: String,
        tunFileDescriptor: Int,
        ipv6Enabled: Boolean,
        processMatchingMode: String,
        lanWebUiPublic: Boolean,
        callbacks: NativePlatformCallbacks,
        networkEnvironment: String,
    ): String?

    private external fun nativeSetTcpConcurrent(enabled: Boolean): String?

    private external fun nativeStop(): String?

    private external fun nativeNotifyNetworkChanged(closeConnections: Boolean): String?

    private external fun nativeUpdateNetworkEnvironment(networkEnvironment: String): String?

    private external fun nativeRetireNetworkScope(networkIdentity: String): String?

    private external fun nativeUpdateSystemDns(serversJson: String): String?

    private external fun nativeUpdateIpv6Availability(available: Boolean): String?

    private external fun nativeIsRunning(): Boolean

    private external fun nativeTrimMemory(): Int

    private external fun nativeRuntimeMetrics(): String?

    private external fun nativeSetLogCapture(enabled: Boolean): String?

    private external fun nativeSetDiagnostics(enabled: Boolean): String?
}
