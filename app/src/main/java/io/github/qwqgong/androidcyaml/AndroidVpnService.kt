package io.github.qwqgong.androidcyaml

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.util.Log

class AndroidVpnService :
    VpnService(),
    RuntimeStateBus.Listener,
    VpnPlatformHost {

    private lateinit var coordinator: RuntimeCoordinator

    @Volatile
    private var stopping = false

    @Volatile
    private var foregroundActive = false

    private var activeForegroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED

    override fun onCreate() {
        super.onCreate()
        coordinator = RuntimeCoordinator.getInstance(this)
        coordinator.addListener(this)
        createNotificationChannel()
        updateManagedMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        updateManagedMode()
        if (ACTION_STOP == action) {
            if (sharedAlwaysOn) {
                updateNotification(getString(R.string.vpn_always_on_controlled))
                return START_STICKY
            }
            requestStop()
            return START_NOT_STICKY
        }

        stopping = false
        try {
            val foregroundStart =
                intent != null && intent.getBooleanExtra(EXTRA_FOREGROUND_START, false)
            enterForeground(
                buildNotification(getString(R.string.vpn_starting)),
                foregroundServiceTypes(
                    hasFineLocationPermission(),
                    hasCoarseLocationPermission(),
                    foregroundStart,
                ),
            )
            foregroundActive = true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Unable to enter foreground mode", exception)
            stopSelf()
            return START_NOT_STICKY
        }
        coordinator.start(this)
        return START_STICKY
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        coordinator.removeListener(this)
        if (!stopping) {
            coordinator.stop(this, null)
        }
        // Without this reset, a destroyed service leaves its last known
        // always-on/lockdown state visible to isAlwaysOnMode()/isLockdownMode()
        // forever, which AppControlService.sendSnapshot() reads even when no
        // AndroidVpnService instance is alive.
        sharedAlwaysOn = false
        sharedLockdown = false
        super.onDestroy()
    }

    override fun onRuntimeStateChanged(snapshot: RuntimeSnapshot) {
        updateManagedMode()
        if (!foregroundActive) {
            return
        }
        when (snapshot.state) {
            RuntimeState.STARTING -> updateNotification(getString(R.string.vpn_starting))
            RuntimeState.RUNNING ->
                updateNotification(getString(R.string.vpn_connected_native_tun))
            RuntimeState.STOPPING -> updateNotification(getString(R.string.vpn_stopping))
            RuntimeState.FAILED -> updateNotification(snapshot.detail)
            RuntimeState.STOPPED -> updateNotification(getString(R.string.vpn_stopped))
        }
    }

    override fun platformContext(): Context = this

    override fun newPlatformBuilder(): Builder = Builder()

    override fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun onCoordinatorFailure(message: String) {
        updateNotification(message)
        stopping = true
        foregroundActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestStop() {
        if (stopping) {
            return
        }
        stopping = true
        updateNotification(getString(R.string.vpn_stopping))
        coordinator.stop(this) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateManagedMode() {
        try {
            sharedAlwaysOn = isAlwaysOn
            sharedLockdown = isLockdownEnabled
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to read system VPN mode", exception)
            sharedAlwaysOn = false
            sharedLockdown = false
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.vpn_notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
        if (!sharedAlwaysOn) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, AndroidVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.mipmap.ic_launcher),
                    getString(R.string.stop_vpn),
                    stop,
                ).build(),
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        if (!foregroundActive) {
            return
        }
        try {
            // Updating the existing foreground-service notification through
            // startForeground does not turn VPN startup into a notification
            // permission gate on Android 13+.
            startForeground(NOTIFICATION_ID, buildNotification(text), activeForegroundServiceTypes)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to update foreground notification", exception)
        }
    }

    private fun enterForeground(notification: Notification, serviceTypes: Int) {
        try {
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
            activeForegroundServiceTypes = serviceTypes
        } catch (locationFailure: SecurityException) {
            if (serviceTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0) {
                throw locationFailure
            }
            Log.w(
                TAG,
                "Location foreground-service access unavailable; " +
                    "continuing VPN without background Wi-Fi identity",
                locationFailure,
            )
            activeForegroundServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            startForeground(NOTIFICATION_ID, notification, activeForegroundServiceTypes)
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "io.github.qwqgong.androidcyaml.action.START_VPN"
        const val ACTION_STOP = "io.github.qwqgong.androidcyaml.action.STOP_VPN"
        const val EXTRA_FOREGROUND_START =
            "io.github.qwqgong.androidcyaml.extra.FOREGROUND_START"

        private const val TAG = "AndroidCyaml/VPN"
        private const val NOTIFICATION_CHANNEL = "androidcyaml_vpn"
        private const val NOTIFICATION_ID = 36

        @Volatile
        private var sharedAlwaysOn = false

        @Volatile
        private var sharedLockdown = false

        fun isAlwaysOnMode(): Boolean = sharedAlwaysOn

        fun isLockdownMode(): Boolean = sharedLockdown

        fun foregroundServiceTypes(
            fineLocationGranted: Boolean,
            coarseLocationGranted: Boolean,
            foregroundStart: Boolean,
        ): Int {
            var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            if (fineLocationGranted && foregroundStart) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            return serviceTypes
        }
    }
}
