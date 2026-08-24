package io.github.qwqgong.androidcyaml

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point for the launcher shortcuts and for the quick settings
 * tile when starting needs the system VPN consent dialog, which only an Activity
 * can host. It performs one action and finishes without ever showing the app.
 */
@Suppress("DEPRECATION")
class QuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // A consent dialog is already in flight; wait for its result.
            return
        }
        when (intent?.action) {
            ACTION_START_VPN -> requestStart()
            ACTION_STOP_VPN -> requestStop()
            else -> finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) {
            finish()
            return
        }
        if (resultCode == RESULT_OK) {
            startService()
        } else {
            toast(getString(R.string.vpn_permission_denied))
        }
        finish()
    }

    private fun requestStart() {
        val consent = try {
            VpnService.prepare(this)
        } catch (exception: RuntimeException) {
            toast(getString(R.string.vpn_permission_failed))
            finish()
            return
        }
        if (consent == null) {
            startService()
            finish()
            return
        }
        try {
            startActivityForResult(consent, REQUEST_VPN_PERMISSION)
        } catch (exception: RuntimeException) {
            toast(getString(R.string.vpn_permission_failed))
            finish()
        }
    }

    private fun requestStop() {
        if (AndroidVpnService.isAlwaysOnMode()) {
            toast(getString(R.string.vpn_always_on_controlled))
        } else if (!VpnQuickActions.stopService(this)) {
            toast(getString(R.string.quick_action_failed))
        }
        finish()
    }

    private fun startService() {
        if (VpnQuickActions.startService(this, true)) {
            toast(getString(R.string.vpn_starting))
        } else {
            toast(getString(R.string.quick_action_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_START_VPN = "io.github.qwqgong.androidcyaml.action.QUICK_START_VPN"
        const val ACTION_STOP_VPN = "io.github.qwqgong.androidcyaml.action.QUICK_STOP_VPN"

        private const val REQUEST_VPN_PERMISSION = 20_001
    }
}
