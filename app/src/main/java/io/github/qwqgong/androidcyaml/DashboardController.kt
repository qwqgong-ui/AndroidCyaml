package io.github.qwqgong.androidcyaml

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class DashboardController(
    private val activity: Activity,
    private val container: FrameLayout,
    private val messages: MessageSink,
) {
    fun interface MessageSink {
        fun show(message: String)
    }

    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var controllerPort = 0

    fun load(url: String, port: Int) {
        controllerPort = port
        if (webView != null && url == loadedUrl) {
            return
        }
        if (webView != null) {
            release()
        }
        val dashboard = createWebView()
        webView = dashboard
        container.addView(
            dashboard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        loadedUrl = url
        dashboard.loadUrl(url)
    }

    fun handleBack(): Boolean {
        val dashboard = webView
        if (dashboard != null && dashboard.canGoBack()) {
            dashboard.goBack()
            return true
        }
        return false
    }

    fun onTrimMemory(level: Int, activityVisible: Boolean) {
        if (!activityVisible && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            release()
        }
    }

    fun release() {
        val dashboard = webView ?: return
        webView = null
        loadedUrl = null
        container.removeView(dashboard)
        dashboard.onPause()
        dashboard.stopLoading()
        dashboard.webChromeClient = null
        dashboard.webViewClient = WebViewClient()
        dashboard.removeAllViews()
        dashboard.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val dashboard = WebView(activity)
        val settings = dashboard.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.userAgentString =
            settings.userAgentString + " AndroidCyaml/" + BuildConfig.VERSION_NAME
        dashboard.webChromeClient = WebChromeClient()
        dashboard.webViewClient = DashboardWebClient()
        return dashboard
    }

    private fun isControllerUri(uri: Uri?): Boolean {
        if (uri == null || controllerPort <= 0) {
            return false
        }
        val scheme = uri.scheme
        return (scheme == "http" || scheme == "https") &&
            uri.host == "127.0.0.1" &&
            uri.port == controllerPort
    }

    private fun openExternal(uri: Uri?) {
        if (uri == null) {
            return
        }
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") {
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (exception: RuntimeException) {
            messages.show(activity.getString(R.string.external_link_failed))
        }
    }

    private inner class DashboardWebClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            if (isControllerUri(uri)) {
                return false
            }
            openExternal(uri)
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError?,
        ) {
            if (request.isForMainFrame) {
                messages.show(
                    activity.getString(
                        R.string.dashboard_load_failed,
                        error?.description ?: "unknown",
                    ),
                )
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
        }
    }
}
