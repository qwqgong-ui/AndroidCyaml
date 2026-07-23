package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_VPN_PERMISSION = 10_001;
    private static final int REQUEST_CONFIG_FILE = 10_002;
    private static final int MENU_UPLOAD = 1;
    private static final int MENU_RESTART = 2;
    private static final int MENU_VPN_SETTINGS = 3;
    private static final int MENU_AUTO_START = 4;
    private static final int MENU_HIDE_RECENTS = 5;
    private static final String PREFERENCES = "androidcyaml_ui";
    private static final String AUTO_START_KEY = "auto_start_vpn";

    private final IControlCallback controlCallback = new IControlCallback.Stub() {
        @Override
        public void onStateChanged(
                int state,
                String detail,
                boolean alwaysOn,
                boolean lockdown,
                String dashboardUrl,
                int controllerPort
        ) {
            runOnUiThread(() -> applyRuntimeSnapshot(
                    state,
                    detail,
                    alwaysOn,
                    lockdown,
                    dashboardUrl,
                    controllerPort
            ));
        }
    };

    private final ServiceConnection controlConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            control = IAppControl.Stub.asInterface(binder);
            controlBound = true;
            try {
                control.registerCallback(controlCallback);
            } catch (RemoteException exception) {
                showToast(getString(R.string.control_service_unavailable));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controlBound = false;
            control = null;
            coreStatus.setText(R.string.control_service_disconnected);
            releaseDashboardWebView();
        }
    };

    private IAppControl control;
    private boolean controlBound;
    private boolean bindRequested;
    private boolean updatingVpnToggle;
    private boolean autoStartAttempted;
    private boolean alwaysOn;
    private boolean lockdown;
    private RuntimeCoordinator.State runtimeState = RuntimeCoordinator.State.STOPPED;
    private String runtimeDetail = "VPN 未连接";
    private int controllerPort;

    private ProgressBar coreProgress;
    private TextView coreStatus;
    private TextView vpnStatus;
    private Switch vpnToggle;
    private FrameLayout dashboardContainer;
    private WebView webView;
    private String loadedDashboardUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        coreProgress = findViewById(R.id.core_progress);
        coreStatus = findViewById(R.id.core_status);
        vpnStatus = findViewById(R.id.vpn_status);
        vpnToggle = findViewById(R.id.vpn_toggle);
        dashboardContainer = findViewById(R.id.dashboard_container);
        Button moreActions = findViewById(R.id.more_actions);

        moreActions.setOnClickListener(this::showMoreActions);
        vpnToggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingVpnToggle) {
                return;
            }
            if (checked) {
                requestVpnStart(false);
            } else {
                requestVpnStop();
            }
        });
        applyRuntimeSnapshot(
                RuntimeCoordinator.State.STOPPED.ordinal(),
                getString(R.string.vpn_stopped),
                false,
                false,
                "",
                0
        );
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, AppControlService.class);
        bindRequested = bindService(intent, controlConnection, Context.BIND_AUTO_CREATE);
        if (!bindRequested) {
            showToast(getString(R.string.control_service_unavailable));
        }
    }

    @Override
    protected void onStop() {
        if (controlBound && control != null) {
            try {
                control.unregisterCallback(controlCallback);
            } catch (RemoteException ignored) {
                // The default process may already have been reclaimed.
            }
        }
        if (bindRequested) {
            try {
                unbindService(controlConnection);
            } catch (IllegalArgumentException ignored) {
                // Binding may have failed before a connection was delivered.
            }
        }
        bindRequested = false;
        controlBound = false;
        control = null;
        releaseDashboardWebView();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releaseDashboardWebView();
        super.onDestroy();
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                setVpnToggle(false);
                showToast(getString(R.string.vpn_permission_denied));
                if (isAutoStartEnabled()) {
                    setAutoStartEnabled(false);
                    showToast(getString(R.string.auto_start_vpn_permission_denied));
                }
            }
            return;
        }
        if (requestCode == REQUEST_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            Uri source = data.getData();
            if (source == null) {
                return;
            }
            try {
                int flags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(
                        source,
                        flags & Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (SecurityException ignored) {
                // The immediate grant remains valid for this import operation.
            }
            importConfig(source);
        }
    }

    private void applyRuntimeSnapshot(
            int stateOrdinal,
            String detail,
            boolean newAlwaysOn,
            boolean newLockdown,
            String dashboardUrl,
            int newControllerPort
    ) {
        RuntimeCoordinator.State[] values = RuntimeCoordinator.State.values();
        runtimeState = stateOrdinal >= 0 && stateOrdinal < values.length
                ? values[stateOrdinal]
                : RuntimeCoordinator.State.FAILED;
        alwaysOn = newAlwaysOn;
        lockdown = newLockdown;
        controllerPort = newControllerPort;
        runtimeDetail = detail == null || detail.isBlank()
                ? getString(R.string.core_stopped)
                : detail;

        coreStatus.setText(runtimeDetail);
        boolean transitional = runtimeState == RuntimeCoordinator.State.STARTING
                || runtimeState == RuntimeCoordinator.State.STOPPING;
        coreProgress.setVisibility(transitional ? View.VISIBLE : View.GONE);

        switch (runtimeState) {
            case RUNNING -> vpnStatus.setText(lockdown
                    ? R.string.vpn_connected_lockdown
                    : R.string.vpn_connected_native_tun);
            case STARTING -> vpnStatus.setText(R.string.vpn_starting);
            case STOPPING -> vpnStatus.setText(R.string.vpn_stopping);
            case FAILED -> vpnStatus.setText(R.string.vpn_failed);
            case STOPPED -> vpnStatus.setText(R.string.vpn_stopped);
        }

        boolean connected = runtimeState == RuntimeCoordinator.State.RUNNING
                || runtimeState == RuntimeCoordinator.State.STARTING
                || runtimeState == RuntimeCoordinator.State.STOPPING;
        setVpnToggle(connected);
        vpnToggle.setEnabled(!transitional && !(alwaysOn && connected));

        if (runtimeState == RuntimeCoordinator.State.RUNNING
                && dashboardUrl != null
                && !dashboardUrl.isBlank()
                && controllerPort > 0) {
            loadDashboard(dashboardUrl);
        } else {
            releaseDashboardWebView();
        }
        maybeAutoStart();
    }

    private void requestVpnStart(boolean automatic) {
        if (runtimeState == RuntimeCoordinator.State.STARTING
                || runtimeState == RuntimeCoordinator.State.RUNNING) {
            return;
        }
        Intent permissionIntent;
        try {
            permissionIntent = VpnService.prepare(this);
        } catch (RuntimeException exception) {
            setVpnToggle(false);
            showToast(getString(R.string.vpn_permission_failed));
            return;
        }
        if (permissionIntent != null) {
            try {
                startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION);
            } catch (RuntimeException exception) {
                setVpnToggle(false);
                showToast(getString(R.string.vpn_permission_failed));
                if (automatic) {
                    setAutoStartEnabled(false);
                }
            }
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, AndroidVpnService.class)
                .setAction(AndroidVpnService.ACTION_START);
        try {
            startForegroundService(intent);
            setVpnToggle(true);
        } catch (RuntimeException exception) {
            setVpnToggle(false);
            showToast(getString(R.string.vpn_start_failed, usefulMessage(exception)));
        }
    }

    private void requestVpnStop() {
        if (alwaysOn) {
            setVpnToggle(true);
            showToast(getString(R.string.vpn_always_on_controlled));
            return;
        }
        Intent intent = new Intent(this, AndroidVpnService.class)
                .setAction(AndroidVpnService.ACTION_STOP);
        try {
            startService(intent);
        } catch (RuntimeException exception) {
            setVpnToggle(true);
            showToast(getString(R.string.vpn_start_failed, usefulMessage(exception)));
        }
    }

    private void showMoreActions(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, MENU_UPLOAD, 0, R.string.upload_config);
        menu.add(Menu.NONE, MENU_RESTART, 1, R.string.restart_core);
        menu.add(Menu.NONE, MENU_VPN_SETTINGS, 2, R.string.vpn_system_settings);
        MenuItem autoStart = menu.add(Menu.NONE, MENU_AUTO_START, 3, R.string.auto_start_vpn);
        autoStart.setCheckable(true).setChecked(isAutoStartEnabled());
        menu.add(Menu.NONE, MENU_HIDE_RECENTS, 4, R.string.hide_from_recents);
        popup.setOnMenuItemClickListener(item -> handleMenuItem(item, autoStart));
        popup.show();
    }

    private boolean handleMenuItem(MenuItem item, MenuItem autoStartItem) {
        return switch (item.getItemId()) {
            case MENU_UPLOAD -> {
                chooseConfig();
                yield true;
            }
            case MENU_RESTART -> {
                restartRuntime();
                yield true;
            }
            case MENU_VPN_SETTINGS -> {
                openVpnSettings();
                yield true;
            }
            case MENU_AUTO_START -> {
                boolean enabled = !autoStartItem.isChecked();
                autoStartItem.setChecked(enabled);
                setAutoStartEnabled(enabled);
                yield true;
            }
            case MENU_HIDE_RECENTS -> {
                finishAndRemoveTask();
                yield true;
            }
            default -> false;
        };
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/x-yaml",
                        "application/yaml",
                        "text/yaml",
                        "text/x-yaml",
                        "text/plain"
                });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.choose_yaml)),
                    REQUEST_CONFIG_FILE
            );
        } catch (RuntimeException exception) {
            showToast(getString(R.string.config_import_failed, usefulMessage(exception)));
        }
    }

    private void importConfig(Uri source) {
        IAppControl local = control;
        if (local == null) {
            showToast(getString(R.string.control_service_unavailable));
            return;
        }
        coreStatus.setText(R.string.config_validating);
        coreProgress.setVisibility(View.VISIBLE);
        try {
            local.importConfig(source, new IOperationCallback.Stub() {
                @Override
                public void onComplete(boolean success, String message) {
                    runOnUiThread(() -> {
                        restoreRuntimeStatus();
                        showToast(success
                                ? getString(R.string.config_imported)
                                : getString(R.string.config_import_failed, message));
                    });
                }
            });
        } catch (RemoteException exception) {
            restoreRuntimeStatus();
            showToast(getString(R.string.config_import_failed, usefulMessage(exception)));
        }
    }

    private void restoreRuntimeStatus() {
        coreStatus.setText(runtimeDetail);
        boolean transitional = runtimeState == RuntimeCoordinator.State.STARTING
                || runtimeState == RuntimeCoordinator.State.STOPPING;
        coreProgress.setVisibility(transitional ? View.VISIBLE : View.GONE);
    }

    private void restartRuntime() {
        IAppControl local = control;
        if (local == null) {
            showToast(getString(R.string.control_service_unavailable));
            return;
        }
        try {
            local.restartRuntime(new IOperationCallback.Stub() {
                @Override
                public void onComplete(boolean success, String message) {
                    runOnUiThread(() -> showToast(success
                            ? getString(R.string.core_restarted)
                            : getString(R.string.core_restart_failed, message)));
                }
            });
        } catch (RemoteException exception) {
            showToast(getString(R.string.core_restart_failed, usefulMessage(exception)));
        }
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (RuntimeException exception) {
            showToast(getString(R.string.vpn_settings_failed));
        }
    }

    private void maybeAutoStart() {
        if (autoStartAttempted || !isAutoStartEnabled()) {
            return;
        }
        if (runtimeState != RuntimeCoordinator.State.STOPPED
                && runtimeState != RuntimeCoordinator.State.FAILED) {
            return;
        }
        autoStartAttempted = true;
        requestVpnStart(true);
    }

    private boolean isAutoStartEnabled() {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(AUTO_START_KEY, false);
    }

    private void setAutoStartEnabled(boolean enabled) {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        preferences.edit().putBoolean(AUTO_START_KEY, enabled).apply();
        if (enabled) {
            autoStartAttempted = false;
            maybeAutoStart();
        }
    }

    private void setVpnToggle(boolean checked) {
        updatingVpnToggle = true;
        vpnToggle.setChecked(checked);
        updatingVpnToggle = false;
    }

    private void loadDashboard(String url) {
        if (url.equals(loadedDashboardUrl) && webView != null) {
            return;
        }
        releaseDashboardWebView();
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isControllerUri(uri)) {
                    return false;
                }
                openExternalUri(uri);
                return true;
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request.isForMainFrame()) {
                    showToast(getString(
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
        });
        dashboardContainer.addView(
                webView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
        loadedDashboardUrl = url;
        webView.loadUrl(url);
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

    private void openExternalUri(Uri uri) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException exception) {
            showToast(getString(R.string.external_link_failed));
        }
    }

    private void releaseDashboardWebView() {
        WebView current = webView;
        webView = null;
        loadedDashboardUrl = null;
        if (current == null) {
            return;
        }
        dashboardContainer.removeView(current);
        current.stopLoading();
        current.loadUrl("about:blank");
        current.clearHistory();
        current.removeAllViews();
        current.destroy();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
