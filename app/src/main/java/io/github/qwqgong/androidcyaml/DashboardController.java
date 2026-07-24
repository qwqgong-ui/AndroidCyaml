package io.github.qwqgong.androidcyaml;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

final class DashboardController {
    interface MessageSink {
        void show(String message);
    }

    private final Activity activity;
    private final FrameLayout container;
    private final MessageSink messages;
    private WebView webView;
    private String retainedPageUrl;
    private String retainedRootUrl;
    private String loadedUrl;
    private int controllerPort;

    DashboardController(Activity activity, FrameLayout container, MessageSink messages) {
        this.activity = activity;
        this.container = container;
        this.messages = messages;
    }

    void load(String url, int port) {
        controllerPort = port;
        if (webView != null && url.equals(loadedUrl)) {
            return;
        }
        if (webView != null) {
            release(false);
        }
        WebView dashboard = createWebView();
        webView = dashboard;
        container.addView(
                dashboard,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
        loadedUrl = url;
        String pageUrl = retainedPageUrl != null && url.equals(retainedRootUrl)
                ? retainedPageUrl
                : url;
        clearRetainedNavigation();
        dashboard.loadUrl(pageUrl);
    }

    boolean handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    void onTrimMemory(int level, boolean activityVisible) {
        if (!activityVisible && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            release(true);
        }
    }

    void release(boolean retainNavigation) {
        WebView dashboard = webView;
        if (dashboard == null) {
            if (!retainNavigation) {
                clearRetainedNavigation();
            }
            return;
        }
        if (retainNavigation) {
            String pageUrl = dashboard.getUrl();
            retainedPageUrl = pageUrl != null && isControllerUri(Uri.parse(pageUrl))
                    ? pageUrl
                    : loadedUrl;
            retainedRootUrl = loadedUrl;
        } else {
            clearRetainedNavigation();
        }
        webView = null;
        loadedUrl = null;
        container.removeView(dashboard);
        dashboard.onPause();
        dashboard.stopLoading();
        dashboard.setWebChromeClient(null);
        dashboard.setWebViewClient(null);
        dashboard.removeAllViews();
        dashboard.destroy();
    }

    private void clearRetainedNavigation() {
        retainedPageUrl = null;
        retainedRootUrl = null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView dashboard = new WebView(activity);
        WebSettings settings = dashboard.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(
                settings.getUserAgentString() + " AndroidCyaml/" + BuildConfig.VERSION_NAME
        );
        WebView.setWebContentsDebuggingEnabled(
                (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
        );
        dashboard.setWebChromeClient(new WebChromeClient());
        dashboard.setWebViewClient(new DashboardWebClient());
        return dashboard;
    }

    private boolean isControllerUri(Uri uri) {
        if (uri == null || controllerPort <= 0) {
            return false;
        }
        String scheme = uri.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme))
                && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == controllerPort;
    }

    private void openExternal(Uri uri) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException exception) {
            messages.show(activity.getString(R.string.external_link_failed));
        }
    }

    private final class DashboardWebClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isControllerUri(uri)) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            if (request.isForMainFrame()) {
                messages.show(activity.getString(
                        R.string.dashboard_load_failed,
                        error == null ? "unknown" : error.getDescription()
                ));
            }
        }

        @Override
        public void onSafeBrowsingHit(
                WebView view,
                WebResourceRequest request,
                int threatType,
                SafeBrowsingResponse callback
        ) {
            callback.backToSafety(true);
        }
    }
}
