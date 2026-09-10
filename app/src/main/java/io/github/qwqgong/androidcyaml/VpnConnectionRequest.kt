package io.github.qwqgong.androidcyaml

import android.content.Context

/** The user's last explicit connection intent, shared by recovery entry points. */
object VpnConnectionRequest {
    fun isRequested(context: Context): Boolean =
        context.getSharedPreferences("vpn_lifecycle", Context.MODE_PRIVATE)
            .getBoolean("connection_requested", false)

    fun setRequested(context: Context, requested: Boolean): Boolean =
        context.getSharedPreferences("vpn_lifecycle", Context.MODE_PRIVATE).edit()
            .putBoolean("connection_requested", requested).commit()
}
