package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity implements
        RuntimeControlClient.Listener,
        MainActionsMenu.Listener,
        VpnUiController.Listener {
    private static final int REQUEST_VPN_PERMISSION = 10_001;
    private static final int REQUEST_CONFIG_FILE = 10_002;

    private RuntimeControlClient controlClient;
    private DashboardController dashboard;
    private UiPreferences preferences;
    private TaskVisibilityController taskVisibility;
    private VpnUiController vpnController;

    private ProgressBar coreProgress;
    private TextView coreStatus;
    private TextView vpnStatus;
    private Switch vpnToggle;

    private RuntimeState runtimeState = RuntimeState.STOPPED;
    private String runtimeDetail = "VPN 未连接";
    private TunStackMode tunStack = TunStackMode.SYSTEM;
    private boolean processMatching = true;
    private boolean ipv6Enabled = true;
    private boolean ipv6Effective = true;
    private boolean alwaysOn;
    private boolean lockdown;
    private boolean updatingVpnToggle;
    private boolean autoStartAttempted;
    private boolean activityVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdgeController.apply(this, findViewById(R.id.root));

        coreProgress = findViewById(R.id.core_progress);
        coreStatus = findViewById(R.id.core_status);
        vpnStatus = findViewById(R.id.vpn_status);
        vpnToggle = findViewById(R.id.vpn_toggle);
        Button moreActions = findViewById(R.id.more_actions);
        FrameLayout dashboardContainer = findViewById(R.id.dashboard_container);

        preferences = new UiPreferences(this);
        taskVisibility = new TaskVisibilityController(this);
        dashboard = new DashboardController(this, dashboardContainer, this::showToast);
        controlClient = new RuntimeControlClient(this, this);
        vpnController = new VpnUiController(this, REQUEST_VPN_PERMISSION, this);

        taskVisibility.setHiddenFromRecents(preferences.hideFromRecents());
        moreActions.setOnClickListener(this::showActions);
        vpnToggle.setOnCheckedChangeListener((button, checked) -> onVpnToggleChanged(checked));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> {
                    if (!dashboard.handleBack()) {
                        finish();
                    }
                }
        );
        applySnapshot(RuntimeSnapshot.stopped(), false, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        controlClient.bind();
    }

    @Override
    protected void onStop() {
        activityVisible = false;
        controlClient.unbind();
        dashboard.release(true);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dashboard.release(false);
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        dashboard.onTrimMemory(level, activityVisible);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            vpnController.handlePermissionResult(resultCode == RESULT_OK);
            return;
        }
        if (requestCode == REQUEST_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            Uri source = ConfigPicker.persistReadPermission(this, data);
            if (source != null) {
                importConfig(source);
            }
        }
    }

    @Override
    public void onRuntimeSnapshot(
            int state,
            String detail,
            boolean newAlwaysOn,
            boolean newLockdown,
            String dashboardUrl,
            int controllerPort,
            String newTunStack,
            boolean newProcessMatching,
            boolean newIpv6Enabled,
            boolean newIpv6Effective
    ) {
        RuntimeState[] values = RuntimeState.values();
        RuntimeState parsed = state >= 0 && state < values.length
                ? values[state]
                : RuntimeState.FAILED;
        try {
            tunStack = TunStackMode.fromWireValue(newTunStack);
        } catch (IllegalArgumentException ignored) {
            tunStack = TunStackMode.SYSTEM;
        }
        processMatching = newProcessMatching;
        ipv6Enabled = newIpv6Enabled;
        ipv6Effective = newIpv6Effective;
        applySnapshot(
                new RuntimeSnapshot(parsed, detail, dashboardUrl, controllerPort),
                newAlwaysOn,
                newLockdown
        );
    }

    @Override
    public void onControlDisconnected() {
        coreProgress.setVisibility(View.GONE);
        coreStatus.setText(R.string.control_service_disconnected);
        dashboard.release(true);
    }

    @Override
    public void onUploadConfig() {
        chooseConfig();
    }

    @Override
    public void onRestartRuntime() {
        controlClient.restart((success, detail) -> showToast(success
                ? getString(R.string.core_restarted)
                : getString(R.string.core_restart_failed, detail)));
    }

    @Override
    public void onOpenRuntimeOverrides() {
        RuntimeOverridesDialog.show(
                this,
                tunStack,
                processMatching,
                ipv6Enabled,
                ipv6Effective,
                (newTunStack, newProcessMatching, newIpv6Enabled) ->
                        controlClient.setRuntimeOverrides(
                                newTunStack,
                                newProcessMatching,
                                newIpv6Enabled,
                                (success, detail) -> showToast(success
                                        ? getString(R.string.runtime_override_applied, detail)
                                        : getString(R.string.runtime_override_failed, detail))
                        )
        );
    }

    @Override
    public void onOpenVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (RuntimeException exception) {
            showToast(getString(R.string.vpn_settings_failed));
        }
    }

    @Override
    public void onAutoStartChanged(boolean enabled) {
        preferences.setAutoStartEnabled(enabled);
        autoStartAttempted = false;
        if (enabled) {
            maybeAutoStart();
        }
    }

    @Override
    public void onHideRecentsChanged(boolean hidden) {
        preferences.setHideFromRecents(hidden);
        taskVisibility.setHiddenFromRecents(hidden);
    }

    @Override
    public void onToggleRequested(boolean checked) {
        setVpnToggle(checked);
    }

    @Override
    public void onMessage(String message) {
        showToast(message);
    }

    @Override
    public void onAutomaticPermissionDenied() {
        preferences.setAutoStartEnabled(false);
    }

    private void applySnapshot(
            RuntimeSnapshot snapshot,
            boolean newAlwaysOn,
            boolean newLockdown
    ) {
        runtimeState = snapshot.state();
        alwaysOn = newAlwaysOn;
        lockdown = newLockdown;
        runtimeDetail = snapshot.detail() == null || snapshot.detail().isBlank()
                ? getString(R.string.core_stopped)
                : snapshot.detail();
        coreStatus.setText(runtimeDetail);

        boolean transitional = runtimeState == RuntimeState.STARTING
                || runtimeState == RuntimeState.STOPPING;
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

        boolean connected = runtimeState == RuntimeState.RUNNING
                || runtimeState == RuntimeState.STARTING
                || runtimeState == RuntimeState.STOPPING;
        setVpnToggle(connected);
        vpnToggle.setEnabled(!transitional && !(alwaysOn && connected));
        if (runtimeState == RuntimeState.RUNNING
                && snapshot.dashboardUrl() != null
                && !snapshot.dashboardUrl().isBlank()
                && snapshot.controllerPort() > 0) {
            dashboard.load(snapshot.dashboardUrl(), snapshot.controllerPort());
        } else {
            dashboard.release(true);
        }
        maybeAutoStart();
    }

    private void onVpnToggleChanged(boolean checked) {
        if (updatingVpnToggle) {
            return;
        }
        if (checked) {
            vpnController.requestStart(runtimeState, false);
        } else {
            vpnController.requestStop(alwaysOn);
        }
    }

    private void showActions(View anchor) {
        MainActionsMenu.show(
                this,
                anchor,
                preferences.autoStartEnabled(),
                preferences.hideFromRecents(),
                this
        );
    }

    private void chooseConfig() {
        try {
            ConfigPicker.open(this, REQUEST_CONFIG_FILE);
        } catch (RuntimeException exception) {
            showToast(getString(R.string.config_import_failed, usefulMessage(exception)));
        }
    }

    private void importConfig(Uri source) {
        coreStatus.setText(R.string.config_validating);
        coreProgress.setVisibility(View.VISIBLE);
        controlClient.importConfig(source, (success, detail) -> {
            coreStatus.setText(runtimeDetail);
            boolean transitional = runtimeState == RuntimeState.STARTING
                    || runtimeState == RuntimeState.STOPPING;
            coreProgress.setVisibility(transitional ? View.VISIBLE : View.GONE);
            showToast(success
                    ? getString(R.string.config_imported)
                    : getString(R.string.config_import_failed, detail));
        });
    }

    private void maybeAutoStart() {
        if (autoStartAttempted || !preferences.autoStartEnabled()) {
            return;
        }
        if (runtimeState != RuntimeState.STOPPED && runtimeState != RuntimeState.FAILED) {
            return;
        }
        autoStartAttempted = true;
        vpnController.requestStart(runtimeState, true);
    }

    private void setVpnToggle(boolean checked) {
        updatingVpnToggle = true;
        vpnToggle.setChecked(checked);
        updatingVpnToggle = false;
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
