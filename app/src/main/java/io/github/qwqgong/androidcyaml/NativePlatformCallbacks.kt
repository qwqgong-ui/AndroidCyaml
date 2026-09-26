package io.github.qwqgong.androidcyaml

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class NativePlatformCallbacks(private val vpnService: VpnService) : AutoCloseable {
    private val ownerResolver = ConnectionOwnerResolver(vpnService)

    @Volatile
    private var underlyingNetwork: Network? = null

    private var webViewXhttpDialer: WebViewXhttpDialer? = null

    @Synchronized
    fun configureWebViewXhttp(enabled: Boolean) {
        if (enabled) {
            if (webViewXhttpDialer == null) {
                webViewXhttpDialer = WebViewXhttpDialer(vpnService) { underlyingNetwork }
            }
            return
        }
        closeWebViewXhttp()
    }

    fun updateUnderlyingNetwork(networkHandle: Long) {
        underlyingNetwork = try {
            if (networkHandle == 0L) null else Network.fromNetworkHandle(networkHandle)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Unable to update underlying network", exception)
            null
        }
    }

    fun protectSocket(fileDescriptor: Int, bindPhysicalNetwork: Boolean): Boolean {
        if (fileDescriptor < 0) {
            return false
        }
        if (bindPhysicalNetwork && !bindDnsSocket(fileDescriptor)) {
            return false
        }
        return protect(fileDescriptor)
    }

    // A protect-only socket has a VPN bypass bit but no explicit physical netId.
    // On affected Android devices such UDP DNS packets still re-enter the TUN.
    // DNS servers belong to the observed physical network, so bind these sockets
    // before protect/connect; keep ordinary connections on Android's default route.
    private fun bindDnsSocket(fileDescriptor: Int): Boolean {
        var network = underlyingNetwork ?: return false
        repeat(2) {
            try {
                ParcelFileDescriptor.fromFd(fileDescriptor).use { duplicate ->
                    network.bindSocket(duplicate.fileDescriptor)
                }
                return true
            } catch (exception: IOException) {
                val current = underlyingNetwork
                if (current != null && current != network) {
                    network = current
                } else {
                    Log.w(TAG, "Unable to bind DNS socket fd=$fileDescriptor network=${network.networkHandle}", exception)
                    return false
                }
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Unable to bind DNS socket fd=$fileDescriptor", exception)
                return false
            }
        }
        return false
    }

    private fun protect(fileDescriptor: Int): Boolean = try {
        vpnService.protect(fileDescriptor)
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Unable to protect socket fd=$fileDescriptor", exception)
        false
    }

    fun resolveProcessOwner(
        protocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): String = try {
        ownerResolver.resolveEncoded(
            protocol,
            sourceAddress,
            sourcePort,
            destinationAddress,
            destinationPort,
        )
    } catch (exception: IOException) {
        Log.d(TAG, "Unable to resolve connection owner", exception)
        ""
    } catch (exception: RuntimeException) {
        Log.d(TAG, "Unable to resolve connection owner", exception)
        ""
    }

    fun startBrowserRequest(requestJson: String?, requestBody: ByteArray?): String {
        val dialer = currentDialer() ?: return browserError("System WebView XHTTP is disabled")
        return try {
            dialer.startRequest(requestJson, requestBody)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to start System WebView XHTTP request", exception)
            browserError(exception.message)
        }
    }

    fun awaitBrowserResponse(requestId: Long): String {
        val dialer = currentDialer() ?: return browserError("System WebView XHTTP is disabled")
        return try {
            dialer.awaitResponse(requestId)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to await System WebView XHTTP response", exception)
            browserError(exception.message)
        }
    }

    fun readBrowserResponse(requestId: Long, destination: ByteArray?): Int {
        val dialer = currentDialer() ?: return -1
        return try {
            dialer.readResponse(requestId, destination)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to read System WebView XHTTP response", exception)
            -1
        }
    }

    fun closeBrowserRequest(requestId: Long) {
        currentDialer()?.closeRequest(requestId)
    }

    @Synchronized
    fun supportsWebViewXhttpStreamUp(): Boolean =
        webViewXhttpDialer?.supportsRequestStreaming() == true

    @Synchronized
    override fun close() {
        closeWebViewXhttp()
    }

    @Synchronized
    private fun currentDialer(): WebViewXhttpDialer? = webViewXhttpDialer

    private fun closeWebViewXhttp() {
        webViewXhttpDialer?.close()
        webViewXhttpDialer = null
    }

    private companion object {
        const val TAG = "AndroidCyaml/JNI"

        fun browserError(message: String?): String = try {
            JSONObject()
                .put(
                    "error",
                    if (message.isNullOrBlank()) "System WebView XHTTP failed" else message,
                )
                .toString()
        } catch (impossible: JSONException) {
            "{\"error\":\"System WebView XHTTP failed\"}"
        }
    }
}
