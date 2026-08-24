package io.github.qwqgong.androidcyaml

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Start and stop paths shared by the quick settings tile, the launcher
 * shortcuts and the main UI. Callers outside an Activity cannot host the system
 * VPN consent dialog, so [start] reports that case instead of failing.
 */
object VpnQuickActions {
    enum class StartResult {
        STARTED,
        NEEDS_CONSENT,
        FAILED,
    }

    fun start(context: Context, foregroundStart: Boolean): StartResult {
        val consent = try {
            VpnService.prepare(context)
        } catch (exception: RuntimeException) {
            return StartResult.FAILED
        }
        if (consent != null) {
            return StartResult.NEEDS_CONSENT
        }
        return if (startService(context, foregroundStart)) {
            StartResult.STARTED
        } else {
            StartResult.FAILED
        }
    }

    /**
     * Requests the VPN foreground service. [foregroundStart] must only be true
     * when the caller is itself visible, because it is what lets the service ask
     * for the location foreground-service type.
     */
    fun startServiceOrThrow(context: Context, foregroundStart: Boolean) {
        context.startForegroundService(
            Intent(context, AndroidVpnService::class.java)
                .setAction(AndroidVpnService.ACTION_START)
                .putExtra(AndroidVpnService.EXTRA_FOREGROUND_START, foregroundStart),
        )
    }

    fun startService(context: Context, foregroundStart: Boolean): Boolean = try {
        startServiceOrThrow(context, foregroundStart)
        true
    } catch (exception: RuntimeException) {
        false
    }

    /** Always-on VPN is enforced by the system; the service ignores stops then. */
    fun stopService(context: Context): Boolean = try {
        context.startService(
            Intent(context, AndroidVpnService::class.java)
                .setAction(AndroidVpnService.ACTION_STOP),
        )
        true
    } catch (exception: RuntimeException) {
        false
    }
}
