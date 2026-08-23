package io.github.qwqgong.androidcyaml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit

class MihomoController private constructor(
    private val listenerHost: String,
    private val port: Int,
) {
    data class SelectionRestoreResult(
        val restored: Int,
        val fallback: Int,
        val skipped: Int,
        val failed: Int,
    )

    private var secret = ""
    private var primarySelector = ""

    fun setPrimarySelector(name: String?) {
        primarySelector = name?.trim() ?: ""
    }

    fun port(): Int = port

    fun listenerAddress(): String = "$listenerHost:$port"

    fun setSecret(secret: String?) {
        this.secret = secret ?: ""
    }

    fun dashboardUrl(): String = "http://" + LOCAL_HOST + ":" + port +
        "/ui/#/setup?hostname=" + LOCAL_HOST +
        "&port=" + port +
        "&secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8) +
        "&disableUpgradeCore=1&disableTunMode=1&type=clash"

    fun awaitReady(timeout: Long, unit: TimeUnit) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline && MihomoNative.isRunning()) {
            var connection: HttpURLConnection? = null
            try {
                connection = open("/version")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    return
                }
            } catch (ignored: IOException) {
                // Startup is polled until the deadline.
            } finally {
                connection?.disconnect()
            }
            Thread.sleep(120)
        }
        if (!MihomoNative.isRunning()) {
            throw IOException("mihomo JNI 核心已停止")
        }
        throw IOException("mihomo 控制器未在 90 秒内就绪")
    }

    fun awaitTun(timeout: Long, unit: TimeUnit) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        var lastFailure: IOException? = null
        while (System.nanoTime() < deadline && MihomoNative.isRunning()) {
            var connection: HttpURLConnection? = null
            try {
                connection = open("/configs")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("mihomo 控制器无法读取 TUN 状态")
                }
                val tun = JSONObject(readBody(connection)).optJSONObject("tun")
                if (tun != null &&
                    tun.optBoolean("enable", false) &&
                    tun.optInt("file-descriptor", 0) > 0
                ) {
                    return
                }
                lastFailure = IOException("mihomo TUN 未使用 Android 文件描述符")
            } catch (exception: IOException) {
                lastFailure = exception
            } catch (exception: JSONException) {
                lastFailure = IOException("无法解析 mihomo TUN 状态", exception)
            } finally {
                connection?.disconnect()
            }
            Thread.sleep(120)
        }
        if (!MihomoNative.isRunning()) {
            throw IOException("mihomo JNI 核心在建立 TUN 时停止")
        }
        throw lastFailure ?: IOException("mihomo TUN 未在 10 秒内就绪")
    }

    fun selectorSelections(): Map<String, String> = selectorSelections(proxySnapshot())

    fun selectorSelections(proxies: JSONObject): Map<String, String> {
        val selections: MutableMap<String, String> = TreeMap()
        for (name in proxies.keys()) {
            if (name != primarySelector) {
                continue
            }
            val proxy = proxies.optJSONObject(name)
            if (!isSelector(proxy)) {
                continue
            }
            val selected = proxy!!.optString("now", "")
            if (selected.isNotBlank() && isAvailable(proxies, selected)) {
                selections[name] = selected
            }
        }
        return selections.toMap()
    }

    fun selectorCatalog(overrides: Map<String, String>?): JSONObject =
        selectorCatalogFor(proxySnapshot(), overrides)

    fun selectorCatalogFor(proxies: JSONObject, overrides: Map<String, String>?): JSONObject {
        if (primarySelector.isBlank()) {
            throw IOException("config.yaml 的第一个策略组不是 Selector")
        }
        val all = selectorCatalog(proxies, overrides ?: emptyMap())
        val primary = all.optJSONObject(primarySelector)
            ?: throw IOException("第一个 Selector 策略组已不存在：$primarySelector")
        try {
            return JSONObject().put(primarySelector, primary)
        } catch (impossible: JSONException) {
            throw AssertionError("Unable to encode primary selector", impossible)
        }
    }

    fun selectSelector(group: String, target: String) {
        if (group != primarySelector) {
            throw IOException("只允许设置 config.yaml 的第一个 Selector 策略组")
        }
        val proxies = proxySnapshot()
        val selector = proxies.optJSONObject(group)
        if (!isSelector(selector) || !containsTarget(selector!!, target)) {
            throw IOException("策略组或目标已不存在")
        }
        if (!isAvailable(proxies, target)) {
            throw IOException("目标节点当前不可用")
        }
        selectProxyAndVerify(group, target)
    }

    fun restoreSelectorSelections(remembered: Map<String, String>?): SelectionRestoreResult {
        if (remembered.isNullOrEmpty()) {
            return SelectionRestoreResult(0, 0, 0, 0)
        }
        val proxies = proxySnapshot()
        var restored = 0
        var fallback = 0
        var skipped = 0
        var failed = 0
        for ((group, rememberedTarget) in TreeMap(remembered)) {
            if (group != primarySelector) {
                skipped++
                continue
            }
            val selector = proxies.optJSONObject(group)
            if (!isSelector(selector)) {
                skipped++
                continue
            }
            var target = rememberedTarget
            var usedFallback = false
            if (!containsTarget(selector!!, target) || !isAvailable(proxies, target)) {
                target = automaticFallback(selector, proxies)
                if (target.isBlank()) {
                    skipped++
                    continue
                }
                usedFallback = true
            }
            if (target == selector.optString("now", "")) {
                if (usedFallback) {
                    fallback++
                } else {
                    restored++
                }
                continue
            }
            try {
                selectProxyAndVerify(group, target)
                selector.put("now", target)
                if (usedFallback) {
                    fallback++
                } else {
                    restored++
                }
            } catch (exception: IOException) {
                failed++
            } catch (exception: JSONException) {
                failed++
            }
        }
        return SelectionRestoreResult(restored, fallback, skipped, failed)
    }

    fun proxySnapshot(): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = open("/proxies")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("mihomo 控制器无法读取策略组：HTTP " + connection.responseCode)
            }
            return JSONObject(readBody(connection)).optJSONObject("proxies")
                ?: throw IOException("mihomo 控制器未返回策略组")
        } catch (exception: JSONException) {
            throw IOException("无法解析 mihomo 策略组", exception)
        } finally {
            connection?.disconnect()
        }
    }

    private fun selectProxy(group: String, target: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = open("/proxies/" + encodePathSegment(group))
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = try {
                JSONObject().put("name", target).toString().toByteArray(StandardCharsets.UTF_8)
            } catch (impossible: JSONException) {
                throw AssertionError("Unable to encode proxy selection", impossible)
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output -> output.write(body) }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_NO_CONTENT
            ) {
                throw IOException("mihomo 策略组切换失败：HTTP $responseCode")
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * A successful PUT only means the controller accepted the request. During
     * an underlying-network handover the core can concurrently reset its
     * persistent state, so the accepted selection may be lost. Always read
     * the Selector back and retry before reporting a background restore as
     * successful.
     */
    private fun selectProxyAndVerify(group: String, target: String) {
        var lastFailure: IOException? = null
        for (attempt in 0 until SELECTION_APPLY_ATTEMPTS) {
            try {
                selectProxy(group, target)
                val selector = proxySnapshot().optJSONObject(group)
                if (selector != null && target == selector.optString("now", "")) {
                    return
                }
                lastFailure = IOException("策略组切换未生效：$group -> $target")
            } catch (exception: IOException) {
                lastFailure = exception
            }
            if (attempt + 1 < SELECTION_APPLY_ATTEMPTS) {
                try {
                    Thread.sleep(SELECTION_VERIFY_DELAY_MILLIS)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("策略组切换验证被中断", exception)
                }
            }
        }
        throw lastFailure ?: IOException("策略组切换未生效")
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL("http://" + LOCAL_HOST + ":" + port + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 1_000
        if (secret.isNotEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer $secret")
        }
        return connection
    }

    companion object {
        private const val LOCAL_HOST = "127.0.0.1"
        private const val PUBLIC_HOST = "0.0.0.0"
        private const val PREFERRED_PORT = 17_890
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val SELECTION_APPLY_ATTEMPTS = 3
        private const val SELECTION_VERIFY_DELAY_MILLIS = 100L
        private val AUTOMATIC_PROXY_TYPES = setOf("urltest", "fallback", "loadbalance", "smart")

        fun reserve(lanWebUiPublic: Boolean): MihomoController {
            val listenerHost = if (lanWebUiPublic) PUBLIC_HOST else LOCAL_HOST
            return MihomoController(listenerHost, findAvailablePort(listenerHost))
        }

        fun selectorCatalog(proxies: JSONObject, overrides: Map<String, String>): JSONObject {
            try {
                val result = JSONObject()
                val names = proxies.keys().asSequence()
                    .filter { isSelector(proxies.optJSONObject(it)) }
                    .sorted()
                    .toList()
                for (name in names) {
                    val selector = proxies.optJSONObject(name)!!
                    var selected = overrides[name] ?: selector.optString("now", "")
                    if (!containsTarget(selector, selected)) {
                        selected = selector.optString("now", "")
                    }
                    val encoded = JSONObject()
                    encoded.put("selected", selected)
                    encoded.put(
                        "effective",
                        effectiveNode(proxies, selected, overrides, HashSet()),
                    )
                    val options = JSONArray()
                    val all = selector.optJSONArray("all")
                    if (all != null) {
                        for (index in 0 until all.length()) {
                            val target = all.optString(index, "")
                            if (target.isBlank()) {
                                continue
                            }
                            val option = JSONObject()
                            option.put("name", target)
                            option.put(
                                "effective",
                                effectiveNode(proxies, target, overrides, HashSet()),
                            )
                            option.put("available", isAvailable(proxies, target))
                            options.put(option)
                        }
                    }
                    encoded.put("options", options)
                    result.put(name, encoded)
                }
                return result
            } catch (exception: JSONException) {
                throw IOException("无法生成策略节点目录", exception)
            }
        }

        private fun effectiveNode(
            proxies: JSONObject,
            name: String?,
            overrides: Map<String, String>,
            visited: MutableSet<String>,
        ): String {
            if (name == null || name.isBlank() || !visited.add(name)) {
                return name ?: ""
            }
            val proxy = proxies.optJSONObject(name) ?: return name
            val type = proxy.optString("type", "").replace("-", "").lowercase(Locale.ROOT)
            if (!isSelector(proxy) && !AUTOMATIC_PROXY_TYPES.contains(type)) {
                return name
            }
            val next = if (isSelector(proxy)) {
                overrides[name] ?: proxy.optString("now", "")
            } else {
                proxy.optString("now", "")
            }
            if (next.isBlank()) {
                return name
            }
            return effectiveNode(proxies, next, overrides, visited)
        }

        private fun isSelector(proxy: JSONObject?): Boolean =
            proxy != null && "selector".equals(proxy.optString("type", ""), ignoreCase = true)

        private fun containsTarget(selector: JSONObject, target: String?): Boolean {
            val all = selector.optJSONArray("all")
            if (all == null || target == null || target.isBlank()) {
                return false
            }
            for (index in 0 until all.length()) {
                if (target == all.optString(index, "")) {
                    return true
                }
            }
            return false
        }

        private fun isAvailable(proxies: JSONObject, target: String): Boolean {
            val proxy = proxies.optJSONObject(target)
            return proxy != null && (!proxy.has("alive") || proxy.optBoolean("alive", true))
        }

        private fun automaticFallback(selector: JSONObject, proxies: JSONObject): String {
            val all = selector.optJSONArray("all") ?: return ""
            for (index in 0 until all.length()) {
                val candidate = all.optString(index, "")
                val proxy = proxies.optJSONObject(candidate)
                if (proxy == null || !isAvailable(proxies, candidate)) {
                    continue
                }
                val type = proxy.optString("type", "").replace("-", "").lowercase(Locale.ROOT)
                if (AUTOMATIC_PROXY_TYPES.contains(type)) {
                    return candidate
                }
            }
            return ""
        }

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun readBody(connection: HttpURLConnection): String {
            connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        total += read
                        if (total > MAX_RESPONSE_BYTES) {
                            throw IOException("mihomo 控制器响应过大")
                        }
                        output.write(buffer, 0, read)
                    }
                    return output.toString(StandardCharsets.UTF_8)
                }
            }
        }

        private fun findAvailablePort(listenerHost: String): Int {
            try {
                ServerSocket().use { reservation ->
                    reservation.reuseAddress = true
                    reservation.bind(
                        InetSocketAddress(InetAddress.getByName(listenerHost), PREFERRED_PORT),
                        1,
                    )
                    return PREFERRED_PORT
                }
            } catch (ignored: IOException) {
                // Fall back to an ephemeral address on the same listener interface.
            }
            ServerSocket().use { reservation ->
                reservation.reuseAddress = false
                reservation.bind(InetSocketAddress(InetAddress.getByName(listenerHost), 0), 1)
                return reservation.localPort
            }
        }
    }
}
