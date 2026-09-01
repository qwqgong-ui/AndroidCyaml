package io.github.qwqgong.androidcyaml

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import java.io.IOException

@Suppress("DEPRECATION")
class MainActivity :
    Activity(),
    RuntimeControlClient.Listener,
    MainActionsMenu.Listener,
    VpnUiController.Listener {

    private lateinit var controlClient: RuntimeControlClient
    private lateinit var dashboard: DashboardController
    private lateinit var preferences: UiPreferences
    private lateinit var taskVisibility: TaskVisibilityController
    private lateinit var vpnController: VpnUiController

    private lateinit var vpnStatus: TextView
    private lateinit var vpnToggle: Switch

    private var runtimeState = RuntimeState.STOPPED
    private var runtimeDetail = "VPN 未连接"
    private var processMatchingMode = ProcessMatchingMode.ALWAYS
    private var ipv6Enabled = true
    private var ipv6Effective = true
    private var logLevel = RuntimeLogLevel.WARNING
    private var adaptiveTcpConcurrent = false
    private var webViewXhttp = false
    private var lanWebUiPublic = false
    private var controllerPort = 0
    private var alwaysOn = false
    private var lockdown = false
    private var updatingVpnToggle = false
    private var autoStartAttempted = false
    private var activityVisible = false
    private var pendingRuntimeOverrides: RuntimeOverrideSettings? = null
    private var pendingIdentityPermissionStart = false
    private var pendingIdentityPermissionAutomatic = false
    private var backAnimator: PredictiveBackAnimator? = null
    private var backCallbackRegistered = false

    private val backCallback = object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
            backAnimator?.start(backEvent)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            backAnimator?.update(backEvent)
        }

        override fun onBackCancelled() {
            backAnimator?.settle()
        }

        override fun onBackInvoked() {
            val handled = dashboard.handleBack()
            backAnimator?.settle()
            if (!handled) {
                finish()
            }
        }
    }

    private val networkNodesListener = object : NetworkNodesDialog.Listener {
        override fun onMessage(message: String) {
            showToast(message)
        }

        override fun onTargetSelected(identity: String, group: String, target: String) {
            controlClient.setNetworkSelection(identity, group, target) { success, detail ->
                showToast(if (success) detail else "节点设置失败：$detail")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EdgeToEdgeController.apply(this, findViewById(R.id.root))

        vpnStatus = findViewById(R.id.vpn_status)
        vpnToggle = findViewById(R.id.vpn_toggle)
        val appTitle = findViewById<TextView>(R.id.app_title)
        val moreActions = findViewById<TextView>(R.id.more_actions)
        val dashboardContainer = findViewById<FrameLayout>(R.id.dashboard_container)

        preferences = UiPreferences(this)
        taskVisibility = TaskVisibilityController(this)
        dashboard = DashboardController(
            this,
            dashboardContainer,
            { message -> showToast(message) },
            { syncBackCallback() },
        )
        controlClient = RuntimeControlClient(this, this)
        vpnController = VpnUiController(this, REQUEST_VPN_PERMISSION, this)

        taskVisibility.setHiddenFromRecents(preferences.hideFromRecents())
        appTitle.setOnClickListener { showRuntimeInfo() }
        moreActions.setOnClickListener { anchor -> showActions(anchor) }
        vpnToggle.setOnCheckedChangeListener { _, checked -> onVpnToggleChanged(checked) }
        backAnimator = PredictiveBackAnimator(dashboardContainer)
        restorePendingState(savedInstanceState)
        applySnapshot(RuntimeSnapshot.stopped(), false, false)
        if (savedInstanceState == null) {
            handleLaunchAction()
        }
    }

    /**
     * The import shortcut lands here because picking and installing a config
     * needs the bound control service and a place to report the result.
     */
    private fun handleLaunchAction() {
        if (ACTION_IMPORT_CONFIG != intent?.action) {
            return
        }
        // Clear the action so a configuration change does not reopen the picker.
        setIntent(Intent(this, MainActivity::class.java))
        chooseConfig()
    }

    /**
     * The dashboard only owns back while its WebView has in-page history. Leaving
     * the dispatcher untouched otherwise lets the system run its own predictive
     * animation for leaving the activity.
     */
    private fun syncBackCallback() {
        setBackCallbackRegistered(dashboard.canGoBack())
    }

    private fun setBackCallbackRegistered(registered: Boolean) {
        if (registered == backCallbackRegistered) {
            return
        }
        backCallbackRegistered = registered
        if (registered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback,
            )
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
            backAnimator?.reset()
        }
    }

    private fun restorePendingState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }
        if (savedInstanceState.getBoolean(STATE_PENDING_OVERRIDES_PRESENT, false)) {
            val processMode = try {
                ProcessMatchingMode.fromWireValue(
                    savedInstanceState.getString(STATE_PENDING_OVERRIDES_PROCESS_MODE),
                )
            } catch (exception: IllegalArgumentException) {
                ProcessMatchingMode.ALWAYS
            }
            val savedLogLevel = try {
                RuntimeLogLevel.fromWireValue(
                    savedInstanceState.getString(STATE_PENDING_OVERRIDES_LOG_LEVEL),
                )
            } catch (exception: IllegalArgumentException) {
                RuntimeLogLevel.WARNING
            }
            pendingRuntimeOverrides = RuntimeOverrideSettings(
                processMode,
                savedInstanceState.getBoolean(STATE_PENDING_OVERRIDES_IPV6, true),
                savedLogLevel,
                savedInstanceState.getBoolean(STATE_PENDING_OVERRIDES_ADAPTIVE_TCP, false),
                savedInstanceState.getBoolean(STATE_PENDING_OVERRIDES_WEBVIEW_XHTTP, false),
                savedInstanceState.getBoolean(STATE_PENDING_OVERRIDES_LAN_WEBUI, false),
            )
        }
        pendingIdentityPermissionStart = savedInstanceState.getBoolean(
            STATE_PENDING_IDENTITY_PERMISSION_START,
            false,
        )
        pendingIdentityPermissionAutomatic = savedInstanceState.getBoolean(
            STATE_PENDING_IDENTITY_PERMISSION_AUTOMATIC,
            false,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val requested = pendingRuntimeOverrides
        outState.putBoolean(STATE_PENDING_OVERRIDES_PRESENT, requested != null)
        if (requested != null) {
            outState.putString(
                STATE_PENDING_OVERRIDES_PROCESS_MODE,
                requested.processMatchingMode.wireValue,
            )
            outState.putBoolean(STATE_PENDING_OVERRIDES_IPV6, requested.ipv6Enabled)
            outState.putString(STATE_PENDING_OVERRIDES_LOG_LEVEL, requested.logLevel.wireValue)
            outState.putBoolean(
                STATE_PENDING_OVERRIDES_ADAPTIVE_TCP,
                requested.adaptiveTcpConcurrent,
            )
            outState.putBoolean(STATE_PENDING_OVERRIDES_WEBVIEW_XHTTP, requested.webViewXhttp)
            outState.putBoolean(STATE_PENDING_OVERRIDES_LAN_WEBUI, requested.lanWebUiPublic)
        }
        outState.putBoolean(
            STATE_PENDING_IDENTITY_PERMISSION_START,
            pendingIdentityPermissionStart,
        )
        outState.putBoolean(
            STATE_PENDING_IDENTITY_PERMISSION_AUTOMATIC,
            pendingIdentityPermissionAutomatic,
        )
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        controlClient.bind()
    }

    override fun onStop() {
        activityVisible = false
        controlClient.unbind()
        dashboard.release()
        super.onStop()
        if (!isChangingConfigurations && "$packageName:ui" == Application.getProcessName()) {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onDestroy() {
        setBackCallbackRegistered(false)
        backAnimator?.reset()
        backAnimator = null
        dashboard.release()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        dashboard.onTrimMemory(level, activityVisible)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            vpnController.handlePermissionResult(resultCode == RESULT_OK)
            return
        }
        if (requestCode == REQUEST_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            val source = ConfigPicker.persistReadPermission(this, data)
            if (source != null) {
                importConfig(source)
            }
            return
        }
        if (requestCode == REQUEST_DIAGNOSTICS_EXPORT && resultCode == RESULT_OK) {
            val destination = data?.data
            if (destination != null) {
                exportDiagnostics(destination)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NETWORK_IDENTITY_PERMISSIONS) {
            val shouldStart = pendingIdentityPermissionStart
            val automatic = pendingIdentityPermissionAutomatic
            pendingIdentityPermissionStart = false
            pendingIdentityPermissionAutomatic = false
            if (missingNetworkIdentityPermissions().isNotEmpty()) {
                showToast(getString(R.string.network_identity_permission_denied))
            }
            if (shouldStart) {
                vpnController.requestStart(runtimeState, automatic)
            }
            return
        }
        if (requestCode != REQUEST_LOCAL_NETWORK_PERMISSION) {
            return
        }
        val requested = pendingRuntimeOverrides ?: return
        pendingRuntimeOverrides = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            submitRuntimeOverrides(requested)
        } else {
            showToast(getString(R.string.local_network_permission_denied))
        }
    }

    override fun onRuntimeSnapshot(payload: RuntimeSnapshotPayload) {
        logLevel = payload.logLevel
        processMatchingMode = payload.processMatchingMode
        ipv6Enabled = payload.ipv6Enabled
        ipv6Effective = payload.ipv6Effective
        adaptiveTcpConcurrent = payload.adaptiveTcpConcurrent
        webViewXhttp = payload.webViewXhttp
        lanWebUiPublic = payload.lanWebUiPublic
        controllerPort = payload.controllerPort
        applySnapshot(
            RuntimeSnapshot(
                payload.state,
                payload.detail,
                payload.dashboardUrl,
                payload.controllerPort,
            ),
            payload.alwaysOn,
            payload.lockdown,
        )
    }

    override fun onControlDisconnected() {
        controllerPort = 0
        dashboard.release()
    }

    override fun onUploadConfig() {
        chooseConfig()
    }

    override fun onRestartRuntime() {
        controlClient.restart { success, detail ->
            showToast(
                if (success) {
                    getString(R.string.core_restarted)
                } else {
                    getString(R.string.core_restart_failed, detail)
                },
            )
        }
    }

    override fun onOpenRuntimeOverrides() {
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
        ) { requested -> applyRuntimeOverrides(requested) }
    }

    override fun onOpenNetworkNodes() {
        if (runtimeState != RuntimeState.RUNNING) {
            showToast("请先启动 VPN，再设置网络节点")
            return
        }
        showToast(getString(R.string.network_nodes_loading))
        controlClient.getNetworkSelectionCatalog { success, detail ->
            if (!success) {
                showToast(detail)
                return@getNetworkSelectionCatalog
            }
            NetworkNodesDialog.show(
                this,
                findViewById(R.id.more_actions),
                detail,
                networkNodesListener,
            )
        }
    }

    override fun onOpenVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (exception: RuntimeException) {
            showToast(getString(R.string.vpn_settings_failed))
        }
    }

    override fun onAddQuickSettingsTile() {
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            showToast(getString(R.string.quick_tile_add_failed))
            return
        }
        try {
            statusBar.requestAddTileService(
                ComponentName(this, VpnTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_vpn_key),
                mainExecutor,
            ) { result -> showToast(getString(quickTileResultMessage(result))) }
        } catch (exception: RuntimeException) {
            showToast(getString(R.string.quick_tile_add_failed))
        }
    }

    override fun onAutoStartChanged(enabled: Boolean) {
        preferences.setAutoStartEnabled(enabled)
        autoStartAttempted = false
        if (enabled) {
            maybeAutoStart()
        }
    }

    override fun onHideRecentsChanged(hidden: Boolean) {
        preferences.setHideFromRecents(hidden)
        taskVisibility.setHiddenFromRecents(hidden)
    }

    override fun onDiagnosticsChanged(enabled: Boolean) {
        preferences.setDiagnosticsEnabled(enabled)
        // The activity and the VPN service share this process, so the sampler
        // starts and stops immediately, with or without a running runtime.
        DiagnosticsSampler.setEnabled(this, enabled)
        showToast(
            getString(
                if (enabled) R.string.diagnostics_enabled else R.string.diagnostics_disabled,
            ),
        )
    }

    override fun onExportDiagnostics() {
        if (DiagnosticsLog.retainedBytes(this) == 0L) {
            showToast(getString(R.string.diagnostics_empty))
            return
        }
        val name = "androidcyaml-diagnostics-" + System.currentTimeMillis() + ".log"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            startActivityForResult(intent, REQUEST_DIAGNOSTICS_EXPORT)
        } catch (exception: RuntimeException) {
            showToast(
                getString(
                    R.string.diagnostics_export_failed,
                    Exceptions.usefulMessage(exception),
                ),
            )
        }
    }

    private fun exportDiagnostics(destination: Uri) {
        val application = applicationContext
        val worker = Thread({
            val outcome = try {
                val written = contentResolver.openOutputStream(destination)?.use { output ->
                    DiagnosticsLog.exportTo(application, output)
                }
                if (written == null) {
                    getString(R.string.diagnostics_export_failed, "no output stream")
                } else {
                    getString(R.string.diagnostics_exported, (written / 1024L).toString())
                }
            } catch (failure: IOException) {
                getString(R.string.diagnostics_export_failed, Exceptions.usefulMessage(failure))
            } catch (failure: RuntimeException) {
                getString(R.string.diagnostics_export_failed, Exceptions.usefulMessage(failure))
            }
            runOnUiThread { showToast(outcome) }
        }, "acy-diag-export")
        worker.isDaemon = true
        worker.start()
    }

    override fun onToggleRequested(checked: Boolean) {
        setVpnToggle(checked)
    }

    override fun onMessage(message: String) {
        showToast(message)
    }

    override fun onAutomaticPermissionDenied() {
        preferences.setAutoStartEnabled(false)
    }

    private fun applySnapshot(
        snapshot: RuntimeSnapshot,
        newAlwaysOn: Boolean,
        newLockdown: Boolean,
    ) {
        runtimeState = snapshot.state
        alwaysOn = newAlwaysOn
        lockdown = newLockdown
        runtimeDetail = if (snapshot.detail.isBlank()) {
            getString(R.string.core_stopped)
        } else {
            snapshot.detail
        }
        val transitional = runtimeState == RuntimeState.STARTING ||
            runtimeState == RuntimeState.STOPPING
        when (runtimeState) {
            RuntimeState.RUNNING -> vpnStatus.setText(
                if (lockdown) {
                    R.string.vpn_connected_lockdown
                } else {
                    R.string.vpn_connected_native_tun
                },
            )
            RuntimeState.STARTING -> vpnStatus.setText(R.string.vpn_starting)
            RuntimeState.STOPPING -> vpnStatus.setText(R.string.vpn_stopping)
            RuntimeState.FAILED -> vpnStatus.setText(R.string.vpn_failed)
            RuntimeState.STOPPED -> vpnStatus.setText(R.string.vpn_stopped)
        }

        val connected = runtimeState == RuntimeState.RUNNING ||
            runtimeState == RuntimeState.STARTING ||
            runtimeState == RuntimeState.STOPPING
        setVpnToggle(connected)
        vpnToggle.isEnabled = !transitional && !(alwaysOn && connected)
        if (activityVisible &&
            runtimeState == RuntimeState.RUNNING &&
            snapshot.dashboardUrl.isNotBlank() &&
            snapshot.controllerPort > 0
        ) {
            dashboard.load(snapshot.dashboardUrl, snapshot.controllerPort)
        } else {
            dashboard.release()
        }
        maybeAutoStart()
    }

    private fun onVpnToggleChanged(checked: Boolean) {
        if (updatingVpnToggle) {
            return
        }
        if (checked) {
            requestVpnStart(false)
        } else {
            vpnController.requestStop(alwaysOn)
        }
    }

    private fun showActions(anchor: View) {
        MainActionsMenu.show(
            this,
            anchor,
            preferences.autoStartEnabled(),
            preferences.hideFromRecents(),
            preferences.diagnosticsEnabled(),
            DiagnosticsLog.retainedBytes(this) > 0L,
            this,
        )
    }

    private fun showRuntimeInfo() {
        val details = StringBuilder(
            getString(R.string.runtime_info_app, BuildConfig.VERSION_NAME),
        )
        for (detail in runtimeDetail.split(Regex("\\s+·\\s+"))) {
            if (detail.isNotBlank()) {
                details.append('\n').append(detail)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.runtime_info)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.popup_menu_background)
        dialog.show()
    }

    private fun chooseConfig() {
        try {
            ConfigPicker.open(this, REQUEST_CONFIG_FILE)
        } catch (exception: RuntimeException) {
            showToast(
                getString(R.string.config_import_failed, Exceptions.usefulMessage(exception)),
            )
        }
    }

    private fun importConfig(source: Uri) {
        controlClient.importConfig(source) { success, detail ->
            showToast(
                if (success) {
                    getString(R.string.config_imported)
                } else {
                    getString(R.string.config_import_failed, detail)
                },
            )
        }
    }

    private fun applyRuntimeOverrides(requested: RuntimeOverrideSettings) {
        if (requested.lanWebUiPublic &&
            Build.VERSION.SDK_INT >= ANDROID_17_API &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRuntimeOverrides = requested
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
                REQUEST_LOCAL_NETWORK_PERMISSION,
            )
            return
        }
        submitRuntimeOverrides(requested)
    }

    private fun submitRuntimeOverrides(requested: RuntimeOverrideSettings) {
        pendingRuntimeOverrides = null
        controlClient.setRuntimeOverrides(
            requested.processMatchingMode,
            requested.ipv6Enabled,
            requested.logLevel,
            requested.adaptiveTcpConcurrent,
            requested.webViewXhttp,
            requested.lanWebUiPublic,
        ) { success, detail ->
            showToast(
                if (success) {
                    getString(R.string.runtime_override_applied, detail)
                } else {
                    getString(R.string.runtime_override_failed, detail)
                },
            )
        }
    }

    private fun maybeAutoStart() {
        if (autoStartAttempted || !preferences.autoStartEnabled()) {
            return
        }
        if (runtimeState != RuntimeState.STOPPED && runtimeState != RuntimeState.FAILED) {
            return
        }
        autoStartAttempted = true
        requestVpnStart(true)
    }

    private fun requestVpnStart(automatic: Boolean) {
        val missing = missingNetworkIdentityPermissions()
        if (missing.isNotEmpty() && !preferences.networkIdentityPermissionRequested()) {
            preferences.setNetworkIdentityPermissionRequested()
            pendingIdentityPermissionStart = true
            pendingIdentityPermissionAutomatic = automatic
            try {
                requestPermissions(
                    missing.toTypedArray(),
                    REQUEST_NETWORK_IDENTITY_PERMISSIONS,
                )
                return
            } catch (exception: RuntimeException) {
                pendingIdentityPermissionStart = false
                pendingIdentityPermissionAutomatic = false
                showToast(getString(R.string.network_identity_permission_denied))
            }
        }
        vpnController.requestStart(runtimeState, automatic)
    }

    private fun missingNetworkIdentityPermissions(): List<String> {
        val missing = ArrayList<String>(2)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return missing
    }

    private fun setVpnToggle(checked: Boolean) {
        updatingVpnToggle = true
        vpnToggle.isChecked = checked
        updatingVpnToggle = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val ACTION_IMPORT_CONFIG = "io.github.qwqgong.androidcyaml.action.IMPORT_CONFIG"

        const val REQUEST_VPN_PERMISSION = 10_001
        const val REQUEST_CONFIG_FILE = 10_002
        const val REQUEST_LOCAL_NETWORK_PERMISSION = 10_003
        const val REQUEST_NETWORK_IDENTITY_PERMISSIONS = 10_004
        const val REQUEST_DIAGNOSTICS_EXPORT = 10_005
        const val ANDROID_17_API = 37

        const val STATE_PENDING_OVERRIDES_PRESENT = "pending_overrides_present"
        const val STATE_PENDING_OVERRIDES_PROCESS_MODE = "pending_overrides_process_mode"
        const val STATE_PENDING_OVERRIDES_IPV6 = "pending_overrides_ipv6"
        const val STATE_PENDING_OVERRIDES_LOG_LEVEL = "pending_overrides_log_level"
        const val STATE_PENDING_OVERRIDES_ADAPTIVE_TCP = "pending_overrides_adaptive_tcp"
        const val STATE_PENDING_OVERRIDES_WEBVIEW_XHTTP = "pending_overrides_webview_xhttp"
        const val STATE_PENDING_OVERRIDES_LAN_WEBUI = "pending_overrides_lan_webui"
        const val STATE_PENDING_IDENTITY_PERMISSION_START = "pending_identity_permission_start"
        const val STATE_PENDING_IDENTITY_PERMISSION_AUTOMATIC =
            "pending_identity_permission_automatic"

        fun quickTileResultMessage(result: Int): Int = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.quick_tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                R.string.quick_tile_already_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                R.string.quick_tile_not_added
            else -> R.string.quick_tile_add_failed
        }
    }
}
