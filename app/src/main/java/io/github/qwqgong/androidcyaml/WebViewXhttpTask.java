package io.github.qwqgong.androidcyaml;

import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** One in-flight XHTTP request bridged through a {@link WebViewXhttpOriginPage}. */
final class WebViewXhttpTask {
    final long id;
    final byte[] requestBody;
    final long requestBodyId;
    final WebViewXhttpOriginPage page;
    final Runnable onReleased;
    final CountDownLatch headersReady = new CountDownLatch(1);
    final AtomicBoolean closed = new AtomicBoolean(false);
    final AtomicBoolean terminal = new AtomicBoolean(false);
    final AtomicBoolean requestBodyClosed = new AtomicBoolean(false);
    final AtomicBoolean pageReleased = new AtomicBoolean(false);
    volatile int statusCode;
    volatile String statusText;
    volatile String headersJson;
    volatile String error;

    WebViewXhttpTask(
            long id,
            byte[] requestBody,
            long requestBodyId,
            WebViewXhttpOriginPage page,
            Runnable onReleased
    ) {
        this.id = id;
        this.requestBody = requestBody;
        this.requestBodyId = requestBodyId;
        this.page = page;
        this.onReleased = onReleased;
    }

    void headers(int status, String text, String encodedHeaders) {
        if (headersReady.getCount() == 0) {
            return;
        }
        statusCode = status;
        statusText = text;
        headersJson = encodedHeaders;
        headersReady.countDown();
    }

    void data(byte[] bytes) {
        if (!closed.get() && bytes != null && bytes.length != 0) {
            if (MihomoNative.pushBrowserResponseChunk(id, bytes)) {
                if (WebViewXhttpDialer.DIRECT_RESPONSE_BRIDGE_LOGGED.compareAndSet(false, true)) {
                    Log.i(WebViewXhttpDialer.TAG, "Direct JNI WebView response bridge is active");
                }
            } else if (!closed.get()) {
                fail("Native WebView response bridge is unavailable");
            }
        }
    }

    void complete() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            closeRequestBody();
            MihomoNative.finishBrowserResponse(id, "");
        } finally {
            headersReady.countDown();
            releasePage();
        }
    }

    void fail(String message) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            closeRequestBody();
            error = message == null ? "System WebView XHTTP failed" : message;
            MihomoNative.finishBrowserResponse(id, error);
        } finally {
            headersReady.countDown();
            releasePage();
        }
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closeRequestBody();
                if (headersReady.getCount() != 0L) {
                    error = "System WebView XHTTP request was canceled";
                }
                if (terminal.compareAndSet(false, true)) {
                    MihomoNative.finishBrowserResponse(
                            id,
                            error == null ? "System WebView XHTTP request was canceled" : error
                    );
                }
            } finally {
                headersReady.countDown();
                releasePage();
            }
        }
    }

    void discard() {
        releasePage();
    }

    private void releasePage() {
        if (pageReleased.compareAndSet(false, true)) {
            onReleased.run();
        }
    }

    private void closeRequestBody() {
        if (requestBodyId > 0L && requestBodyClosed.compareAndSet(false, true)) {
            MihomoNative.closeBrowserRequestBody(requestBodyId);
        }
    }
}
