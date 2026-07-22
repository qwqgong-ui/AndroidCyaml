package io.github.qwqgong.androidcyaml;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class AndroidVpnService extends VpnService {
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
    }

    public interface Listener {
        void onVpnStateChanged(State state, String detail);
    }

    public static final String ACTION_START =
            "io.github.qwqgong.androidcyaml.action.START_VPN";
    public static final String ACTION_STOP =
            "io.github.qwqgong.androidcyaml.action.STOP_VPN";

    private static final String TAG = "AndroidCyaml/VPN";
    private static final String NOTIFICATION_CHANNEL = "androidcyaml_vpn";
    private static final int NOTIFICATION_ID = 36;
    private static final int MTU = 9000;
    private static final String IPV4_ADDRESS = "198.18.0.1";
    private static final String IPV4_DNS = "198.18.0.2";
    private static final String IPV6_ADDRESS = "fdfe:dcba:9876::1";
    private static final String IPV6_DNS = "fdfe:dcba:9876::2";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile State sharedState = State.STOPPED;
    private static volatile String sharedDetail = "VPN 未连接";

    private MihomoManager manager;
    private ParcelFileDescriptor tunnel;
    private long generation;
    private boolean stopping;
    private boolean tunnelReady;

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
        MAIN_HANDLER.post(() -> listener.onVpnStateChanged(sharedState, sharedDetail));
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        manager = MihomoManager.getInstance(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn("VPN 已断开");
            return START_NOT_STICKY;
        }
        if (stopping) {
            return START_NOT_STICKY;
        }

        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("正在建立 VPN…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            );
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to enter VPN foreground mode", exception);
            publish(State.FAILED, "系统拒绝启动 VPN 前台服务：" + usefulMessage(exception));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel == null) {
            startVpn();
        } else if (tunnelReady) {
            publish(State.RUNNING, "VPN 已连接");
        } else {
            publish(State.STARTING, "正在等待 mihomo 接管 VPN…");
        }
        return START_STICKY;
    }

    private void startVpn() {
        long requestGeneration = ++generation;
        tunnelReady = false;
        publish(State.STARTING, "正在建立系统 VPN 接口…");
        try {
            Builder builder = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(MTU)
                    .addAddress(IPV4_ADDRESS, 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(IPV4_DNS)
                    .addAddress(IPV6_ADDRESS, 126)
                    .addRoute("::", 0)
                    .addDnsServer(IPV6_DNS)
                    .setBlocking(true)
                    .setMetered(false)
                    .setConfigureIntent(openAppPendingIntent());

            // The mihomo child process has this app's UID. Excluding our own
            // package keeps its upstream sockets outside the VPN and prevents
            // a routing loop; every other application is captured by default.
            builder.addDisallowedApplication(getPackageName());
            ParcelFileDescriptor established = builder.establish();
            if (established == null) {
                throw new IOException("系统未能建立 VPN TUN 接口");
            }
            tunnel = established;
            manager.activateVpn(established, (success, detail) -> {
                if (requestGeneration != generation || stopping) {
                    return;
                }
                if (success) {
                    tunnelReady = true;
                    publish(State.RUNNING, "VPN 已连接 · 全局 IPv4/IPv6");
                    updateNotification("VPN 已连接");
                } else {
                    failAndStop("mihomo 无法接管 VPN：" + detail);
                }
            });
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException exception) {
            Log.e(TAG, "Unable to establish VPN", exception);
            failAndStop(usefulMessage(exception));
        }
    }

    private void stopVpn(String message) {
        if (stopping) {
            return;
        }
        stopping = true;
        tunnelReady = false;
        ++generation;
        publish(State.STARTING, "正在断开 VPN…");
        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        manager.deactivateVpn(currentTunnel, () -> {
            closeTunnel(currentTunnel);
            publish(State.STOPPED, message);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void failAndStop(String message) {
        publish(State.FAILED, message);
        updateNotification("VPN 启动失败");
        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        tunnelReady = false;
        stopping = true;
        ++generation;
        manager.deactivateVpn(currentTunnel, () -> {
            closeTunnel(currentTunnel);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    @Override
    public void onRevoke() {
        stopVpn("VPN 授权已被系统撤销");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        ++generation;
        stopping = true;
        tunnelReady = false;
        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        if (currentTunnel != null) {
            manager.deactivateVpn(currentTunnel, () -> closeTunnel(currentTunnel));
            publish(State.STOPPED, "VPN 已停止");
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.vpn_notification_channel_description));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, AndroidVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(text)
                .setContentIntent(openAppPendingIntent())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(
                        new Notification.Action.Builder(
                                Icon.createWithResource(this, R.drawable.ic_vpn_key),
                                getString(R.string.stop_vpn),
                                stopIntent
                        ).build()
                )
                .build();
    }

    private PendingIntent openAppPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void updateNotification(String text) {
        startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        );
    }

    private void publish(State state, String detail) {
        sharedState = state;
        sharedDetail = detail;
        MAIN_HANDLER.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onVpnStateChanged(sharedState, sharedDetail);
            }
        });
    }

    private static void closeTunnel(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException exception) {
            Log.w(TAG, "Unable to close VPN TUN descriptor", exception);
        }
    }

    private static String usefulMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
