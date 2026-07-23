package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns one mihomo subprocess. It has no Android VPN policy of its own. */
final class MihomoRuntime implements AutoCloseable {
    interface ExitListener {
        void onUnexpectedExit(MihomoRuntime runtime, int exitCode, String diagnostics);
    }

    static final String PREFERENCES = "androidcyaml";
    static final String CONTROLLER_SECRET_KEY = "controller_secret";
    static final int MAX_CONFIG_BYTES = 32 * 1024 * 1024;

    private static final String TAG = "AndroidCyaml/Runtime";
    private static final String HOST = "127.0.0.1";
    private static final int PREFERRED_CONTROLLER_PORT = 17_890;
    private static final int MAX_LOG_LINES = 120;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final Context context;
    private final String platformSocket;
    private final ExitListener exitListener;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final Object processLock = new Object();
    private final Object logLock = new Object();
    private final ArrayDeque<String> recentLogs = new ArrayDeque<>();
    private final String controllerSecret;

    private volatile int controllerPort;
    private volatile boolean ready;
    private volatile boolean intentionalStop;
    private Process process;

    MihomoRuntime(Context context, String platformSocket, ExitListener exitListener) {
        this.context = context.getApplicationContext();
        this.platformSocket = platformSocket;
        this.exitListener = exitListener;
        controllerSecret = loadOrCreateSecret(this.context);
    }

    String start() throws IOException, InterruptedException {
        synchronized (logLock) {
            recentLogs.clear();
        }
        ready = false;
        intentionalStop = false;
        RuntimeFiles files = ensureRuntimeFiles(context);
        terminateStaleCores(context);
        int port = findAvailableControllerPort();
        controllerPort = port;

        File binary = coreBinary(context);
        if (!binary.isFile()) {
            throw new IOException("APK 中缺少 arm64 mihomo 核心：" + binary);
        }

        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("-d");
        command.add(files.home().getAbsolutePath());
        command.add("-f");
        command.add(files.config().getAbsolutePath());
        command.add("-ext-ui");
        command.add(files.ui().getAbsolutePath());
        command.add("-ext-ctl");
        command.add(HOST + ":" + port);
        command.add("-secret");
        command.add(controllerSecret);
        command.add("-android-platform-socket");
        command.add(platformSocket);

        Process candidate = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(files.home())
                    .redirectErrorStream(true);
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            candidate = builder.start();
            synchronized (processLock) {
                process = candidate;
            }
            readCoreLogs(candidate);
            watchForExit(candidate);

            if (!waitForController(candidate, port, 90, TimeUnit.SECONDS)) {
                throw new IOException(diagnosticMessage("mihomo 控制器未在 90 秒内就绪"));
            }
            waitForTunActive(candidate, port, 10, TimeUnit.SECONDS);
            ready = true;
            return "mihomo " + shortCommit() + " · 原生 TUN · zashboard "
                    + BuildConfig.ZASHBOARD_VERSION;
        } catch (IOException | InterruptedException exception) {
            intentionalStop = true;
            ready = false;
            if (candidate != null) {
                synchronized (processLock) {
                    if (process == candidate) {
                        process = null;
                    }
                }
                stopSpecificProcess(candidate);
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        }
    }

    int getControllerPort() {
        return controllerPort;
    }

    String dashboardUrl() {
        if (!ready || controllerPort <= 0) {
            return "";
        }
        return "http://" + HOST + ":" + controllerPort
                + "/ui/#/setup?hostname=" + HOST
                + "&port=" + controllerPort
                + "&secret=" + controllerSecret
                + "&disableUpgradeCore=1&disableTunMode=1&type=clash";
    }

    int trimLogCache() {
        synchronized (logLock) {
            int removed = recentLogs.size();
            recentLogs.clear();
            return removed;
        }
    }

