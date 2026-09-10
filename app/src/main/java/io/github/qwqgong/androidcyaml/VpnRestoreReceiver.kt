package io.github.qwqgong.androidcyaml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/** Restore only a previously requested, still-authorized VPN session. */
class VpnRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!VpnConnectionRequest.isRequested(context)) return
        try {
            // Revoked VPN consent must never cause an automatic consent UI.
            if (VpnService.prepare(context) != null) return
            VpnQuickActions.startServiceOrThrow(context, false)
        } catch (failure: RuntimeException) {
            Log.w("AndroidCyaml/Restore", "System refused VPN restoration", failure)
        }
    }
}
