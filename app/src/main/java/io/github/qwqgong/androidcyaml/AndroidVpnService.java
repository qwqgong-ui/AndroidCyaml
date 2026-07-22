package io.github.qwqgong.androidcyaml;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final String TAG = "AndroidCyaml/VPN";
    private static final String NOTIFICATION_CHANNEL = "androidcyaml_vpn";
    private static final int NOTIFICATION_ID = 36;

    // VpnService.Builder is the only owner of interface addressing, routes and DNS.
    // These values are deliberately independent from config.yaml. HEV receives only
    // the matching MTU because it reads packets from the already-created TUN FD.
    private static final int MTU = 9000;
    private static final String IPV4_ADDRESS = "198.18.0.1";
    private static final int IPV4_PREFIX = 30;
    private static final String IPV4_DNS = "198.18.0.2";
    private static final String IPV6_ADDRESS = "fdfe:dcba:9876::1";
    private static final int IPV6_PREFIX = 126;
    private static final String MAP_DNS_NETWORK = "100.64.0.0";
    private static final String MAP_DNS_NETMASK = "255.192.0.0";
    private static final String HEV_CONFIG_NAME = "hev-socks5-tunnel.yaml";
    private static final long SOCKS_READY_TIMEOUT_SECONDS = 15;
    private static final long NATIVE_START_SETTLE_MILLIS = 250;

    // HEV registers all four methods from JNI_OnLoad, including the stats method.
    private static native boolean TProxyStartService(String configPath, int tunFd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    @SuppressWarnings("unused")
    private static native long[] TProxyGetStats();

    private static final String NATIVE_LOAD_ERROR;

    static {
        String loadError = null;
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (UnsatisfiedLinkError exception) {
            loadError = usefulMessage(exception);
        }
        NATIVE_LOAD_ERROR = loadError;
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile State sharedState = State.STOPPED;
    private static volatile String sharedDetail = "VPN 未连接";

    private final ExecutorService tunnelExecutor = Executors.newSingleThreadExecutor();

    private MihomoManager manager;
    private volatile ParcelFileDescriptor tunnel;
    private volatile File tunnelConfig;
    private volatile int activeSocksPort;
    private volatile long generation;
    private volatile boolean stopping;
    private volatile boolean starting;
    private volatile boolean tunnelReady;
    private volatile boolean nativeTunnelStarted;
    private volatile boolean coreRestartPending;

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
        manager.addListener(this);
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

        if (tunnelReady) {
            publish(State.RUNNING, runningDetail(activeSocksPort));
        } else if (!starting && tunnel == null) {
            startVpn();
        } else {
            publish(State.STARTING, "正在等待 HEV SOCKS5 隧道接管 VPN…");
        }
        return START_STICKY;
    }

    private void startVpn() {
        long requestGeneration = ++generation;
        starting = true;
        tunnelReady = false;
        activeSocksPort = 0;
        publish(State.STARTING, "正在等待 mihomo 本地 SOCKS5 入站…");

        if (NATIVE_LOAD_ERROR != null) {
            failAndStop("APK 中的 HEV SOCKS5 隧道无法加载：" + NATIVE_LOAD_ERROR);
            return;
        }

        manager.ensureStarted();
        tunnelExecutor.execute(() -> establishAndStartTunnel(requestGeneration));
    }

    private void establishAndStartTunnel(long requestGeneration) {
        ParcelFileDescriptor established = null;
        File generatedConfig = null;
        try {
            int socksPort = awaitSocksPort(requestGeneration);
            ensureCurrent(requestGeneration);
            publish(State.STARTING, "正在建立固定 IPv4/IPv6 VPN 接口…");

            generatedConfig = writeHevConfig(socksPort);
            Builder builder = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(MTU)
                    .addAddress(IPV4_ADDRESS, IPV4_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(IPV4_DNS)
                    .addAddress(IPV6_ADDRESS, IPV6_PREFIX)
                    .addRoute("::", 0)
                    .setBlocking(false)
                    .setMetered(false)
                    .setConfigureIntent(openAppPendingIntent());

            // HEV and mihomo run under this application's UID. Excluding the UID
            // keeps the loopback SOCKS transport and mihomo's upstream sockets
            // outside this VPN, which prevents a routing loop.
            builder.addDisallowedApplication(getPackageName());
            established = builder.establish();
            if (established == null) {
                throw new IOException("系统未能建立 VPN TUN 接口");
            }
            ensureCurrent(requestGeneration);

            tunnel = established;
            tunnelConfig = generatedConfig;
            startNativeTunnel(generatedConfig, established, socksPort);
            ensureCurrent(requestGeneration);

            activeSocksPort = socksPort;
            ParcelFileDescriptor activeTunnel = established;
            MAIN_HANDLER.post(() -> {
                if (!isCurrent(requestGeneration) || tunnel != activeTunnel) {
                    return;
                }
                starting = false;
                tunnelReady = true;
                publish(State.RUNNING, runningDetail(socksPort));
                updateNotification("VPN 已连接");
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupFailedStart(established, generatedConfig);
            postStartFailure(requestGeneration, "建立 SOCKS5 VPN 时被中断");
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException exception) {
            Log.e(TAG, "Unable to establish SOCKS5 VPN", exception);
            cleanupFailedStart(established, generatedConfig);
            postStartFailure(requestGeneration, usefulMessage(exception));
        } catch (LinkageError error) {
            Log.e(TAG, "HEV SOCKS5 JNI failure", error);
            cleanupFailedStart(established, generatedConfig);
            postStartFailure(
                    requestGeneration,
                    "HEV SOCKS5 隧道不可用：" + usefulMessage(error)
            );
        }
    }

    private int awaitSocksPort(long requestGeneration)
            throws IOException, InterruptedException {
        return MihomoSocksEndpoint.awaitPort(
                manager,
                SOCKS_READY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                () -> !isCurrent(requestGeneration)
        );
    }

    private void startNativeTunnel(
            File config,
            ParcelFileDescriptor descriptor,
            int socksPort
    ) throws IOException, InterruptedException {
        if (!descriptor.getFileDescriptor().valid()) {
            throw new IOException("VPN TUN 接口已关闭");
        }
        if (!TProxyStartService(config.getAbsolutePath(), descriptor.getFd())) {
            throw new IOException("无法启动 HEV SOCKS5 隧道线程");
        }
        nativeTunnelStarted = true;

        // JNI reports thread creation, not full initialization. Surface immediate
        // config/lwIP failures before marking the VPN as connected.
        TimeUnit.MILLISECONDS.sleep(NATIVE_START_SETTLE_MILLIS);
        if (!TProxyIsRunning()) {
            nativeTunnelStarted = false;
            throw new IOException(
                    "HEV SOCKS5 隧道初始化失败（SOCKS5 127.0.0.1:" + socksPort + "）"
            );
        }
    }

    private void cleanupFailedStart(
            ParcelFileDescriptor established,
            File generatedConfig
    ) {
        if (tunnel == established) {
            tunnel = null;
        }
        if (tunnelConfig == generatedConfig) {
            tunnelConfig = null;
        }
        stopNativeTunnel();
        closeTunnel(established);
        deleteQuietly(generatedConfig);
    }

    private void postStartFailure(long requestGeneration, String message) {
        MAIN_HANDLER.post(() -> {
            if (!isCurrent(requestGeneration)) {
                return;
            }
            failAndStop(message);
        });
    }

    private File writeHevConfig(int socksPort) throws IOException {
        File config = new File(getCacheDir(), HEV_CONFIG_NAME);
        String content = buildHevConfig(socksPort);
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        return config;
    }

    private static String buildHevConfig(int socksPort) {
        return "tunnel:\n"
                + "  mtu: " + MTU + "\n"
                + "  icmp: 'off'\n"
                + "socks5:\n"
                + "  address: '127.0.0.1'\n"
                + "  port: " + socksPort + "\n"
                + "  udp: 'udp'\n"
                + "  udp-address: '127.0.0.1'\n"
                + "mapdns:\n"
                + "  address: " + IPV4_DNS + "\n"
                + "  port: 53\n"
                + "  network: " + MAP_DNS_NETWORK + "\n"
                + "  netmask: " + MAP_DNS_NETMASK + "\n"
                + "  cache-size: 10000\n"
                + "misc:\n"
                + "  task-stack-size: 86016\n"
                + "  tcp-buffer-size: 65536\n"
                + "  udp-recv-buffer-size: 524288\n"
                + "  log-file: stderr\n"
                + "  log-level: warn\n";
    }

    private void stopVpn(String message) {
        if (stopping) {
            return;
        }
        stopping = true;
        starting = false;
        tunnelReady = false;
        coreRestartPending = false;
        activeSocksPort = 0;
        ++generation;
        publish(State.STARTING, "正在断开 VPN…");

        ParcelFileDescriptor currentTunnel = tunnel;
        File currentConfig = tunnelConfig;
        tunnel = null;
        tunnelConfig = null;
        stopTunnelAsync(currentTunnel, currentConfig, () -> {
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
        activeSocksPort = 0;
        ++generation;
        publish(State.FAILED, message);
        updateNotification("VPN 启动失败");

        ParcelFileDescriptor currentTunnel = tunnel;
        File currentConfig = tunnelConfig;
        tunnel = null;
        tunnelConfig = null;
        stopTunnelAsync(currentTunnel, currentConfig, () -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void stopTunnelAsync(
            ParcelFileDescriptor descriptor,
            File config,
            Runnable callback
    ) {
        tunnelExecutor.execute(() -> {
            stopNativeTunnel();
            closeTunnel(descriptor);
            deleteQuietly(config);
            MAIN_HANDLER.post(callback);
        });
    }

    @Override
    public void onCoreStateChanged(MihomoManager.State state, String detail) {
        if (tunnel == null || stopping) {
            return;
        }
        switch (state) {
            case STARTING -> {
                coreRestartPending = true;
                tunnelReady = false;
                publish(State.STARTING, "mihomo 正在重启，HEV SOCKS5 隧道等待恢复…");
            }
            case RUNNING -> {
                boolean forceRestart = coreRestartPending;
                coreRestartPending = false;
                refreshSocksEndpoint(generation, forceRestart);
            }
            case FAILED -> failAndStop(
                    detail == null || detail.isBlank()
                            ? "mihomo 本地代理已停止"
                            : "mihomo 本地代理已停止：" + detail
            );
            case STOPPED -> {
                coreRestartPending = true;
                tunnelReady = false;
                publish(State.STARTING, "mihomo 已停止，正在等待本地代理恢复…");
            }
        }
    }

    private void refreshSocksEndpoint(long requestGeneration, boolean forceRestart) {
        tunnelExecutor.execute(() -> {
            try {
                int socksPort = awaitSocksPort(requestGeneration);
                ensureCurrent(requestGeneration);
                ParcelFileDescriptor currentTunnel = tunnel;
                if (currentTunnel == null) {
                    return;
                }

                boolean nativeRunning = TProxyIsRunning();
                if (forceRestart || socksPort != activeSocksPort || !nativeRunning) {
                    stopNativeTunnel();
                    ensureCurrent(requestGeneration);
                    File config = writeHevConfig(socksPort);
                    tunnelConfig = config;
                    startNativeTunnel(config, currentTunnel, socksPort);
                    ensureCurrent(requestGeneration);
                }

                activeSocksPort = socksPort;
                MAIN_HANDLER.post(() -> {
                    if (!isCurrent(requestGeneration) || tunnel != currentTunnel) {
                        return;
                    }
                    tunnelReady = true;
                    publish(State.RUNNING, runningDetail(socksPort));
                    updateNotification("VPN 已连接");
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (isCurrent(requestGeneration)) {
                    postRuntimeFailure(requestGeneration, "等待本地 SOCKS5 入站时被中断");
                }
            } catch (IOException | RuntimeException exception) {
                if (isCurrent(requestGeneration)) {
                    postRuntimeFailure(
                            requestGeneration,
                            "本地 SOCKS5 入站恢复失败：" + usefulMessage(exception)
                    );
                }
            } catch (LinkageError error) {
                if (isCurrent(requestGeneration)) {
                    postRuntimeFailure(
                            requestGeneration,
                            "HEV SOCKS5 隧道恢复失败：" + usefulMessage(error)
                    );
                }
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

    private void stopNativeTunnel() {
        if (NATIVE_LOAD_ERROR != null) {
            nativeTunnelStarted = false;
            return;
        }
        try {
            if (nativeTunnelStarted || TProxyIsRunning()) {
                TProxyStopService();
            }
        } catch (RuntimeException | LinkageError exception) {
            Log.w(TAG, "Unable to stop HEV SOCKS5 tunnel", exception);
        } finally {
            nativeTunnelStarted = false;
        }
    }

    private boolean isCurrent(long requestGeneration) {
        return requestGeneration == generation && !stopping;
    }

    private void ensureCurrent(long requestGeneration) throws InterruptedException {
        if (!isCurrent(requestGeneration)) {
            throw new InterruptedException("VPN request cancelled");
        }
    }

    private static String runningDetail(int socksPort) {
        return "VPN 已连接 · HEV SOCKS5 127.0.0.1:" + socksPort + " · IPv4/IPv6";
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
        activeSocksPort = 0;
        ++generation;

        ParcelFileDescriptor currentTunnel = tunnel;
        File currentConfig = tunnelConfig;
        tunnel = null;
        tunnelConfig = null;
        if (currentTunnel != null || nativeTunnelStarted) {
            tunnelExecutor.execute(() -> {
                stopNativeTunnel();
                closeTunnel(currentTunnel);
                deleteQuietly(currentConfig);
            });
        }
        tunnelExecutor.shutdown();
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

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to remove temporary HEV config: " + file);
        }
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
