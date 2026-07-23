package io.github.qwqgong.androidcyaml;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.util.Log;

public final class AndroidVpnService extends VpnService implements
        RuntimeStateBus.Listener,
        VpnPlatformHost {
    public static final String ACTION_START =
            "io.github.qwqgong.androidcyaml.action.START_VPN";
    public static final String ACTION_STOP =
            "io.github.qwqgong.androidcyaml.action.STOP_VPN";

    private static final String TAG = "AndroidCyaml/VPN";
    private static final String NOTIFICATION_CHANNEL = "androidcyaml_vpn";
    private static final int NOTIFICATION_ID = 36;

    private static volatile boolean sharedAlwaysOn;
    private static volatile boolean sharedLockdown;

    private RuntimeCoordinator coordinator;
    private volatile boolean stopping;
    private volatile boolean foregroundActive;

    public static boolean isAlwaysOnMode() {
        return sharedAlwaysOn;
    }

    public static boolean isLockdownMode() {
        return sharedLockdown;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        coordinator = RuntimeCoordinator.getInstance(this);
        coordinator.addListener(this);
        createNotificationChannel();
        updateManagedMode();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        updateManagedMode();
        if (ACTION_STOP.equals(action)) {
            if (sharedAlwaysOn) {
                updateNotification(getString(R.string.vpn_always_on_controlled));
                return START_STICKY;
            }
            requestStop();
            return START_NOT_STICKY;
        }

        stopping = false;
        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.vpn_starting)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            );
            foregroundActive = true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to enter foreground mode", exception);
            stopSelf();
            return START_NOT_STICKY;
        }
        coordinator.start(this);
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        requestStop();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        coordinator.removeListener(this);
        if (!stopping) {
            coordinator.stop(this, null);
        }
        super.onDestroy();
    }

    @Override
    public void onRuntimeStateChanged(RuntimeSnapshot snapshot) {
        updateManagedMode();
        if (!foregroundActive) {
            return;
        }
        switch (snapshot.state()) {
            case STARTING -> updateNotification(getString(R.string.vpn_starting));
            case RUNNING -> updateNotification(getString(R.string.vpn_connected_native_tun));
            case STOPPING -> updateNotification(getString(R.string.vpn_stopping));
            case FAILED -> updateNotification(snapshot.detail());
            case STOPPED -> updateNotification(getString(R.string.vpn_stopped));
        }
    }

    @Override
    public Context platformContext() {
        return this;
    }

    @Override
    public Builder newPlatformBuilder() {
        return new Builder();
    }

    @Override
    public PendingIntent openAppPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    void onCoordinatorFailure(String message) {
        updateNotification(message);
        stopping = true;
        foregroundActive = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void requestStop() {
        if (stopping) {
            return;
        }
        stopping = true;
        updateNotification(getString(R.string.vpn_stopping));
        coordinator.stop(this, () -> {
            foregroundActive = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void updateManagedMode() {
        try {
            sharedAlwaysOn = isAlwaysOn();
            sharedLockdown = isLockdownEnabled();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read system VPN mode", exception);
            sharedAlwaysOn = false;
            sharedLockdown = false;
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.vpn_notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(text)
                .setContentIntent(openAppPendingIntent())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true);
        if (!sharedAlwaysOn) {
            PendingIntent stop = PendingIntent.getService(
                    this,
                    1,
                    new Intent(this, AndroidVpnService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.mipmap.ic_launcher),
                    getString(R.string.stop_vpn),
                    stop
            ).build());
        }
        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && foregroundActive) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
