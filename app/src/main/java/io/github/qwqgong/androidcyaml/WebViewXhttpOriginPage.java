package io.github.qwqgong.androidcyaml;

import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.WebMessagePortCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One pooled per-origin WebView shared by every XHTTP request to that origin. */
final class WebViewXhttpOriginPage {
    final String origin;
    final WebViewConnectProxy.TargetBinding binding;
    final AtomicInteger activeRequests = new AtomicInteger();
    final AtomicBoolean initializationStarted = new AtomicBoolean(false);
    final AtomicBoolean bridgeInstallationStarted = new AtomicBoolean(false);
    final AtomicBoolean bridgeResolved = new AtomicBoolean(false);
    final AtomicBoolean binaryBridge = new AtomicBoolean(false);
    final AtomicReference<WebMessagePortCompat> messagePort = new AtomicReference<>();
    final CountDownLatch ready = new CountDownLatch(1);
    volatile WebView webView;
    volatile String error;
    volatile long lastUsedNanos = System.nanoTime();

    WebViewXhttpOriginPage(String origin, WebViewConnectProxy.TargetBinding binding) {
        this.origin = origin;
        this.binding = binding;
    }

    void retain() {
        activeRequests.incrementAndGet();
        lastUsedNanos = System.nanoTime();
    }

    void release() {
        activeRequests.updateAndGet(value -> Math.max(0, value - 1));
        lastUsedNanos = System.nanoTime();
    }

    void fail(String message) {
        error = message;
        bridgeResolved.set(true);
        binaryBridge.set(false);
        closeMessagePort();
        ready.countDown();
    }

    boolean resolveBridge(boolean binary) {
        if (!bridgeResolved.compareAndSet(false, true)) {
            return false;
        }
        binaryBridge.set(binary);
        ready.countDown();
        return true;
    }

    boolean disableBinaryBridge() {
        if (!binaryBridge.compareAndSet(true, false)) {
            return false;
        }
        closeMessagePort();
        return true;
    }

    void closeMessagePort() {
        closeMessagePort(messagePort.getAndSet(null));
    }

    static void closeMessagePort(WebMessagePortCompat port) {
        if (port == null) {
            return;
        }
        try {
            port.close();
        } catch (RuntimeException exception) {
            Log.w(WebViewXhttpDialer.TAG, "Unable to close WebView message port", exception);
        }
    }
}
