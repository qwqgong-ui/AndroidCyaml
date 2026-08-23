package io.github.qwqgong.androidcyaml

import android.app.Activity
import android.content.Intent
import android.net.VpnService

class VpnUiController(
    private val activity: Activity,
    private val permissionRequestCode: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onToggleRequested(checked: Boolean)

        fun onMessage(message: String)

        fun onAutomaticPermissionDenied()
    }

    private var automaticPermissionRequest = false

    fun requestStart(state: RuntimeState, automatic: Boolean) {
        if (state == RuntimeState.STARTING || state == RuntimeState.RUNNING) {
            return
        }
        try {
            val permission = VpnService.prepare(activity)
            if (permission == null) {
                automaticPermissionRequest = false
                startService()
                return
            }
            automaticPermissionRequest = automatic
            activity.startActivityForResult(permission, permissionRequestCode)
        } catch (exception: RuntimeException) {
            automaticPermissionRequest = false
            listener.onToggleRequested(false)
            listener.onMessage(activity.getString(R.string.vpn_permission_failed))
            if (automatic) {
                listener.onAutomaticPermissionDenied()
            }
        }
    }

    fun handlePermissionResult(granted: Boolean) {
        val automatic = automaticPermissionRequest
        automaticPermissionRequest = false
        if (granted) {
            startService()
            return
        }
        listener.onToggleRequested(false)
        if (automatic) {
            listener.onAutomaticPermissionDenied()
            listener.onMessage(activity.getString(R.string.auto_start_vpn_permission_denied))
        } else {
            listener.onMessage(activity.getString(R.string.vpn_permission_denied))
        }
    }

    fun requestStop(alwaysOn: Boolean) {
        if (alwaysOn) {
            listener.onToggleRequested(true)
            listener.onMessage(activity.getString(R.string.vpn_always_on_controlled))
            return
        }
        try {
            activity.startService(
                Intent(activity, AndroidVpnService::class.java)
                    .setAction(AndroidVpnService.ACTION_STOP),
            )
        } catch (exception: RuntimeException) {
            listener.onToggleRequested(true)
            listener.onMessage(
                activity.getString(
                    R.string.vpn_start_failed,
                    Exceptions.usefulMessage(exception),
                ),
            )
        }
    }

    private fun startService() {
        try {
            activity.startForegroundService(
                Intent(activity, AndroidVpnService::class.java)
                    .setAction(AndroidVpnService.ACTION_START)
                    .putExtra(AndroidVpnService.EXTRA_FOREGROUND_START, true),
            )
            listener.onToggleRequested(true)
        } catch (exception: RuntimeException) {
            listener.onToggleRequested(false)
            listener.onMessage(
                activity.getString(
                    R.string.vpn_start_failed,
                    Exceptions.usefulMessage(exception),
                ),
            )
        }
    }
}
