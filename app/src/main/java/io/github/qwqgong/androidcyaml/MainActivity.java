package io.github.qwqgong.androidcyaml;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.VpnService;
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
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity implements
        MihomoManager.Listener,
        AndroidVpnService.Listener {
    private static final int PICK_CONFIG_REQUEST = 1001;
    private static final int PREPARE_VPN_REQUEST = 1002;
    private static final String UI_PREFERENCES = "androidcyaml_ui";
    private static final String HIDE_FROM_RECENTS_KEY = "hide_from_recents";
    private static final int MENU_IMPORT_CONFIG = 1;
    private static final int MENU_RESTART_CORE = 2;
    private static final int MENU_HIDE_FROM_RECENTS = 3;
    private static final int MENU_RUNTIME_OVERRIDES = 4;
    private static final String[] PROCESS_MATCH_VALUES = {
            MihomoManager.PROCESS_MATCH_CONFIG,
            MihomoManager.PROCESS_MATCH_STRICT,
            MihomoManager.PROCESS_MATCH_ALWAYS,
            MihomoManager.PROCESS_MATCH_OFF,
    };

    private MihomoManager manager;
    private WebView webView;
    private ProgressBar progress;
    private TextView status;
    private Button moreActionsButton;
    private Switch vpnSwitch;
    private TextView vpnStatus;
    private boolean updatingVpnSwitch;
    private AndroidVpnService.State vpnState = AndroidVpnService.State.STOPPED;
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
        moreActionsButton = findViewById(R.id.more_actions);
        vpnSwitch = findViewById(R.id.vpn_toggle);
        vpnStatus = findViewById(R.id.vpn_status);
        manager = MihomoManager.getInstance(this);

        configureWebView(savedInstanceState);
        moreActionsButton.setOnClickListener(this::showActionsMenu);
        vpnSwitch.setOnCheckedChangeListener(
                (button, checked) -> onVpnSwitchChanged(checked)
        );
        applySavedRecentsVisibility();

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

        manager.addListener(this);
        AndroidVpnService.addListener(this);
        manager.ensureStarted();
    }

    private void showActionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_IMPORT_CONFIG, 0, R.string.upload_config);
        popup.getMenu().add(0, MENU_RESTART_CORE, 1, R.string.restart_core);
        popup.getMenu().add(0, MENU_RUNTIME_OVERRIDES, 2, R.string.runtime_overrides);
        popup.getMenu()
                .add(0, MENU_HIDE_FROM_RECENTS, 3, R.string.hide_from_recents)
                .setCheckable(true)
                .setChecked(isTaskHiddenFromRecents());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_IMPORT_CONFIG -> chooseConfig();
                case MENU_RESTART_CORE -> {
                    loadedDashboardUrl = null;
                    manager.restart();
                }
                case MENU_RUNTIME_OVERRIDES -> showRuntimeOverrides();
                case MENU_HIDE_FROM_RECENTS -> setTaskHiddenPreference(
                        !isTaskHiddenFromRecents()
                );
                default -> {
                    return false;
                }
            }
            return true;
        });
        popup.show();
    }

    private void showRuntimeOverrides() {
        String current = manager.getProcessMatchOverride();
        int checked = 0;
        for (int index = 0; index < PROCESS_MATCH_VALUES.length; index++) {
            if (PROCESS_MATCH_VALUES[index].equals(current)) {
                checked = index;
                break;
            }
        }
        int[] selected = {checked};
        String[] labels = getResources().getStringArray(R.array.process_match_labels);
        new AlertDialog.Builder(this)
                .setTitle(R.string.process_matching)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> selected[0] = which)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    int selection = selected[0];
                    String mode = PROCESS_MATCH_VALUES[selection];
                    if (MihomoManager.PROCESS_MATCH_CONFIG.equals(mode)) {
                        loadedDashboardUrl = null;
                    }
                    manager.setProcessMatchOverride(mode, (success, detail) -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        Toast.makeText(
                                this,
                                success
                                        ? getString(
                                                R.string.process_override_applied,
                                                labels[selection]
                                        )
                                        : getString(R.string.process_override_failed, detail),
                                Toast.LENGTH_LONG
                        ).show();
                    });
                })
                .show();
    }

    private void applySavedRecentsVisibility() {
        setTaskHiddenFromRecents(isTaskHiddenFromRecents());
    }

    private boolean isTaskHiddenFromRecents() {
        return getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getBoolean(HIDE_FROM_RECENTS_KEY, false);
    }

    private void setTaskHiddenPreference(boolean hidden) {
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(HIDE_FROM_RECENTS_KEY, hidden)
                .apply();
        setTaskHiddenFromRecents(hidden);
    }

    private void setTaskHiddenFromRecents(boolean hidden) {
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask appTask : activityManager.getAppTasks()) {
            if (appTask.getTaskInfo().taskId == getTaskId()) {
                appTask.setExcludeFromRecents(hidden);
                return;
            }
        }
    }

    private void onVpnSwitchChanged(boolean checked) {
        if (updatingVpnSwitch) {
            return;
        }
        if (!checked) {
            if (vpnState != AndroidVpnService.State.RUNNING) {
                return;
            }
            vpnSwitch.setEnabled(false);
            startService(
                    new Intent(this, AndroidVpnService.class)
                            .setAction(AndroidVpnService.ACTION_STOP)
            );
            return;
        }

        if (vpnState == AndroidVpnService.State.RUNNING) {
            return;
        }

        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent == null) {
            startVpnService();
            return;
        }
        try {
            startActivityForResult(permissionIntent, PREPARE_VPN_REQUEST);
        } catch (ActivityNotFoundException exception) {
            setVpnSwitchChecked(false);
            vpnSwitch.setEnabled(true);
            Toast.makeText(this, R.string.vpn_permission_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void startVpnService() {
        loadedDashboardUrl = null;
        vpnSwitch.setEnabled(false);
        try {
            startForegroundService(
                    new Intent(this, AndroidVpnService.class)
                            .setAction(AndroidVpnService.ACTION_START)
            );
        } catch (RuntimeException exception) {
            setVpnSwitchChecked(false);
            vpnSwitch.setEnabled(true);
            Toast.makeText(
                    this,
                    getString(R.string.vpn_start_failed, exception.getMessage()),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void setVpnSwitchChecked(boolean checked) {
        updatingVpnSwitch = true;
        vpnSwitch.setChecked(checked);
        updatingVpnSwitch = false;
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
        if (requestCode == PREPARE_VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                setVpnSwitchChecked(false);
                vpnSwitch.setEnabled(true);
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PICK_CONFIG_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        moreActionsButton.setEnabled(false);
        loadedDashboardUrl = null;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.config_validating);
        manager.importConfig(uri, (success, detail) -> {
            moreActionsButton.setEnabled(true);
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
    public void onVpnStateChanged(AndroidVpnService.State state, String detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        vpnState = state;
        vpnStatus.setText(detail);
        switch (state) {
            case STARTING -> {
                vpnStatus.setTextColor(getColor(R.color.on_surface));
                vpnSwitch.setEnabled(false);
            }
            case RUNNING -> {
                vpnStatus.setTextColor(getColor(R.color.status_ok));
                setVpnSwitchChecked(true);
                vpnSwitch.setEnabled(true);
            }
            case FAILED -> {
                vpnStatus.setTextColor(getColor(R.color.status_error));
                setVpnSwitchChecked(false);
                vpnSwitch.setEnabled(true);
            }
            case STOPPED -> {
                vpnStatus.setTextColor(getColor(R.color.on_surface));
                setVpnSwitchChecked(false);
                vpnSwitch.setEnabled(true);
            }
        }
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
                moreActionsButton.setEnabled(false);
            }
            case RUNNING -> {
                progress.setVisibility(View.GONE);
                status.setText(detail.isBlank() ? getString(R.string.core_running) : detail);
                status.setTextColor(getColor(R.color.status_ok));
                moreActionsButton.setEnabled(true);
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
                moreActionsButton.setEnabled(true);
                if (TextUtils.isEmpty(webView.getUrl())) {
                    showErrorPage(detail);
                }
            }
            case STOPPED -> {
                progress.setVisibility(View.GONE);
                status.setText(R.string.core_stopped);
                status.setTextColor(getColor(R.color.on_surface));
                moreActionsButton.setEnabled(true);
            }
        }
    }

    private void showErrorPage(String detail) {
        String safeDetail = TextUtils.htmlEncode(detail);
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>body{font-family:sans-serif;padding:28px;background:#f7f8fc;color:#171a21}"
                + "h2{color:#b3261e}code{word-break:break-word}</style>"
                + "<h2>mihomo 启动失败</h2><p><code>" + safeDetail + "</code></p>"
                + "<p>可修正 config.yaml 后重新上传，或在更多操作中重启 mihomo。</p>";
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
        AndroidVpnService.removeListener(this);
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
                    && manager.isControllerPort(uri.getPort());
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
