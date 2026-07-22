package io.github.qwqgong.androidcyaml;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
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
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG_REQUEST = 1001;
    private static final int PREPARE_VPN_REQUEST = 1002;
    private static final String UI_PREFERENCES = "androidcyaml_ui";
    private static final String HIDE_FROM_RECENTS_KEY = "hide_from_recents";
    private static final String AUTO_START_VPN_KEY = "auto_start_vpn";
    private static final String DASHBOARD_STATE_KEY = "dashboard_state";
    private static final long BACKGROUND_UI_EXIT_DELAY_MILLIS = 750L;
    private static final long FINISHED_TASK_EXIT_DELAY_MILLIS = 250L;
    private static boolean anyUiStarted;
    private static final int MENU_IMPORT_CONFIG = 1;
    private static final int MENU_RESTART_CORE = 2;
    private static final int MENU_HIDE_FROM_RECENTS = 3;
    private static final int MENU_RUNTIME_OVERRIDES = 4;
    private static final int MENU_VPN_SETTINGS = 5;
    private static final int MENU_AUTO_START_VPN = 6;
    private static final String[] PROCESS_MATCH_VALUES = {
            MihomoManager.PROCESS_MATCH_CONFIG,
            MihomoManager.PROCESS_MATCH_STRICT,
            MihomoManager.PROCESS_MATCH_ALWAYS,
            MihomoManager.PROCESS_MATCH_OFF,
    };

    private IAppControl control;
    private boolean controlBound;
    private boolean activityStarted;
    private boolean externalResultPending;
    private boolean autoStartVpnAttempted;
    private boolean vpnPermissionRequestedByAutoStart;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable exitStoppedUiProcess = () -> {
        if (!anyUiStarted && !externalResultPending) {
            finishAndRemoveTask();
            lifecycleHandler.postDelayed(() -> {
                if (!anyUiStarted) {
                    releaseDashboardWebView(false);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, FINISHED_TASK_EXIT_DELAY_MILLIS);
        }
    };
    private FrameLayout dashboardContainer;
    private WebView webView;
    private Bundle retainedDashboardState;
    private ProgressBar progress;
    private TextView status;
    private Button moreActionsButton;
    private Switch vpnSwitch;
    private TextView vpnStatus;
    private boolean updatingVpnSwitch;
    private AndroidVpnService.State vpnState = AndroidVpnService.State.STOPPED;
    private boolean vpnAlwaysOn;
    private int controllerPort;
    private String processMatchOverride = MihomoManager.PROCESS_MATCH_CONFIG;
    private String loadedDashboardUrl;

    private final IControlCallback controlCallback = new IControlCallback.Stub() {
        @Override
        public void onStateChanged(
                int coreStateValue,
                String coreDetail,
                int vpnStateValue,
                String vpnDetail,
                boolean alwaysOn,
                boolean lockdown,
                String dashboardUrl,
                int newControllerPort,
                String newProcessMatchOverride
        ) {
            runOnUiThread(() -> applyControlState(
                    coreStateValue,
                    coreDetail,
                    vpnStateValue,
                    vpnDetail,
                    alwaysOn,
                    dashboardUrl,
                    newControllerPort,
                    newProcessMatchOverride
            ));
        }
    };

    private final ServiceConnection controlConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            control = IAppControl.Stub.asInterface(service);
            try {
                control.registerCallback(controlCallback);
                control.ensureCoreStarted();
            } catch (RemoteException exception) {
                handleControlDisconnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleControlDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleControlDisconnected();
            if (activityStarted) {
                rebindControlService();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handleControlDisconnected();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        dashboardContainer = findViewById(R.id.dashboard_container);
        progress = findViewById(R.id.core_progress);
        status = findViewById(R.id.core_status);
        moreActionsButton = findViewById(R.id.more_actions);
        vpnSwitch = findViewById(R.id.vpn_toggle);
        vpnStatus = findViewById(R.id.vpn_status);
        retainedDashboardState = savedInstanceState == null
                ? null
                : savedInstanceState.getBundle(DASHBOARD_STATE_KEY);
        createDashboardWebView();
        moreActionsButton.setOnClickListener(this::showActionsMenu);
        vpnSwitch.setOnCheckedChangeListener(
                (button, checked) -> onVpnSwitchChanged(checked)
        );
        applySavedRecentsVisibility();

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> {
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        finish();
                    }
                }
        );

        progress.setVisibility(View.VISIBLE);
        moreActionsButton.setEnabled(false);
        vpnSwitch.setEnabled(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleHandler.removeCallbacks(exitStoppedUiProcess);
        anyUiStarted = true;
        activityStarted = true;
        createDashboardWebView();
        bindControlService();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        anyUiStarted = false;
        unbindControlService();
        releaseDashboardWebView(true);
        if (!isChangingConfigurations() && !externalResultPending) {
            lifecycleHandler.postDelayed(
                    exitStoppedUiProcess,
                    BACKGROUND_UI_EXIT_DELAY_MILLIS
            );
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vpnState == AndroidVpnService.State.STARTING
                || vpnState == AndroidVpnService.State.RUNNING) {
            startService(
                    new Intent(this, AndroidVpnService.class)
                            .setAction(AndroidVpnService.ACTION_REFRESH)
            );
        }
    }

    private void bindControlService() {
        if (controlBound) {
            return;
        }
        Intent intent = new Intent(this, AppControlService.class);
        controlBound = bindService(
                intent,
                controlConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT
        );
        if (!controlBound) {
            handleControlDisconnected();
        }
    }

    private void unbindControlService() {
        IAppControl service = control;
        if (service != null) {
            try {
                service.unregisterCallback(controlCallback);
            } catch (RemoteException ignored) {
                // The VPN/core process may already have been reclaimed.
            }
        }
        control = null;
        if (!controlBound) {
            return;
        }
        try {
            unbindService(controlConnection);
        } catch (IllegalArgumentException ignored) {
            // A dead binding can disappear before Activity teardown.
        }
        controlBound = false;
    }

    private void rebindControlService() {
        if (controlBound) {
            try {
                unbindService(controlConnection);
            } catch (IllegalArgumentException ignored) {
                // The dead binding may already have been removed by the system.
            }
            controlBound = false;
        }
        bindControlService();
    }

    private void handleControlDisconnected() {
        control = null;
        progress.setVisibility(View.GONE);
        status.setText(R.string.control_service_disconnected);
        status.setTextColor(getColor(R.color.status_error));
        moreActionsButton.setEnabled(false);
        vpnSwitch.setEnabled(false);
    }

    private void applyControlState(
            int coreStateValue,
            String coreDetail,
            int vpnStateValue,
            String vpnDetail,
            boolean alwaysOn,
            String dashboardUrl,
            int newControllerPort,
            String newProcessMatchOverride
    ) {
        if (!activityStarted || isFinishing() || isDestroyed()) {
            return;
        }
        MihomoManager.State coreState = enumValue(
                MihomoManager.State.values(),
                coreStateValue,
                MihomoManager.State.FAILED
        );
        AndroidVpnService.State newVpnState = enumValue(
                AndroidVpnService.State.values(),
                vpnStateValue,
                AndroidVpnService.State.FAILED
        );
        controllerPort = newControllerPort;
        processMatchOverride = newProcessMatchOverride;
        onVpnStateChanged(newVpnState, vpnDetail, alwaysOn);
        onCoreStateChanged(coreState, coreDetail, dashboardUrl);
        maybeAutoStartVpn();
    }

    private static <T> T enumValue(T[] values, int index, T fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    private void showActionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_IMPORT_CONFIG, 0, R.string.upload_config);
        popup.getMenu().add(0, MENU_RESTART_CORE, 1, R.string.restart_core);
        popup.getMenu().add(0, MENU_RUNTIME_OVERRIDES, 2, R.string.runtime_overrides);
        popup.getMenu().add(0, MENU_VPN_SETTINGS, 3, R.string.vpn_system_settings);
        popup.getMenu()
                .add(0, MENU_AUTO_START_VPN, 4, R.string.auto_start_vpn)
                .setCheckable(true)
                .setChecked(isAutoStartVpnEnabled());
        popup.getMenu()
                .add(0, MENU_HIDE_FROM_RECENTS, 5, R.string.hide_from_recents)
                .setCheckable(true)
                .setChecked(isTaskHiddenFromRecents());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_IMPORT_CONFIG -> chooseConfig();
                case MENU_RESTART_CORE -> {
                    loadedDashboardUrl = null;
                    IAppControl current = control;
                    if (current == null) {
                        showControlUnavailable();
                    } else {
                        try {
                            current.restartCore();
                        } catch (RemoteException exception) {
                            handleControlDisconnected();
                            showControlUnavailable();
                        }
                    }
                }
                case MENU_RUNTIME_OVERRIDES -> showRuntimeOverrides();
                case MENU_VPN_SETTINGS -> openVpnSettings();
                case MENU_AUTO_START_VPN -> setAutoStartVpnEnabled(
                        !isAutoStartVpnEnabled()
                );
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

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.vpn_settings_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showRuntimeOverrides() {
        String current = processMatchOverride;
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
                    IAppControl service = control;
                    if (service == null) {
                        showControlUnavailable();
                        return;
                    }
                    try {
                        service.setProcessMatchOverride(mode, new IOperationCallback.Stub() {
                            @Override
                            public void onComplete(boolean success, String detail) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    Toast.makeText(
                                            MainActivity.this,
                                            success
                                                    ? getString(
                                                            R.string.process_override_applied,
                                                            labels[selection]
                                                    )
                                                    : getString(
                                                            R.string.process_override_failed,
                                                            detail
                                                    ),
                                            Toast.LENGTH_LONG
                                    ).show();
                                });
                            }
                        });
                    } catch (RemoteException exception) {
                        handleControlDisconnected();
                        showControlUnavailable();
                    }
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

    private boolean isAutoStartVpnEnabled() {
        return getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getBoolean(AUTO_START_VPN_KEY, false);
    }

    private void setAutoStartVpnEnabled(boolean enabled) {
        getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(AUTO_START_VPN_KEY, enabled)
                .apply();
        autoStartVpnAttempted = false;
        if (enabled) {
            maybeAutoStartVpn();
        }
    }

    private void maybeAutoStartVpn() {
        if (autoStartVpnAttempted || !isAutoStartVpnEnabled()) {
            return;
        }
        autoStartVpnAttempted = true;
        if (!vpnAlwaysOn && vpnState == AndroidVpnService.State.STOPPED) {
            requestVpnStart(true);
        }
    }

    private void onVpnSwitchChanged(boolean checked) {
        if (updatingVpnSwitch) {
            return;
        }
        if (!checked && vpnAlwaysOn) {
            setVpnSwitchChecked(true);
            Toast.makeText(this, R.string.vpn_always_on_controlled, Toast.LENGTH_LONG).show();
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

        requestVpnStart(false);
    }

    private void requestVpnStart(boolean fromAutoStart) {
        if (vpnState == AndroidVpnService.State.RUNNING) {
            return;
        }
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent == null) {
            vpnPermissionRequestedByAutoStart = false;
            startVpnService();
            return;
        }
        try {
            vpnPermissionRequestedByAutoStart = fromAutoStart;
            externalResultPending = true;
            startActivityForResult(permissionIntent, PREPARE_VPN_REQUEST);
        } catch (ActivityNotFoundException exception) {
            vpnPermissionRequestedByAutoStart = false;
            externalResultPending = false;
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
    private void createDashboardWebView() {
        if (webView != null) {
            return;
        }
        WebView dashboard = new WebView(this);
        dashboardContainer.addView(
                dashboard,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
        webView = dashboard;
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

        if (retainedDashboardState != null) {
            webView.restoreState(retainedDashboardState);
            loadedDashboardUrl = webView.getUrl();
            retainedDashboardState = null;
        }
    }

    private void releaseDashboardWebView(boolean retainNavigationState) {
        WebView dashboard = webView;
        if (dashboard == null) {
            return;
        }
        if (retainNavigationState) {
            Bundle state = new Bundle();
            dashboard.saveState(state);
            retainedDashboardState = state;
        } else {
            retainedDashboardState = null;
        }
        webView = null;
        loadedDashboardUrl = null;
        dashboardContainer.removeView(dashboard);
        dashboard.onPause();
        dashboard.stopLoading();
        dashboard.setWebChromeClient(null);
        dashboard.setWebViewClient(null);
        dashboard.removeAllViews();
        dashboard.destroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!activityStarted && level >= TRIM_MEMORY_UI_HIDDEN) {
            releaseDashboardWebView(true);
        } else if (webView != null && level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            webView.clearCache(false);
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
            externalResultPending = true;
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_yaml)), PICK_CONFIG_REQUEST);
        } catch (ActivityNotFoundException exception) {
            externalResultPending = false;
            Toast.makeText(this, R.string.external_link_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PREPARE_VPN_REQUEST || requestCode == PICK_CONFIG_REQUEST) {
            externalResultPending = false;
        }
        if (requestCode == PREPARE_VPN_REQUEST) {
            boolean requestedByAutoStart = vpnPermissionRequestedByAutoStart;
            vpnPermissionRequestedByAutoStart = false;
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                if (requestedByAutoStart) {
                    setAutoStartVpnEnabled(false);
                }
                setVpnSwitchChecked(false);
                vpnSwitch.setEnabled(true);
                Toast.makeText(
                        this,
                        requestedByAutoStart
                                ? R.string.auto_start_vpn_permission_denied
                                : R.string.vpn_permission_denied,
                        Toast.LENGTH_LONG
                ).show();
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
        IAppControl service = control;
        if (service == null) {
            moreActionsButton.setEnabled(false);
            showControlUnavailable();
            return;
        }
        try {
            service.importConfig(uri, new IOperationCallback.Stub() {
                @Override
                public void onComplete(boolean success, String detail) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        moreActionsButton.setEnabled(true);
                        if (success) {
                            Toast.makeText(
                                    MainActivity.this,
                                    R.string.config_imported,
                                    Toast.LENGTH_LONG
                            ).show();
                        } else {
                            Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.config_import_failed, detail),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        } catch (RemoteException exception) {
            handleControlDisconnected();
            showControlUnavailable();
        }
    }

    private void onVpnStateChanged(
            AndroidVpnService.State state,
            String detail,
            boolean alwaysOn
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        vpnState = state;
        vpnAlwaysOn = alwaysOn;
        vpnStatus.setText(detail);
        switch (state) {
            case STARTING -> {
                vpnStatus.setTextColor(getColor(R.color.on_surface));
                if (alwaysOn) {
                    setVpnSwitchChecked(true);
                }
                vpnSwitch.setEnabled(false);
            }
            case RUNNING -> {
                vpnStatus.setTextColor(getColor(R.color.status_ok));
                setVpnSwitchChecked(true);
                vpnSwitch.setEnabled(!alwaysOn);
            }
            case FAILED -> {
                vpnStatus.setTextColor(getColor(R.color.status_error));
                setVpnSwitchChecked(alwaysOn);
                vpnSwitch.setEnabled(!alwaysOn);
            }
            case STOPPED -> {
                vpnStatus.setTextColor(getColor(R.color.on_surface));
                setVpnSwitchChecked(alwaysOn);
                vpnSwitch.setEnabled(!alwaysOn);
            }
        }
    }

    private void onCoreStateChanged(
            MihomoManager.State state,
            String detail,
            String dashboardUrl
    ) {
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
                if (webView != null
                        && !dashboardUrl.isBlank()
                        && !dashboardUrl.equals(loadedDashboardUrl)) {
                    loadedDashboardUrl = dashboardUrl;
                    webView.loadUrl(dashboardUrl);
                }
            }
            case FAILED -> {
                progress.setVisibility(View.GONE);
                status.setText(detail);
                status.setTextColor(getColor(R.color.status_error));
                moreActionsButton.setEnabled(true);
                if (webView != null && TextUtils.isEmpty(webView.getUrl())) {
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
        if (webView == null) {
            return;
        }
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
        Bundle dashboardState = new Bundle();
        if (webView != null) {
            webView.saveState(dashboardState);
        } else if (retainedDashboardState != null) {
            dashboardState.putAll(retainedDashboardState);
        }
        outState.putBundle(DASHBOARD_STATE_KEY, dashboardState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        unbindControlService();
        releaseDashboardWebView(false);
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
                    && controllerPort > 0
                    && uri.getPort() == controllerPort;
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

    private void showControlUnavailable() {
        Toast.makeText(this, R.string.control_service_unavailable, Toast.LENGTH_LONG).show();
    }
}
