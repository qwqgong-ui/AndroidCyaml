package io.github.qwqgong.androidcyaml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity implements
        RuntimeControlClient.Listener,
        MainActionsMenu.Listener,
        VpnUiController.Listener {
    private static final int REQUEST_VPN_PERMISSION = 10_001;
    private static final int REQUEST_CONFIG_FILE = 10_002;
    private static final int REQUEST_LOCAL_NETWORK_PERMISSION = 10_003;
    private static final int REQUEST_NETWORK_IDENTITY_PERMISSIONS = 10_004;
    private static final int ANDROID_17_API = 37;

    private RuntimeControlClient controlClient;
    private DashboardController dashboard;
    private UiPreferences preferences;
    private TaskVisibilityController taskVisibility;
    private VpnUiController vpnController;

    private TextView vpnStatus;
    private Switch vpnToggle;

    private RuntimeState runtimeState = RuntimeState.STOPPED;
    private String runtimeDetail = "VPN 未连接";
    private ProcessMatchingMode processMatchingMode = ProcessMatchingMode.ALWAYS;
    private boolean ipv6Enabled = true;
    private boolean ipv6Effective = true;
    private RuntimeLogLevel logLevel = RuntimeLogLevel.WARNING;
    private boolean adaptiveTcpConcurrent;
    private boolean webViewXhttp;
    private boolean lanWebUiPublic;
    private int controllerPort;
    private boolean alwaysOn;
    private boolean lockdown;
    private boolean updatingVpnToggle;
    private boolean autoStartAttempted;
    private boolean activityVisible;
    private RuntimeOverrideSettings pendingRuntimeOverrides;
    private boolean pendingIdentityPermissionStart;
    private boolean pendingIdentityPermissionAutomatic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdgeController.apply(this, findViewById(R.id.root));

        vpnStatus = findViewById(R.id.vpn_status);
        vpnToggle = findViewById(R.id.vpn_toggle);
        TextView appTitle = findViewById(R.id.app_title);
        TextView moreActions = findViewById(R.id.more_actions);
        FrameLayout dashboardContainer = findViewById(R.id.dashboard_container);

        preferences = new UiPreferences(this);
        taskVisibility = new TaskVisibilityController(this);
        dashboard = new DashboardController(this, dashboardContainer, this::showToast);
        controlClient = new RuntimeControlClient(this, this);
        vpnController = new VpnUiController(this, REQUEST_VPN_PERMISSION, this);

        taskVisibility.setHiddenFromRecents(preferences.hideFromRecents());
        appTitle.setOnClickListener(ignored -> showRuntimeInfo());
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
        dashboard.release();
        super.onStop();
        if (!isChangingConfigurations()
                && (getPackageName() + ":ui").equals(
                        android.app.Application.getProcessName()
                )) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    protected void onDestroy() {
        dashboard.release();
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
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NETWORK_IDENTITY_PERMISSIONS) {
            boolean shouldStart = pendingIdentityPermissionStart;
            boolean automatic = pendingIdentityPermissionAutomatic;
            pendingIdentityPermissionStart = false;
            pendingIdentityPermissionAutomatic = false;
            if (!missingNetworkIdentityPermissions().isEmpty()) {
                showToast(getString(R.string.network_identity_permission_denied));
            }
            if (shouldStart) {
                vpnController.requestStart(runtimeState, automatic);
            }
            return;
        }
        if (requestCode != REQUEST_LOCAL_NETWORK_PERMISSION) {
            return;
        }
        RuntimeOverrideSettings requested = pendingRuntimeOverrides;
        pendingRuntimeOverrides = null;
        if (requested == null) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            submitRuntimeOverrides(requested);
        } else {
            showToast(getString(R.string.local_network_permission_denied));
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
            String newProcessMatchingMode,
            boolean newIpv6Enabled,
            boolean newIpv6Effective,
            String newLogLevel,
            boolean newAdaptiveTcpConcurrent,
            boolean newWebViewXhttp,
            boolean newLanWebUiPublic
    ) {
        RuntimeState[] values = RuntimeState.values();
        RuntimeState parsed = state >= 0 && state < values.length
                ? values[state]
                : RuntimeState.FAILED;
        try {
            logLevel = RuntimeLogLevel.fromWireValue(newLogLevel);
        } catch (IllegalArgumentException ignored) {
            logLevel = RuntimeLogLevel.WARNING;
        }
        try {
            processMatchingMode = ProcessMatchingMode.fromWireValue(newProcessMatchingMode);
        } catch (IllegalArgumentException ignored) {
            processMatchingMode = ProcessMatchingMode.ALWAYS;
        }
        ipv6Enabled = newIpv6Enabled;
        ipv6Effective = newIpv6Effective;
        adaptiveTcpConcurrent = newAdaptiveTcpConcurrent;
        webViewXhttp = newWebViewXhttp;
        lanWebUiPublic = newLanWebUiPublic;
        this.controllerPort = controllerPort;
        applySnapshot(
                new RuntimeSnapshot(parsed, detail, dashboardUrl, controllerPort),
                newAlwaysOn,
                newLockdown
        );
    }

    @Override
    public void onControlDisconnected() {
        controllerPort = 0;
        dashboard.release();
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
                processMatchingMode,
                ipv6Enabled,
                ipv6Effective,
                logLevel,
                adaptiveTcpConcurrent,
                webViewXhttp,
                lanWebUiPublic,
                controllerPort,
                this::applyRuntimeOverrides
        );
    }

    @Override
    public void onOpenNetworkNodes() {
        if (runtimeState != RuntimeState.RUNNING) {
            showToast("请先启动 VPN，再设置网络节点");
            return;
        }
        showToast(getString(R.string.network_nodes_loading));
        controlClient.getNetworkSelectionCatalog((success, detail) -> {
            if (!success) {
                showToast(detail);
                return;
            }
            try {
                showNetworkProfiles(new JSONObject(detail));
            } catch (JSONException exception) {
                showToast("无法解析网络节点列表");
            }
        });
    }

    private void showNetworkProfiles(JSONObject catalog) throws JSONException {
        JSONArray profiles = catalog.optJSONArray("profiles");
        if (profiles == null || profiles.length() == 0) {
            showToast("当前没有可识别或已记忆的 Wi-Fi / 移动数据网络");
            return;
        }
        List<JSONObject> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.optJSONObject(index);
            if (profile == null) {
                continue;
            }
            values.add(profile);
            String label = profile.optString("label", "已记忆网络");
            if (profile.optBoolean("current", false)) {
                label += "（当前）";
            }
            String summary = summarizeGroups(profile.optJSONObject("groups"));
            labels.add(summary.isBlank() ? label : label + "\n" + summary);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.network_nodes_title)
                .setItems(labels.toArray(String[]::new), (dialog, which) -> {
                    try {
                        JSONObject profile = values.get(which);
                        JSONObject groups = profile.optJSONObject("groups");
                        if (groups == null || groups.length() == 0) {
                            showToast("config.yaml 的第一个策略组不是 Selector");
                            return;
                        }
                        Iterator<String> names = groups.keys();
                        showNetworkTargets(profile, names.next());
                    } catch (JSONException exception) {
                        showToast("无法解析节点列表");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showNetworkTargets(JSONObject profile, String groupName)
            throws JSONException {
        JSONObject group = profile.getJSONObject("groups").getJSONObject(groupName);
        String selected = group.optString("selected", "");
        JSONArray options = group.optJSONArray("options");
        List<JSONObject> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int selectedIndex = -1;
        if (options != null) {
            for (int index = 0; index < options.length(); index++) {
                JSONObject option = options.optJSONObject(index);
                if (option == null || !option.optBoolean("available", true)) {
                    continue;
                }
                values.add(option);
                String name = option.optString("name", "");
                String label = displayTarget(name, option.optString("effective", ""));
                labels.add((name.equals(selected) ? "✓ " : "") + label);
                if (name.equals(selected)) {
                    selectedIndex = values.size() - 1;
                }
            }
        }
        if (values.isEmpty()) {
            showToast("该策略组当前没有可用节点");
            return;
        }
        int checkedIndex = selectedIndex;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.network_targets_title, groupName))
                .setSingleChoiceItems(
                        labels.toArray(String[]::new),
                        checkedIndex,
                        (dialog, which) -> {
                    dialog.dismiss();
                    JSONObject option = values.get(which);
                    String target = option.optString("name", "");
                    controlClient.setNetworkSelection(
                            profile.optString("identity", ""),
                            groupName,
                            target,
                            (success, detail) -> showToast(success
                                    ? detail
                                    : "节点设置失败：" + detail)
                    );
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String summarizeGroups(JSONObject groups) {
        if (groups == null) {
            return "";
        }
        List<String> summaries = new ArrayList<>();
        Iterator<String> keys = groups.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject group = groups.optJSONObject(name);
            if (group != null) {
                summaries.add(name + "：" + displayTarget(
                        group.optString("selected", ""),
                        group.optString("effective", "")
                ));
            }
        }
        summaries.sort(String::compareTo);
        return String.join("；", summaries);
    }

    private static String displayTarget(String selected, String effective) {
        if (selected == null || selected.isBlank()) {
            return "未选择";
        }
        return effective == null || effective.isBlank() || effective.equals(selected)
                ? selected
                : selected + " → " + effective;
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
        boolean transitional = runtimeState == RuntimeState.STARTING
                || runtimeState == RuntimeState.STOPPING;
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
        if (activityVisible
                && runtimeState == RuntimeState.RUNNING
                && snapshot.dashboardUrl() != null
                && !snapshot.dashboardUrl().isBlank()
                && snapshot.controllerPort() > 0) {
            dashboard.load(snapshot.dashboardUrl(), snapshot.controllerPort());
        } else {
            dashboard.release();
        }
        maybeAutoStart();
    }

    private void onVpnToggleChanged(boolean checked) {
        if (updatingVpnToggle) {
            return;
        }
        if (checked) {
            requestVpnStart(false);
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

    private void showRuntimeInfo() {
        StringBuilder details = new StringBuilder(getString(
                R.string.runtime_info_app,
                BuildConfig.VERSION_NAME
        ));
        for (String detail : runtimeDetail.split("\\s+·\\s+")) {
            if (!detail.isBlank()) {
                details.append('\n').append(detail);
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.runtime_info)
                .setMessage(details)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.popup_menu_background);
        dialog.show();
    }

    private void chooseConfig() {
        try {
            ConfigPicker.open(this, REQUEST_CONFIG_FILE);
        } catch (RuntimeException exception) {
            showToast(getString(R.string.config_import_failed, usefulMessage(exception)));
        }
    }

    private void importConfig(Uri source) {
        controlClient.importConfig(source, (success, detail) -> {
            showToast(success
                    ? getString(R.string.config_imported)
                    : getString(R.string.config_import_failed, detail));
        });
    }

    private void applyRuntimeOverrides(RuntimeOverrideSettings requested) {
        if (requested.lanWebUiPublic()
                && Build.VERSION.SDK_INT >= ANDROID_17_API
                && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            pendingRuntimeOverrides = requested;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK},
                    REQUEST_LOCAL_NETWORK_PERMISSION
            );
            return;
        }
        submitRuntimeOverrides(requested);
    }

    private void submitRuntimeOverrides(RuntimeOverrideSettings requested) {
        pendingRuntimeOverrides = null;
        controlClient.setRuntimeOverrides(
                requested.processMatchingMode(),
                requested.ipv6Enabled(),
                requested.logLevel(),
                requested.adaptiveTcpConcurrent(),
                requested.webViewXhttp(),
                requested.lanWebUiPublic(),
                (success, detail) -> showToast(success
                        ? getString(R.string.runtime_override_applied, detail)
                        : getString(R.string.runtime_override_failed, detail))
        );
    }

    private void maybeAutoStart() {
        if (autoStartAttempted || !preferences.autoStartEnabled()) {
            return;
        }
        if (runtimeState != RuntimeState.STOPPED && runtimeState != RuntimeState.FAILED) {
            return;
        }
        autoStartAttempted = true;
        requestVpnStart(true);
    }

    private void requestVpnStart(boolean automatic) {
        List<String> missing = missingNetworkIdentityPermissions();
        if (!missing.isEmpty() && !preferences.networkIdentityPermissionRequested()) {
            preferences.setNetworkIdentityPermissionRequested();
            pendingIdentityPermissionStart = true;
            pendingIdentityPermissionAutomatic = automatic;
            try {
                requestPermissions(
                        missing.toArray(String[]::new),
                        REQUEST_NETWORK_IDENTITY_PERMISSIONS
                );
                return;
            } catch (RuntimeException exception) {
                pendingIdentityPermissionStart = false;
                pendingIdentityPermissionAutomatic = false;
                showToast(getString(R.string.network_identity_permission_denied));
            }
        }
        vpnController.requestStart(runtimeState, automatic);
    }

    private List<String> missingNetworkIdentityPermissions() {
        List<String> missing = new ArrayList<>(2);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
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
