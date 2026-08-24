package io.github.qwqgong.androidcyaml

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu

object MainActionsMenu {
    interface Listener {
        fun onUploadConfig()

        fun onRestartRuntime()

        fun onOpenRuntimeOverrides()

        fun onOpenNetworkNodes()

        fun onOpenVpnSettings()

        fun onAddQuickSettingsTile()

        fun onAutoStartChanged(enabled: Boolean)

        fun onHideRecentsChanged(hidden: Boolean)
    }

    private const val UPLOAD = 1
    private const val RESTART = 2
    private const val RUNTIME_OVERRIDES = 3
    private const val VPN_SETTINGS = 4
    private const val AUTO_START = 5
    private const val HIDE_RECENTS = 6
    private const val NETWORK_NODES = 7
    private const val ADD_QUICK_TILE = 8

    fun show(
        context: Context,
        anchor: View,
        autoStartEnabled: Boolean,
        hiddenFromRecents: Boolean,
        listener: Listener,
    ) {
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        menu.add(Menu.NONE, UPLOAD, 0, R.string.upload_config)
        menu.add(Menu.NONE, RESTART, 1, R.string.restart_core)
        menu.add(Menu.NONE, RUNTIME_OVERRIDES, 2, R.string.runtime_overrides)
        menu.add(Menu.NONE, NETWORK_NODES, 3, R.string.network_nodes)
        menu.add(Menu.NONE, VPN_SETTINGS, 4, R.string.vpn_system_settings)
        menu.add(Menu.NONE, ADD_QUICK_TILE, 5, R.string.add_quick_tile)
        menu.add(Menu.NONE, AUTO_START, 6, R.string.auto_start_vpn)
            .setCheckable(true)
            .isChecked = autoStartEnabled
        menu.add(Menu.NONE, HIDE_RECENTS, 7, R.string.hide_from_recents)
            .setCheckable(true)
            .isChecked = hiddenFromRecents
        popup.setOnMenuItemClickListener { item -> handle(item, listener) }
        popup.show()
    }

    private fun handle(item: MenuItem, listener: Listener): Boolean {
        when (item.itemId) {
            UPLOAD -> listener.onUploadConfig()
            RESTART -> listener.onRestartRuntime()
            RUNTIME_OVERRIDES -> listener.onOpenRuntimeOverrides()
            NETWORK_NODES -> listener.onOpenNetworkNodes()
            VPN_SETTINGS -> listener.onOpenVpnSettings()
            ADD_QUICK_TILE -> listener.onAddQuickSettingsTile()
            AUTO_START -> {
                val enabled = !item.isChecked
                item.isChecked = enabled
                listener.onAutoStartChanged(enabled)
            }
            HIDE_RECENTS -> {
                val hidden = !item.isChecked
                item.isChecked = hidden
                listener.onHideRecentsChanged(hidden)
            }
            else -> return false
        }
        return true
    }
}
