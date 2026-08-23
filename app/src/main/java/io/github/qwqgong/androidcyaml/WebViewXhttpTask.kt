package io.github.qwqgong.androidcyaml

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

// One in-flight XHTTP request bridged through a WebViewXhttpOriginPage.
class WebViewXhttpTask(
    val id: Long,
    val requestBody: ByteArray,
    val requestBodyId: Long,
    val page: WebViewXhttpOriginPage,
    private val onReleased: Runnable,
) {
    val headersReady = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    val terminal = AtomicBoolean(false)
    val requestBodyClosed = AtomicBoolean(false)
    val pageReleased = AtomicBoolean(false)

    @Volatile
    var statusCode: Int = 0

    @Volatile
    var statusText: String? = null

    @Volatile
    var headersJson: String? = null

    @Volatile
    var error: String? = null

    fun headers(status: Int, text: String?, encodedHeaders: String?) {
        if (headersReady.count == 0L) {
            return
        }
        statusCode = status
        statusText = text
        headersJson = encodedHeaders
        headersReady.countDown()
    }

    fun data(bytes: ByteArray?) {
        if (!closed.get() && bytes != null && bytes.isNotEmpty()) {
            if (MihomoNative.pushBrowserResponseChunk(id, bytes)) {
                if (WebViewXhttpDialer.DIRECT_RESPONSE_BRIDGE_LOGGED.compareAndSet(false, true)) {
                    Log.i(WebViewXhttpDialer.TAG, "Direct JNI WebView response bridge is active")
                }
            } else if (!closed.get()) {
                fail("Native WebView response bridge is unavailable")
            }
        }
    }

    fun complete() {
        if (!terminal.compareAndSet(false, true)) {
            return
        }
        try {
            closeRequestBody()
            MihomoNative.finishBrowserResponse(id, "")
        } finally {
            headersReady.countDown()
            releasePage()
        }
    }

    fun fail(message: String?) {
        if (!terminal.compareAndSet(false, true)) {
            return
        }
        try {
            closeRequestBody()
            val reason = message ?: "System WebView XHTTP failed"
            error = reason
            MihomoNative.finishBrowserResponse(id, reason)
        } finally {
            headersReady.countDown()
            releasePage()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closeRequestBody()
                if (headersReady.count != 0L) {
                    error = "System WebView XHTTP request was canceled"
                }
                if (terminal.compareAndSet(false, true)) {
                    MihomoNative.finishBrowserResponse(
                        id,
                        error ?: "System WebView XHTTP request was canceled",
                    )
                }
            } finally {
                headersReady.countDown()
                releasePage()
            }
        }
    }

    fun discard() {
        releasePage()
    }

    private fun releasePage() {
        if (pageReleased.compareAndSet(false, true)) {
            onReleased.run()
        }
    }

    private fun closeRequestBody() {
        if (requestBodyId > 0L && requestBodyClosed.compareAndSet(false, true)) {
            MihomoNative.closeBrowserRequestBody(requestBodyId)
        }
    }
}
