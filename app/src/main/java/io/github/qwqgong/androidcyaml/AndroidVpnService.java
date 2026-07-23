package io.github.qwqgong.androidcyaml;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AndroidVpnService extends VpnService implements MihomoManager.Listener {
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
    public static final String ACTION_REFRESH =
            "io.github.qwqgong.androidcyaml.action.REFRESH_VPN_STATE";

    private static final String TAG = "AndroidCyaml/VPN";
    private static final String NOTIFICATION_CHANNEL = "androidcyaml_vpn";
    private static final int NOTIFICATION_ID = 36;

    // VpnService.Builder is the sole owner of the Android interface profile.
    // These values are never read from or written back to config.yaml.
    private static final int MTU = 9000;
    private static final String IPV4_ADDRESS = "198.18.0.1";
    private static final int IPV4_PREFIX = 30;
    private static final String DNS_IPV4_ADDRESS = "8.8.8.8";
    private static final String IPV6_ADDRESS = "fdfe:dcba:9876::1";
    private static final int IPV6_PREFIX = 126;
    private static final String DNS_IPV6_ADDRESS = "2001:4860:4860::8888";

    private static final String INTERNAL_SOCKS_HOST = "127.0.0.1";
    private static final String INTERNAL_SOCKS_USER = "androidcyaml";
    private static final int INTERNAL_SOCKS_PORT_BASE = 20_000;
    private static final int INTERNAL_SOCKS_PORT_SPAN = 20_000;
    private static final long SOCKS_READY_TIMEOUT_SECONDS = 90;

    // Keep these names synchronized with MihomoManager. Its constructor creates
    // the per-install 256-bit secret before the VPN service starts the core.
    private static final String CORE_PREFERENCES = "androidcyaml";
    private static final String CONTROLLER_SECRET_KEY = "controller_secret";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile State sharedState = State.STOPPED;
    private static volatile String sharedDetail = "VPN 未连接";
    private static volatile boolean sharedAlwaysOn;
    private static volatile boolean sharedLockdown;

    private final ExecutorService vpnExecutor = Executors.newSingleThreadExecutor();

    private MihomoManager manager;
    private volatile ParcelFileDescriptor tunnel;
    private volatile long generation;
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile boolean tunnelReady;
    private volatile boolean coreRestartPending;

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
        MAIN_HANDLER.post(() -> listener.onVpnStateChanged(sharedState, sharedDetail));
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isAlwaysOnMode() {
        return sharedAlwaysOn;
    }

    public static boolean isLockdownMode() {
        return sharedLockdown;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        manager = MihomoManager.getInstance(this);
        manager.addListener(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (isAlwaysOn()) {
                Log.i(TAG, "Ignoring app stop request while always-on VPN is enabled");
            } else {
                stopVpn("VPN 已断开");
                return START_NOT_STICKY;
            }
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

        if (tunnelReady) {
            publish(State.RUNNING, runningDetail());
        } else if (!starting) {
            startVpn();
        } else {
            publish(State.STARTING, "正在等待 HEV SOCKS5 隧道接管 VPN…");
        }
        return START_STICKY;
    }

    private void startVpn() {
        long requestGeneration = ++generation;
        starting = true;
        stopping = false;
        tunnelReady = false;
        coreRestartPending = false;
        publish(State.STARTING, "正在等待 mihomo 内部 SOCKS5 入口…");

        // mihomo remains in local-proxy mode. The patched core exposes a
        // loopback-only, per-install authenticated SOCKS5 listener for HEV.
        manager.ensureStarted();
        vpnExecutor.execute(() -> establishTunnel(requestGeneration));
    }

    private void establishTunnel(long requestGeneration) {
        ParcelFileDescriptor established = null;
        try {
            String socksPassword = readControllerSecret();
            int socksPort = deriveInternalSocksPort(socksPassword);
            waitForInternalSocks(requestGeneration, socksPort, socksPassword);
            ensureCurrent(requestGeneration);

            publish(State.STARTING, "正在建立固定 IPv4/IPv6 TUN 接口…");
            Builder builder = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(MTU)
                    .addAddress(IPV4_ADDRESS, IPV4_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_IPV4_ADDRESS)
                    .addAddress(IPV6_ADDRESS, IPV6_PREFIX)
                    .addRoute("::", 0)
                    .addDnsServer(DNS_IPV6_ADDRESS)
                    // HEV consumes a non-blocking TUN descriptor.
                    .setBlocking(false)
                    .setMetered(false)
                    .setConfigureIntent(openAppPendingIntent());

            // HEV and mihomo run under this application's UID. Excluding the
            // package keeps their loopback and upstream sockets outside the VPN,
            // while every other application remains captured by the default routes.
            builder.addDisallowedApplication(getPackageName());
            established = builder.establish();
            if (established == null) {
                throw new IOException("系统未能建立 VPN TUN 接口");
            }
            ensureCurrent(requestGeneration);
            tunnel = established;

            publish(State.STARTING, "正在启动 HEV TUN → SOCKS5 转发…");
            boolean hevStarted = HevSocks5Tunnel.start(
                    getCacheDir(),
                    established.getFd(),
                    MTU,
                    IPV4_ADDRESS,
                    IPV6_ADDRESS,
                    INTERNAL_SOCKS_HOST,
                    socksPort,
                    INTERNAL_SOCKS_USER,
                    socksPassword
            );
            if (!hevStarted) {
                throw new IOException("hev-socks5-tunnel 未能启动");
            }
            ensureCurrent(requestGeneration);

            ParcelFileDescriptor readyTunnel = established;
            MAIN_HANDLER.post(() -> finishStart(requestGeneration, readyTunnel));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupFailedStart(established);
            postStartFailure(requestGeneration, "建立 SOCKS5 VPN 时被中断");
        } catch (Exception | LinkageError exception) {
            cleanupFailedStart(established);
            Log.e(TAG, "Unable to establish HEV VPN", exception);
            postStartFailure(requestGeneration, usefulMessage(exception));
        }
    }

    private void finishStart(long requestGeneration, ParcelFileDescriptor readyTunnel) {
        if (!isCurrent(requestGeneration) || tunnel != readyTunnel) {
            vpnExecutor.execute(() -> {
                stopHevQuietly();
                closeTunnel(readyTunnel);
            });
            return;
        }
        starting = false;
        tunnelReady = true;
        publish(State.RUNNING, runningDetail());
        updateNotification("VPN 已连接 · HEV SOCKS");
    }

    private void cleanupFailedStart(ParcelFileDescriptor established) {
        if (tunnel == established) {
            tunnel = null;
        }
        stopHevQuietly();
        closeTunnel(established);
    }

    private void postStartFailure(long requestGeneration, String message) {
        MAIN_HANDLER.post(() -> {
            if (isCurrent(requestGeneration)) {
                failAndStop(message);
            }
        });
    }

    private void waitForInternalSocks(
            long requestGeneration,
            int socksPort,
            String socksPassword
    ) throws IOException, InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(SOCKS_READY_TIMEOUT_SECONDS);
        IOException lastFailure = null;
        byte[] username = INTERNAL_SOCKS_USER.getBytes(StandardCharsets.UTF_8);
        byte[] password = socksPassword.getBytes(StandardCharsets.UTF_8);
        if (username.length == 0 || username.length > 255
                || password.length == 0 || password.length > 255) {
            throw new IOException("mihomo 内部 SOCKS5 凭据长度无效");
        }

        while (System.nanoTime() < deadline && isCurrent(requestGeneration)) {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                InetAddress.getByName(INTERNAL_SOCKS_HOST),
                                socksPort
                        ),
                        500
                );
                socket.setSoTimeout(500);
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // RFC 1929 authentication verifies both listener identity and
                // the per-install credentials before Android publishes the VPN.
                output.write(new byte[]{0x05, 0x01, 0x02});
                output.flush();
                if (input.read() != 0x05 || input.read() != 0x02) {
                    throw new IOException("mihomo 内部 SOCKS5 未选择认证方法");
                }

                output.write(0x01);
                output.write(username.length);
                output.write(username);
                output.write(password.length);
                output.write(password);
                output.flush();
                if (input.read() == 0x01 && input.read() == 0x00) {
                    return;
                }
                throw new IOException("mihomo 内部 SOCKS5 认证失败");
            } catch (IOException exception) {
                lastFailure = exception;
            }
            Thread.sleep(120);
        }
        if (!isCurrent(requestGeneration)) {
            throw new InterruptedException("VPN 启动已取消");
        }
        throw new IOException(
                "mihomo 内部 SOCKS5 未在 " + SOCKS_READY_TIMEOUT_SECONDS + " 秒内就绪",
                lastFailure
        );
    }

    private String readControllerSecret() throws IOException {
        String secret = getSharedPreferences(CORE_PREFERENCES, MODE_PRIVATE)
                .getString(CONTROLLER_SECRET_KEY, null);
        if (secret == null || !secret.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("mihomo 控制器密钥尚未就绪");
        }
        return secret.toLowerCase(Locale.ROOT);
    }

    private static int deriveInternalSocksPort(String secret) {
        // FNV-1a 32-bit; the patched Go core uses the same derivation.
        long hash = 0x811c9dc5L;
        for (byte value : secret.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xffL;
            hash = (hash * 0x01000193L) & 0xffff_ffffL;
        }
        return INTERNAL_SOCKS_PORT_BASE
                + (int) (hash % INTERNAL_SOCKS_PORT_SPAN);
    }

    @Override
    public void onCoreStateChanged(MihomoManager.State state, String detail) {
        if (stopping || tunnel == null) {
            return;
        }
        switch (state) {
            case STARTING -> {
                coreRestartPending = true;
                tunnelReady = false;
                publish(State.STARTING, "mihomo 正在重启，HEV SOCKS5 隧道等待恢复…");
            }
            case RUNNING -> {
                if (coreRestartPending) {
                    coreRestartPending = false;
                    restartHevAfterCore(generation);
                }
            }
            case FAILED -> failAndStop(
                    detail == null || detail.isBlank()
                            ? "mihomo 本地代理已停止"
                            : "mihomo 本地代理已停止：" + detail
            );
            case STOPPED -> {
                coreRestartPending = true;
                tunnelReady = false;
                publish(State.STARTING, "mihomo 已停止，正在等待内部 SOCKS5 恢复…");
            }
        }
    }

    private void restartHevAfterCore(long requestGeneration) {
        vpnExecutor.execute(() -> {
            try {
                ParcelFileDescriptor currentTunnel = tunnel;
                if (currentTunnel == null || !currentTunnel.getFileDescriptor().valid()) {
                    return;
                }
                String socksPassword = readControllerSecret();
                int socksPort = deriveInternalSocksPort(socksPassword);
                waitForInternalSocks(requestGeneration, socksPort, socksPassword);
                ensureCurrent(requestGeneration);

                stopHevQuietly();
                if (!HevSocks5Tunnel.start(
                        getCacheDir(),
                        currentTunnel.getFd(),
                        MTU,
                        IPV4_ADDRESS,
                        IPV6_ADDRESS,
                        INTERNAL_SOCKS_HOST,
                        socksPort,
                        INTERNAL_SOCKS_USER,
                        socksPassword
                )) {
                    throw new IOException("hev-socks5-tunnel 未能恢复");
                }
                ensureCurrent(requestGeneration);

                MAIN_HANDLER.post(() -> {
                    if (!isCurrent(requestGeneration) || tunnel != currentTunnel) {
                        return;
                    }
                    tunnelReady = true;
                    publish(State.RUNNING, runningDetail());
                    updateNotification("VPN 已连接 · HEV SOCKS");
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                postRuntimeFailure(requestGeneration, "等待内部 SOCKS5 恢复时被中断");
            } catch (Exception | LinkageError exception) {
                postRuntimeFailure(
                        requestGeneration,
                        "HEV SOCKS5 隧道恢复失败：" + usefulMessage(exception)
                );
            }
        });
    }

    private void postRuntimeFailure(long requestGeneration, String message) {
        MAIN_HANDLER.post(() -> {
            if (isCurrent(requestGeneration)) {
                failAndStop(message);
            }
        });
    }

    private boolean isCurrent(long requestGeneration) {
        return requestGeneration == generation && !stopping;
    }

    private void ensureCurrent(long requestGeneration) throws InterruptedException {
        if (!isCurrent(requestGeneration)) {
            throw new InterruptedException("VPN request cancelled");
        }
    }

    private static String runningDetail() {
        return "VPN 已连接 · HEV SOCKS · IPv4/IPv6";
    }

    private void stopVpn(String message) {
        if (stopping) {
            return;
        }
        stopping = true;
        starting = false;
        tunnelReady = false;
        coreRestartPending = false;
        ++generation;
        publish(State.STARTING, "正在断开 HEV VPN…");

        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        stopTunnelAsync(currentTunnel, () -> {
            publish(State.STOPPED, message);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void failAndStop(String message) {
        if (stopping) {
            return;
        }
        stopping = true;
        starting = false;
        tunnelReady = false;
        coreRestartPending = false;
        ++generation;
        publish(State.FAILED, message);
        updateNotification("VPN 启动失败");

        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        stopTunnelAsync(currentTunnel, () -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void stopTunnelAsync(ParcelFileDescriptor descriptor, Runnable callback) {
        vpnExecutor.execute(() -> {
            stopHevQuietly();
            closeTunnel(descriptor);
            MAIN_HANDLER.post(callback);
        });
    }

    @Override
    public void onRevoke() {
        stopVpn("VPN 授权已被系统撤销");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        manager.removeListener(this);
        boolean unexpected = !stopping;
        stopping = true;
        starting = false;
        tunnelReady = false;
        coreRestartPending = false;
        ++generation;

        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        vpnExecutor.execute(() -> {
            stopHevQuietly();
            closeTunnel(currentTunnel);
        });
        vpnExecutor.shutdown();
        if (unexpected) {
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
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(withSystemMode(text))
                .setContentIntent(openAppPendingIntent())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (!isAlwaysOn()) {
            PendingIntent stopIntent = PendingIntent.getService(
                    this,
                    1,
                    new Intent(this, AndroidVpnService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(
                        new Notification.Action.Builder(
                                Icon.createWithResource(this, R.drawable.ic_vpn_key),
                                getString(R.string.stop_vpn),
                                stopIntent
                        ).build()
                );
        }
        return builder.build();
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
        sharedAlwaysOn = isAlwaysOn();
        sharedLockdown = isLockdownEnabled();
        sharedState = state;
        sharedDetail = withSystemMode(detail);
        MAIN_HANDLER.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onVpnStateChanged(sharedState, sharedDetail);
            }
        });
    }

    private String withSystemMode(String detail) {
        if (isLockdownEnabled()) {
            return detail + " · 始终开启/锁定";
        }
        if (isAlwaysOn()) {
            return detail + " · 始终开启";
        }
        return detail;
    }

    private static void stopHevQuietly() {
        try {
            HevSocks5Tunnel.stop();
        } catch (LinkageError error) {
            Log.w(TAG, "Unable to stop HEV native runtime", error);
        }
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

    private static String usefulMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
