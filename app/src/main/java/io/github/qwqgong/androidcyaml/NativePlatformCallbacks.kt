package io.github.qwqgong.androidcyaml

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

class NativePlatformCallbacks(private val vpnService: VpnService) : AutoCloseable {
    private val ownerResolver = ConnectionOwnerResolver(vpnService)

    @Volatile
    private var underlyingNetwork: Network? = null

    private var webViewXhttpDialer: WebViewXhttpDialer? = null

    // Sockets reach protect() through this unix endpoint, so the Go core never
    // attaches one of its own threads to ART just to dial. protectSocket(Int)
    // below stays as the JNI fallback for when the endpoint cannot be created.
    private val protectService: SocketProtectService? = try {
        SocketProtectService(File(vpnService.noBackupFilesDir, PROTECT_ENDPOINT_NAME)) {
            protectSocket(it)
        }
    } catch (failure: IOException) {
        Log.w(TAG, "Unable to open the socket protect endpoint; falling back to JNI", failure)
        null
    }

    /** Empty when the endpoint is unavailable and the JNI fallback must be used. */
    fun protectEndpointPath(): String = protectService?.endpointPath().orEmpty()

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

    fun updateWebViewUnderlyingNetwork(networkHandle: Long) {
        underlyingNetwork = try {
            if (networkHandle == 0L) null else Network.fromNetworkHandle(networkHandle)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Unable to update WebView XHTTP underlying network", exception)
            null
        }
    }

    fun protectSocket(fileDescriptor: Int): Boolean {
        if (fileDescriptor < 0) {
            return false
        }
        return protect(fileDescriptor)
    }

    /** Protects a descriptor received over the socket endpoint. */
    fun protectSocket(descriptor: FileDescriptor): Boolean = try {
        ParcelFileDescriptor.dup(descriptor).use { duplicate ->
            protect(duplicate.fd)
        }
    } catch (exception: IOException) {
        Log.w(TAG, "Unable to duplicate a received socket descriptor", exception)
        false
    }

    private fun protect(fileDescriptor: Int): Boolean = try {
        // Protected sockets deliberately remain unbound. Together with
        // VpnService.setUnderlyingNetworks(null), Android's own network scoring decides
        // whether Wi-Fi or cellular carries new upstream connections.
        vpnService.protect(fileDescriptor)
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
        protectService?.close()
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
        const val PROTECT_ENDPOINT_NAME = "protect.sock"

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
