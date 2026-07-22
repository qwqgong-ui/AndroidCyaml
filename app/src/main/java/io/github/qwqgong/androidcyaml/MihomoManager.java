package io.github.qwqgong.androidcyaml;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MihomoManager {
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
    }

    public interface Listener {
        void onCoreStateChanged(State state, String detail);
    }

    public interface ImportCallback {
        void onComplete(boolean success, String detail);
    }

    public interface OperationCallback {
        void onComplete(boolean success, String detail);
    }

    private static final String TAG = "AndroidCyaml/Mihomo";
    private static final String PREFERENCES = "androidcyaml";
    private static final String SECRET_KEY = "controller_secret";
    private static final String HOST = "127.0.0.1";
    private static final int MAX_CONFIG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LOG_LINES = 80;

    private static volatile MihomoManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object processLock = new Object();
    private final Object logLock = new Object();
    private final ArrayDeque<String> recentLogs = new ArrayDeque<>();
    private final String controllerSecret;

    private volatile State state = State.STOPPED;
    private volatile String detail = "";
    private volatile int controllerPort;
    private ParcelFileDescriptor vpnTunnel;
    private Process process;

    private MihomoManager(Context context) {
        this.context = context.getApplicationContext();
        controllerSecret = loadOrCreateSecret();
    }

    public static MihomoManager getInstance(Context context) {
        MihomoManager local = instance;
        if (local == null) {
            synchronized (MihomoManager.class) {
                local = instance;
                if (local == null) {
                    local = new MihomoManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        mainHandler.post(() -> listener.onCoreStateChanged(state, detail));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void ensureStarted() {
        controlExecutor.execute(() -> {
            if (state == State.STARTING || isProcessAlive()) {
                return;
            }
            startInternal();
        });
    }

    public void restart() {
        controlExecutor.execute(() -> {
            stopInternal(false);
            startInternal();
        });
    }

    public void activateVpn(ParcelFileDescriptor tunnel, OperationCallback callback) {
        controlExecutor.execute(() -> {
            if (tunnel == null || !tunnel.getFileDescriptor().valid()) {
                postOperationResult(callback, false, "系统返回了无效的 VPN TUN 接口");
                return;
            }
            vpnTunnel = tunnel;
            stopInternal(false);
            boolean success = startInternal();
            if (!success && vpnTunnel == tunnel) {
                vpnTunnel = null;
            }
            postOperationResult(callback, success, detail);
        });
    }

    public void deactivateVpn(ParcelFileDescriptor tunnel, Runnable callback) {
        controlExecutor.execute(() -> {
            if (tunnel == null || vpnTunnel == tunnel) {
                vpnTunnel = null;
            }
            stopInternal(false);
            mainHandler.post(callback);
            startInternal();
        });
    }

    public String getDashboardUrl() {
        int port = controllerPort;
        return "http://" + HOST + ":" + port
                + "/ui/#/setup?hostname=" + HOST
                + "&port=" + port
                + "&secret=" + controllerSecret
                + "&disableUpgradeCore=1&disableTunMode=1&type=clash";
    }

    public boolean isControllerPort(int port) {
        return port > 0 && port == controllerPort;
    }

    public void importConfig(Uri source, ImportCallback callback) {
        controlExecutor.execute(() -> importConfigInternal(source, callback));
    }

    private boolean startInternal() {
        synchronized (logLock) {
            recentLogs.clear();
        }
        publish(State.STARTING, "正在准备内置面板和 mihomo 核心…");
        Process candidate = null;
        LocalServerSocket descriptorServer = null;
        Future<Void> descriptorTransfer = null;
        try {
            RuntimeFiles files = ensureRuntimeFiles();
            terminateStaleCores();
            ParcelFileDescriptor activeTunnel = vpnTunnel;
            int activeControllerPort = findAvailableControllerPort();
            controllerPort = activeControllerPort;
            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libmihomo.so");
            if (!binary.isFile()) {
                throw new IOException("APK 中缺少 arm64 mihomo 核心：" + binary);
            }

            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            command.add("-d");
            command.add(files.home.getAbsolutePath());
            command.add("-f");
            command.add(files.config.getAbsolutePath());
            command.add("-ext-ui");
            command.add(files.ui.getAbsolutePath());
            command.add("-ext-ctl");
            command.add(HOST + ":" + activeControllerPort);
            command.add("-secret");
            command.add(controllerSecret);
            if (activeTunnel == null) {
                command.add("-android-disable-tun");
            } else {
                String socketName = "androidcyaml-vpn-" + UUID.randomUUID();
                descriptorServer = new LocalServerSocket(socketName);
                LocalServerSocket transferServer = descriptorServer;
                descriptorTransfer = ioExecutor.submit(
                        () -> sendTunDescriptor(transferServer, activeTunnel)
                );
                command.add("-android-vpn-fd-socket");
                command.add("@" + socketName);
            }

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(files.home)
                    .redirectErrorStream(true);
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

            candidate = builder.start();
            synchronized (processLock) {
                process = candidate;
            }
            readCoreLogs(candidate);
            watchForExit(candidate);

            if (descriptorTransfer != null) {
                waitForTunDescriptorTransfer(descriptorTransfer, descriptorServer);
                descriptorTransfer = null;
                descriptorServer = null;
            }

            if (!waitForController(candidate, activeControllerPort, 90, TimeUnit.SECONDS)) {
                String logs = diagnosticLogSummary();
                throw new IOException(logs.isEmpty()
                        ? "mihomo 控制器未在 90 秒内就绪"
                        : "mihomo 启动失败：" + logs);
            }

            publish(
                    State.RUNNING,
                    "mihomo " + BuildConfig.MIHOMO_COMMIT.substring(0, 8)
                            + " · zashboard " + BuildConfig.ZASHBOARD_VERSION
                            + (activeTunnel == null ? " · 本地代理" : " · Android VPN")
            );
            return true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (descriptorTransfer != null) {
                descriptorTransfer.cancel(true);
            }
            closeQuietly(descriptorServer);
            if (candidate != null) {
                synchronized (processLock) {
                    if (process == candidate) {
                        process = null;
                    }
                }
                stopSpecificProcess(candidate);
            }
            Log.e(TAG, "Unable to start mihomo", exception);
            publish(State.FAILED, usefulMessage(exception));
            return false;
        }
    }

    private Void sendTunDescriptor(
            LocalServerSocket descriptorServer,
            ParcelFileDescriptor tunnel
    ) throws IOException {
        try (LocalServerSocket server = descriptorServer;
             LocalSocket connection = server.accept()) {
            FileDescriptor descriptor = tunnel.getFileDescriptor();
            if (!descriptor.valid()) {
                throw new IOException("VPN TUN 文件描述符已关闭");
            }
            connection.setFileDescriptorsForSend(new FileDescriptor[]{descriptor});
            connection.getOutputStream().write(1);
            connection.getOutputStream().flush();
        }
        return null;
    }

    private void waitForTunDescriptorTransfer(
            Future<Void> transfer,
            LocalServerSocket server
    ) throws IOException, InterruptedException {
        try {
            transfer.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            closeQuietly(server);
            transfer.cancel(true);
            throw new IOException("mihomo 未在 5 秒内接收 VPN TUN 接口", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IOException(
                    cause == null ? "无法向 mihomo 传递 VPN TUN 接口" : cause.getMessage(),
                    cause
            );
        }
    }

    private void importConfigInternal(Uri source, ImportCallback callback) {
        File candidate = null;
        File backup = null;
        try {
            RuntimeFiles files = ensureRuntimeFiles();
            candidate = new File(files.home, "config.importing.yaml");
            backup = new File(files.home, "config.previous.yaml");
            copyUriWithLimit(source, candidate, MAX_CONFIG_BYTES);

            Files.deleteIfExists(backup.toPath());
            if (files.config.isFile()) {
                moveReplacing(files.config, backup);
            }
            moveReplacing(candidate, files.config);

            stopInternal(false);
            if (!startInternal()) {
                String failedConfigDetail = detail;
                stopInternal(false);
                Files.deleteIfExists(files.config.toPath());
                if (backup.isFile()) {
                    moveReplacing(backup, files.config);
                    startInternal();
                }
                throw new IOException(
                        "新配置无法启动，已恢复原配置：" + failedConfigDetail
                );
            }

            try {
                Files.deleteIfExists(backup.toPath());
            } catch (IOException exception) {
                Log.w(TAG, "Unable to remove previous config backup", exception);
            }
            postImportResult(callback, true, "配置已导入并由 mihomo 成功启动");
        } catch (Exception exception) {
            Log.e(TAG, "Unable to import config", exception);
            if (backup != null && backup.isFile()) {
                File config = new File(backup.getParentFile(), "config.yaml");
                if (!config.exists()) {
                    try {
                        moveReplacing(backup, config);
                    } catch (IOException restoreException) {
                        Log.e(TAG, "Unable to restore previous config", restoreException);
                    }
                }
            }
            postImportResult(callback, false, usefulMessage(exception));
        } finally {
            if (candidate != null) {
                try {
                    Files.deleteIfExists(candidate.toPath());
                } catch (IOException ignored) {
                    // A stale candidate is harmless and will be replaced on the next import.
                }
            }
        }
    }

    private void copyUriWithLimit(Uri source, File destination, int byteLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > byteLimit) {
                    throw new IOException("config.yaml 超过 32 MiB 限制");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private RuntimeFiles ensureRuntimeFiles() throws IOException {
        File home = new File(context.getNoBackupFilesDir(), "mihomo");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建 mihomo 工作目录");
        }

        File config = new File(home, "config.yaml");
        if (!config.isFile()) {
            copyAssetFile("default-config.yaml", config);
        }

        File ui = new File(home, "ui");
        File marker = new File(ui, ".androidcyaml-version");
        String installedVersion = marker.isFile() ? readFile(marker).trim() : "";
        if (!BuildConfig.ZASHBOARD_VERSION.equals(installedVersion)
                || !new File(ui, "index.html").isFile()) {
            installDashboard(home, ui);
        }
        return new RuntimeFiles(home, config, ui);
    }

    private void installDashboard(File home, File ui) throws IOException {
        File staging = new File(home, "ui.installing");
        File previous = new File(home, "ui.previous");
        deleteRecursively(staging);
        deleteRecursively(previous);
        if (!staging.mkdirs()) {
            throw new IOException("无法创建面板暂存目录");
        }
        copyAssetTree("zashboard", staging);
        writeText(new File(staging, ".androidcyaml-version"), BuildConfig.ZASHBOARD_VERSION);

        if (ui.exists()) {
            moveReplacing(ui, previous);
        }
        try {
            moveReplacing(staging, ui);
        } catch (IOException exception) {
            if (previous.exists() && !ui.exists()) {
                moveReplacing(previous, ui);
            }
            throw exception;
        }
        deleteRecursively(previous);
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination);
        }
        for (String entry : entries) {
            copyAssetTree(assetPath + "/" + entry, new File(destination, entry));
        }
    }

    private void copyAssetFile(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private boolean waitForController(
            Process candidate,
            int port,
            long timeout,
            TimeUnit unit
    ) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline && candidate.isAlive()) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + HOST + ":" + port + "/version");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(300);
                connection.setReadTimeout(300);
                connection.setRequestProperty("Authorization", "Bearer " + controllerSecret);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return true;
                }
            } catch (IOException ignored) {
                // The controller is expected to reject connections while it starts.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private int findAvailableControllerPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), 0),
                    1
            );
            return reservation.getLocalPort();
        }
    }

    private void readCoreLogs(Process source) {
        ioExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                    synchronized (logLock) {
                        recentLogs.addLast(line);
                        while (recentLogs.size() > MAX_LOG_LINES) {
                            recentLogs.removeFirst();
                        }
                    }
                }
            } catch (IOException exception) {
                if (source.isAlive()) {
                    Log.w(TAG, "Core log reader stopped", exception);
                }
            }
        });
    }

    private void watchForExit(Process source) {
        ioExecutor.execute(() -> {
            try {
                int exitCode = source.waitFor();
                synchronized (processLock) {
                    if (process != source) {
                        return;
                    }
                    process = null;
                }
                String logs = recentLogSummary();
                publish(
                        State.FAILED,
                        "mihomo 已退出（" + exitCode + "）"
                                + (logs.isEmpty() ? "" : "：" + logs)
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void stopInternal(boolean publishStopped) {
        Process current;
        synchronized (processLock) {
            current = process;
            process = null;
        }
        if (current != null) {
            stopSpecificProcess(current);
        }
        if (publishStopped) {
            publish(State.STOPPED, "mihomo 已停止");
        }
    }

    private void stopSpecificProcess(Process target) {
        target.destroy();
        try {
            if (!target.waitFor(2, TimeUnit.SECONDS)) {
                target.destroyForcibly();
                target.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }

    private boolean isProcessAlive() {
        synchronized (processLock) {
            return process != null && process.isAlive();
        }
    }

    private void terminateStaleCores() throws IOException {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        File proc = new File("/proc");
        File[] processes = proc.listFiles();
        if (processes != null) {
            for (File processDirectory : processes) {
                try {
                    candidates.add(Integer.parseInt(processDirectory.getName()));
                } catch (NumberFormatException ignored) {
                    // Non-process entries such as /proc/net are expected.
                }
            }
        }

        String expectedBinary = new File(
                context.getApplicationInfo().nativeLibraryDir,
                "libmihomo.so"
        ).getAbsolutePath();
        for (int pid : candidates) {
            if (pid <= 0 || pid == android.os.Process.myPid()) {
                continue;
            }
            String command = readProcessCommand(pid);
            if (!expectedBinary.equals(command)) {
                continue;
            }
            Log.w(TAG, "Stopping stale mihomo process " + pid);
            signalProcess(pid, OsConstants.SIGTERM);
            waitForProcessExit(pid, 800);
            if (isProcessAlive(pid)) {
                signalProcess(pid, OsConstants.SIGKILL);
                waitForProcessExit(pid, 800);
            }
            if (isProcessAlive(pid)) {
                throw new IOException("无法停止残留的 mihomo 进程 " + pid);
            }
        }
    }

    private String readProcessCommand(int pid) {
        try {
            String commandLine = readFile(new File("/proc/" + pid + "/cmdline"));
            int separator = commandLine.indexOf('\0');
            return separator < 0 ? commandLine : commandLine.substring(0, separator);
        } catch (IOException ignored) {
            return "";
        }
    }

    private void signalProcess(int pid, int signal) throws IOException {
        try {
            Os.kill(pid, signal);
        } catch (ErrnoException exception) {
            if (exception.errno != OsConstants.ESRCH) {
                throw new IOException("无法向残留 mihomo 进程发送信号", exception);
            }
        }
    }

    private void waitForProcessExit(int pid, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (isProcessAlive(pid) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("等待残留 mihomo 进程退出时被中断", exception);
            }
        }
    }

    private static boolean isProcessAlive(int pid) {
        return new File("/proc/" + pid).isDirectory();
    }

    private String loadOrCreateSecret() {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String existing = preferences.getString(SECRET_KEY, null);
        if (existing != null && existing.length() == 64) {
            return existing;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder secret = new StringBuilder(random.length * 2);
        for (byte value : random) {
            secret.append(String.format("%02x", value & 0xff));
        }
        String generated = secret.toString();
        preferences.edit().putString(SECRET_KEY, generated).apply();
        return generated;
    }

    private void publish(State newState, String newDetail) {
        state = newState;
        detail = newDetail == null ? "" : newDetail;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onCoreStateChanged(state, detail);
            }
        });
    }

    private void postImportResult(ImportCallback callback, boolean success, String message) {
        mainHandler.post(() -> callback.onComplete(success, message));
    }

    private void postOperationResult(OperationCallback callback, boolean success, String message) {
        mainHandler.post(() -> callback.onComplete(success, message));
    }

    private String recentLogSummary() {
        synchronized (logLock) {
            if (recentLogs.isEmpty()) {
                return "";
            }
            String last = recentLogs.peekLast();
            return last == null ? "" : last.trim();
        }
    }

    private String diagnosticLogSummary() {
        synchronized (logLock) {
            String lastError = "";
            for (String line : recentLogs) {
                if (line.contains("level=error") || line.contains("level=fatal")) {
                    lastError = line.trim();
                }
            }
            return lastError.isEmpty() ? recentLogSummary() : lastError;
        }
    }

    private static String usefulMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static void moveReplacing(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String readFile(File source) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File destination, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void closeQuietly(LocalServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket may already have been closed by the descriptor sender.
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (!target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!target.delete()) {
            throw new IOException("无法删除：" + target);
        }
    }

    private static final class RuntimeFiles {
        final File home;
        final File config;
        final File ui;

        RuntimeFiles(File home, File config, File ui) {
            this.home = home;
            this.config = config;
            this.ui = ui;
        }
    }

}
