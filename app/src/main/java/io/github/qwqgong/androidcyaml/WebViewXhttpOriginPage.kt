package io.github.qwqgong.androidcyaml

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessagePortCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** One pooled per-origin WebView shared by every XHTTP request to that origin. */
class WebViewXhttpOriginPage(
    val origin: String,
    val binding: WebViewConnectProxy.TargetBinding,
) {
    val activeRequests = AtomicInteger()
    val initializationStarted = AtomicBoolean(false)
    val bridgeInstallationStarted = AtomicBoolean(false)
    val bridgeResolved = AtomicBoolean(false)
    val binaryBridge = AtomicBoolean(false)
    val messagePort = AtomicReference<WebMessagePortCompat?>()
    val ready = CountDownLatch(1)

    @Volatile
    var webView: WebView? = null

    @Volatile
    var error: String? = null

    @Volatile
    var lastUsedNanos: Long = System.nanoTime()

    fun retain() {
        activeRequests.incrementAndGet()
        lastUsedNanos = System.nanoTime()
    }

    fun release() {
        activeRequests.updateAndGet { value -> max(0, value - 1) }
        lastUsedNanos = System.nanoTime()
    }

    fun fail(message: String?) {
        error = message
        bridgeResolved.set(true)
        binaryBridge.set(false)
        closeMessagePort()
        ready.countDown()
    }

    fun resolveBridge(binary: Boolean): Boolean {
        if (!bridgeResolved.compareAndSet(false, true)) {
            return false
        }
        binaryBridge.set(binary)
        ready.countDown()
        return true
    }

    fun disableBinaryBridge(): Boolean {
        if (!binaryBridge.compareAndSet(true, false)) {
            return false
        }
        closeMessagePort()
        return true
    }

    fun closeMessagePort() {
        closeMessagePort(messagePort.getAndSet(null))
    }

    companion object {
        fun closeMessagePort(port: WebMessagePortCompat?) {
            if (port == null) {
                return
            }
            try {
                port.close()
            } catch (exception: RuntimeException) {
                Log.w(WebViewXhttpDialer.TAG, "Unable to close WebView message port", exception)
            }
        }
    }
}
