package io.github.qwqgong.androidcyaml;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity implements MihomoManager.Listener {
    private static final int PICK_CONFIG_REQUEST = 1001;

    private MihomoManager manager;
    private WebView webView;
    private ProgressBar progress;
    private TextView status;
    private Button importButton;
    private Button restartButton;
    private String loadedDashboardUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        webView = findViewById(R.id.dashboard);
        progress = findViewById(R.id.core_progress);
        status = findViewById(R.id.core_status);
        importButton = findViewById(R.id.import_config);
        restartButton = findViewById(R.id.restart_core);

        configureWebView(savedInstanceState);
        importButton.setOnClickListener(ignored -> chooseConfig());
        restartButton.setOnClickListener(ignored -> {
            loadedDashboardUrl = null;
            manager.restart();
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> {
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        finish();
                    }
                }
        );

        manager = MihomoManager.getInstance(this);
        manager.addListener(this);
        manager.ensureStarted();
    }

    private void configureEdgeToEdge() {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getColor(R.color.surface));
        window.setNavigationBarContrastEnforced(false);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(Bundle savedInstanceState) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " AndroidCyaml/" + BuildConfig.VERSION_NAME);

        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
        );
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new DashboardWebViewClient());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            loadedDashboardUrl = webView.getUrl();
        }
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/yaml",
                        "application/x-yaml",
                        "text/yaml",
                        "text/x-yaml",
                        "text/plain",
                        "application/octet-stream",
                });
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_yaml)), PICK_CONFIG_REQUEST);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.external_link_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_CONFIG_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        importButton.setEnabled(false);
        restartButton.setEnabled(false);
        loadedDashboardUrl = null;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.config_validating);
        manager.importConfig(uri, (success, detail) -> {
            importButton.setEnabled(true);
            restartButton.setEnabled(true);
            if (success) {
                Toast.makeText(this, R.string.config_imported, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(
                        this,
                        getString(R.string.config_import_failed, detail),
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    @Override
    public void onCoreStateChanged(MihomoManager.State state, String detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        switch (state) {
            case STARTING -> {
                progress.setVisibility(View.VISIBLE);
                status.setText(detail.isBlank() ? getString(R.string.core_starting) : detail);
                status.setTextColor(getColor(R.color.on_surface));
                restartButton.setEnabled(false);
            }
            case RUNNING -> {
                progress.setVisibility(View.GONE);
                status.setText(detail.isBlank() ? getString(R.string.core_running) : detail);
                status.setTextColor(getColor(R.color.status_ok));
                restartButton.setEnabled(true);
                String dashboardUrl = manager.getDashboardUrl();
                if (!dashboardUrl.equals(loadedDashboardUrl)) {
                    loadedDashboardUrl = dashboardUrl;
                    webView.loadUrl(dashboardUrl);
                }
            }
            case FAILED -> {
                progress.setVisibility(View.GONE);
                status.setText(detail);
                status.setTextColor(getColor(R.color.status_error));
                restartButton.setEnabled(true);
                if (TextUtils.isEmpty(webView.getUrl())) {
                    showErrorPage(detail);
                }
            }
            case STOPPED -> {
                progress.setVisibility(View.GONE);
                status.setText(R.string.core_stopped);
                status.setTextColor(getColor(R.color.on_surface));
                restartButton.setEnabled(true);
            }
        }
    }

    private void showErrorPage(String detail) {
        String safeDetail = TextUtils.htmlEncode(detail);
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>body{font-family:sans-serif;padding:28px;background:#f7f8fc;color:#171a21}"
                + "h2{color:#b3261e}code{word-break:break-word}</style>"
                + "<h2>mihomo 启动失败</h2><p><code>" + safeDetail + "</code></p>"
                + "<p>可修正 config.yaml 后重新上传，或点击“重启”。</p>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (manager != null) {
            manager.removeListener(this);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class DashboardWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isDashboardUri(uri)) {
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
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame() && isDashboardUri(request.getUrl())) {
                status.setText(getString(R.string.dashboard_load_failed, error.getDescription()));
                status.setTextColor(getColor(R.color.status_error));
            }
        }

        private boolean isDashboardUri(Uri uri) {
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && uri.getPort() == 9090;
        }

        private void openExternal(Uri uri) {
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                Toast.makeText(MainActivity.this, R.string.external_link_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(MainActivity.this, R.string.external_link_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
