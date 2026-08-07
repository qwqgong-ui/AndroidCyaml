package io.github.qwqgong.androidcyaml;

import android.net.VpnService;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;

final class NativePlatformCallbacks implements AutoCloseable {
    private static final String TAG = "AndroidCyaml/JNI";

    private final VpnService vpnService;
    private final ConnectionOwnerResolver ownerResolver;
    private WebViewXhttpDialer webViewXhttpDialer;

    NativePlatformCallbacks(VpnService vpnService) {
        this.vpnService = Objects.requireNonNull(vpnService);
        ownerResolver = new ConnectionOwnerResolver(vpnService);
    }

    synchronized void configureWebViewXhttp(boolean enabled) throws IOException {
        if (enabled) {
            if (webViewXhttpDialer == null) {
                webViewXhttpDialer = new WebViewXhttpDialer(vpnService);
            }
            return;
        }
        closeWebViewXhttp();
    }

    @SuppressWarnings("unused")
    public boolean protectSocket(int fileDescriptor) {
        if (fileDescriptor < 0) {
            return false;
        }
        try {
            return vpnService.protect(fileDescriptor);
        } catch (RuntimeException exception) {
            Log.w(TAG, "VpnService.protect failed for fd=" + fileDescriptor, exception);
            return false;
        }
    }

    @SuppressWarnings("unused")
    public String resolveProcessOwner(
            int protocol,
            String sourceAddress,
            int sourcePort,
            String destinationAddress,
            int destinationPort
    ) {
        try {
            return ownerResolver.resolveEncoded(
                    protocol,
                    sourceAddress,
                    sourcePort,
                    destinationAddress,
                    destinationPort
            );
        } catch (IOException | RuntimeException exception) {
            Log.d(TAG, "Unable to resolve connection owner", exception);
            return "";
        }
    }

    @SuppressWarnings("unused")
    public String startBrowserRequest(String requestJson, byte[] requestBody) {
        WebViewXhttpDialer dialer;
        synchronized (this) {
            dialer = webViewXhttpDialer;
        }
        if (dialer == null) {
            return browserError("System WebView XHTTP is disabled");
        }
        try {
            return dialer.startRequest(requestJson, requestBody);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start System WebView XHTTP request", exception);
            return browserError(exception.getMessage());
        }
    }

    @SuppressWarnings("unused")
    public int readBrowserResponse(long requestId, byte[] destination) {
        WebViewXhttpDialer dialer;
        synchronized (this) {
            dialer = webViewXhttpDialer;
        }
        if (dialer == null) {
            return -1;
        }
        try {
            return dialer.readResponse(requestId, destination);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read System WebView XHTTP response", exception);
            return -1;
        }
    }

    @SuppressWarnings("unused")
    public void closeBrowserRequest(long requestId) {
        WebViewXhttpDialer dialer;
        synchronized (this) {
            dialer = webViewXhttpDialer;
        }
        if (dialer != null) {
            dialer.closeRequest(requestId);
        }
    }

    @Override
    public synchronized void close() {
        closeWebViewXhttp();
    }

    private void closeWebViewXhttp() {
        if (webViewXhttpDialer != null) {
            webViewXhttpDialer.close();
            webViewXhttpDialer = null;
        }
    }

    private static String browserError(String message) {
        try {
            return new JSONObject()
                    .put("error", message == null || message.isBlank()
                            ? "System WebView XHTTP failed"
                            : message)
                    .toString();
        } catch (JSONException impossible) {
            return "{\"error\":\"System WebView XHTTP failed\"}";
        }
    }
}