    @Override
    public void close() {
        intentionalStop = true;
        ready = false;
        Process current;
        synchronized (processLock) {
            current = process;
            process = null;
        }
        if (current != null) {
            stopSpecificProcess(current);
        }
        controllerPort = 0;
        ioExecutor.shutdownNow();
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
                    Log.w(TAG, "mihomo log reader stopped", exception);
                }
            }
        });
    }

    private void watchForExit(Process source) {
        ioExecutor.execute(() -> {
            try {
                int exitCode = source.waitFor();
                boolean notify;
                synchronized (processLock) {
                    if (process != source) {
                        return;
                    }
                    process = null;
                    notify = ready && !intentionalStop;
                    ready = false;
                }
                if (notify && exitListener != null) {
                    exitListener.onUnexpectedExit(this, exitCode, recentLogSummary());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private boolean waitForController(
            Process candidate,
            int port,
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline && candidate.isAlive()) {
            HttpURLConnection connection = null;
            try {
                connection = openControllerConnection(port, "/version");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return true;
                }
            } catch (IOException ignored) {
                // Controller startup is polled until the deadline.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(120);
        }
        return false;
    }

    private void waitForTunActive(
            Process candidate,
            int port,
            long timeout,
            TimeUnit unit
    ) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline && candidate.isAlive()) {
            HttpURLConnection connection = null;
            try {
                connection = openControllerConnection(port, "/configs");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("mihomo 控制器无法读取 TUN 状态");
                }
                JSONObject tun = new JSONObject(readResponseBody(connection)).optJSONObject("tun");
                if (tun != null
                        && tun.optBoolean("enable", false)
                        && tun.optInt("file-descriptor", 0) > 0) {
                    return;
                }
                lastFailure = new IOException("mihomo TUN 监听未使用 Android 文件描述符");
            } catch (IOException | JSONException exception) {
                lastFailure = exception instanceof IOException
                        ? (IOException) exception
                        : new IOException("无法解析 mihomo TUN 状态", exception);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(120);
        }
        if (!candidate.isAlive()) {
            throw new IOException(diagnosticMessage("mihomo 在建立 TUN 时退出"));
        }
        throw lastFailure == null
                ? new IOException("mihomo TUN 未在 10 秒内就绪")
                : lastFailure;
    }

    private HttpURLConnection openControllerConnection(int port, String path) throws IOException {
        URL url = new URL("http://" + HOST + ":" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(500);
        connection.setReadTimeout(1_000);
        connection.setRequestProperty("Authorization", "Bearer " + controllerSecret);
        return connection;
    }

    private static String readResponseBody(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("mihomo 控制器响应过大");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private String diagnosticMessage(String fallback) {
        String logs = recentLogSummary();
        return logs.isEmpty() ? fallback : fallback + "：" + logs;
    }

    private String recentLogSummary() {
        synchronized (logLock) {
            if (recentLogs.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : recentLogs) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(line);
                if (builder.length() > 2_000) {
                    break;
                }
            }
            return builder.toString();
        }
    }

    static RuntimeFiles ensureRuntimeFiles(Context context) throws IOException {
        File home = new File(context.getNoBackupFilesDir(), "mihomo");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建 mihomo 工作目录");
        }

        File config = new File(home, "config.yaml");
        if (!config.isFile()) {
            copyAssetFile(context, "default-config.yaml", config);
        }
        makeConfigReadOnly(config);
        copyAssetFileIfMissing(context, "geodata/GeoIP.dat", new File(home, "GeoIP.dat"));
        copyAssetFileIfMissing(context, "geodata/GeoSite.dat", new File(home, "GeoSite.dat"));

        File ui = new File(home, "ui");
        File marker = new File(ui, ".androidcyaml-version");
        String installedVersion = marker.isFile() ? readFile(marker).trim() : "";
        if (!BuildConfig.ZASHBOARD_VERSION.equals(installedVersion)
                || !new File(ui, "index.html").isFile()) {
            installDashboard(context, home, ui);
        }
        return new RuntimeFiles(home, config, ui);
    }

    static void validateConfig(Context context, File candidate)
            throws IOException, InterruptedException {
        RuntimeFiles files = ensureRuntimeFiles(context);
        File binary = coreBinary(context);
        if (!binary.isFile()) {
            throw new IOException("APK 中缺少 mihomo 核心");
        }
        Process process = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-d", files.home().getAbsolutePath(),
                "-f", candidate.getAbsolutePath(),
                "-t"
        ).directory(files.home()).redirectErrorStream(true).start();

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        Future<String> output = readerExecutor.submit(() -> readProcessOutput(process, 2 * 1024 * 1024));
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("mihomo 配置校验超时");
            }
            String diagnostics;
            try {
                diagnostics = output.get(5, TimeUnit.SECONDS).trim();
            } catch (ExecutionException | TimeoutException exception) {
                throw new IOException("无法读取 mihomo 配置校验结果", exception);
            }
            if (process.exitValue() != 0) {
                throw new IOException(diagnostics.isEmpty()
                        ? "mihomo 拒绝了 config.yaml"
                        : diagnostics);
            }
        } finally {
            output.cancel(true);
            readerExecutor.shutdownNow();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readProcessOutput(Process process, int maximumBytes) throws IOException {
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximumBytes) {
                    int remaining = maximumBytes - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, remaining);
                    }
                    break;
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    static void makeConfigReadOnly(File config) throws IOException {
        try {
            Os.chmod(config.getAbsolutePath(), OsConstants.S_IRUSR);
        } catch (ErrnoException exception) {
            throw new IOException("无法将 config.yaml 设为只读", exception);
        }
    }

    static void moveReplacing(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
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

    private static File coreBinary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libmihomo.so");
    }

    private static void terminateStaleCores(Context context) {
        String expected = coreBinary(context).getAbsolutePath();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return;
        }
        Set<Integer> pids = new LinkedHashSet<>();
        for (File entry : entries) {
            try {
                pids.add(Integer.parseInt(entry.getName()));
            } catch (NumberFormatException ignored) {
                // Non-process /proc entries are expected.
            }
        }
        for (int pid : pids) {
            if (pid <= 0 || pid == android.os.Process.myPid()) {
                continue;
            }
            try {
                String command = readCmdline(pid);
                if (expected.equals(command)) {
                    Log.w(TAG, "Stopping stale mihomo process " + pid);
                    Os.kill(pid, OsConstants.SIGTERM);
                    Thread.sleep(50);
                    if (new File("/proc/" + pid).exists()) {
                        Os.kill(pid, OsConstants.SIGKILL);
                    }
                }
            } catch (IOException | ErrnoException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String readCmdline(int pid) throws IOException {
        File file = new File("/proc/" + pid + "/cmdline");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            while (output.size() < 4096) {
                int value = input.read();
                if (value <= 0) {
                    break;
                }
                output.write(value);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void stopSpecificProcess(Process target) {
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

    private static int findAvailableControllerPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(true);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), PREFERRED_CONTROLLER_PORT),
                    1
            );
            return PREFERRED_CONTROLLER_PORT;
        } catch (IOException exception) {
            Log.w(TAG, "Preferred controller port is unavailable", exception);
        }
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress(InetAddress.getByName(HOST), 0), 1);
            return reservation.getLocalPort();
        }
    }

    private static String loadOrCreateSecret(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String existing = preferences.getString(CONTROLLER_SECRET_KEY, null);
        if (existing != null && existing.matches("[0-9a-f]{64}")) {
            return existing;
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        StringBuilder encoded = new StringBuilder(64);
        for (byte value : secret) {
            encoded.append(String.format("%02x", value & 0xff));
        }
        String generated = encoded.toString();
        if (!preferences.edit().putString(CONTROLLER_SECRET_KEY, generated).commit()) {
            throw new IllegalStateException("无法持久化 mihomo 控制器密钥");
        }
        return generated;
    }

    private static void copyAssetFileIfMissing(
            Context context,
            String assetPath,
            File destination
    ) throws IOException {
        if (!destination.isFile()) {
            copyAssetFile(context, assetPath, destination);
        }
    }

    private static void installDashboard(Context context, File home, File ui) throws IOException {
        File staging = new File(home, "ui.installing");
        File previous = new File(home, "ui.previous");
        deleteRecursively(staging);
        deleteRecursively(previous);
        if (!staging.mkdirs()) {
            throw new IOException("无法创建面板暂存目录");
        }
        copyAssetTree(context, "zashboard", staging);
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

    private static void copyAssetTree(Context context, String assetPath, File destination)
            throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(context, assetPath, destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination);
        }
        for (String entry : entries) {
            copyAssetTree(context, assetPath + "/" + entry, new File(destination, entry));
        }
    }

    private static void copyAssetFile(Context context, String assetPath, File destination)
            throws IOException {
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

    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    private static String shortCommit() {
        String commit = BuildConfig.MIHOMO_COMMIT;
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    record RuntimeFiles(File home, File config, File ui) {}
}
