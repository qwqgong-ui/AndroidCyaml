package io.github.qwqgong.androidcyaml

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick settings toggle for the VPN. It observes the same control service the
 * main UI uses, so the tile tracks the runtime instead of guessing from its own
 * clicks.
 */
class VpnTileService : TileService(), RuntimeControlClient.Listener {

    private var controlClient: RuntimeControlClient? = null
    private var state = RuntimeState.STOPPED
    private var alwaysOn = false
    private var lockdown = false

    override fun onStartListening() {
        super.onStartListening()
        val client = controlClient ?: RuntimeControlClient(this, this).also { controlClient = it }
        render()
        client.bind()
    }

    override fun onStopListening() {
        controlClient?.unbind()
        super.onStopListening()
    }

    override fun onDestroy() {
        controlClient?.unbind()
        controlClient = null
        super.onDestroy()
    }

    override fun onRuntimeSnapshot(payload: RuntimeSnapshotPayload) {
        state = payload.state
        alwaysOn = payload.alwaysOn
        lockdown = payload.lockdown
        render()
    }

    override fun onControlDisconnected() {
        state = RuntimeState.STOPPED
        lockdown = false
        render()
    }

    override fun onClick() {
        when {
            // Always-on is owned by the system; send the user where it lives
            // rather than pretending the tile can turn it off.
            alwaysOn && active() -> collapseInto(Intent(Settings.ACTION_VPN_SETTINGS))
            active() -> {
                if (VpnQuickActions.stopService(this)) {
                    state = RuntimeState.STOPPING
                    render()
                }
            }
            else -> requestStart()
        }
    }

    private fun requestStart() {
        // The tile is not a visible caller, so it must not claim a foreground
        // start, and it cannot host the consent dialog either.
        if (VpnQuickActions.start(this, false) == VpnQuickActions.StartResult.STARTED) {
            state = RuntimeState.STARTING
            render()
            return
        }
        collapseInto(
            Intent(this, QuickActionActivity::class.java)
                .setAction(QuickActionActivity.ACTION_START_VPN),
        )
    }

    private fun collapseInto(intent: Intent) {
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    }

    private fun active(): Boolean =
        state == RuntimeState.RUNNING ||
            state == RuntimeState.STARTING ||
            state == RuntimeState.STOPPING

    private fun render() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
        tile.label = getString(R.string.app_name)
        when (state) {
            RuntimeState.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(
                    if (lockdown) {
                        R.string.vpn_connected_lockdown
                    } else {
                        R.string.vpn_connected_native_tun
                    },
                )
            }
            RuntimeState.STARTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.vpn_starting)
            }
            RuntimeState.STOPPING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.vpn_stopping)
            }
            RuntimeState.FAILED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.vpn_failed)
            }
            RuntimeState.STOPPED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.vpn_stopped)
            }
        }
        tile.updateTile()
    }
}
