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

    fun protectSocket(fileDescriptor: Int): Boolean {
        if (fileDescriptor < 0) {
            return false
        }
        return protect(fileDescriptor)
    }

    private fun protect(fileDescriptor: Int): Boolean = try {
        // Use the same physical network that supplied the current DNS servers.
        // protect alone leaves routing to the default-network policy, which can
        // send system DNS back through the VPN during a Wi-Fi/cellular handover.
        val network = underlyingNetwork
        if (network == null) {
            false
        } else {
            // fromFd duplicates the descriptor: closing this wrapper must not
            // close the Go-owned socket. The network binding belongs to the
            // socket and therefore also applies to the original descriptor.
            ParcelFileDescriptor.fromFd(fileDescriptor).use {
                network.bindSocket(it.fileDescriptor)
            }
            vpnService.protect(fileDescriptor)
        }
    } catch (exception: IOException) {
        Log.w(TAG, "Unable to protect socket fd=$fileDescriptor", exception)
        false
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
