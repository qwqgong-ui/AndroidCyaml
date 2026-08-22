package io.github.qwqgong.androidcyaml

import android.content.Context
import android.content.SharedPreferences

class UiPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun autoStartEnabled(): Boolean = preferences.getBoolean(AUTO_START_KEY, false)

    fun setAutoStartEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(AUTO_START_KEY, enabled).apply()
    }

    fun hideFromRecents(): Boolean = preferences.getBoolean(HIDE_RECENTS_KEY, false)

    fun setHideFromRecents(hidden: Boolean) {
        preferences.edit().putBoolean(HIDE_RECENTS_KEY, hidden).apply()
    }

    fun networkIdentityPermissionRequested(): Boolean =
        preferences.getBoolean(NETWORK_IDENTITY_PERMISSION_REQUESTED, false)

    fun setNetworkIdentityPermissionRequested() {
        preferences.edit().putBoolean(NETWORK_IDENTITY_PERMISSION_REQUESTED, true).apply()
    }

    private companion object {
        const val PREFERENCES = "androidcyaml_ui"
        const val AUTO_START_KEY = "auto_start_vpn"
        const val HIDE_RECENTS_KEY = "hide_from_recents"
        const val NETWORK_IDENTITY_PERMISSION_REQUESTED = "network_identity_permission_requested"
    }
}
