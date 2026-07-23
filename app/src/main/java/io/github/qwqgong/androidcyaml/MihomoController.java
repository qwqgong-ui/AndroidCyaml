package io.github.qwqgong.androidcyaml;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class MihomoController {
    private static final String HOST = "127.0.0.1";
    private static final int PREFERRED_PORT = 17_890;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String secret;
    private final int port;

    private MihomoController(String secret, int port) {
        this.secret = secret;
        this.port = port;
    }

    static MihomoController reserve(String secret) throws IOException {
        return new MihomoController(secret, findAvailablePort());
    }

    int port() {
        return port;
    }

    String dashboardUrl() {
        return "http://" + HOST + ":" + port
                + "/ui/#/setup?hostname=" + HOST
                + "&port=" + port
                + "&secret=" + secret
                + "&disableUpgradeCore=1&disableTunMode=1&type=clash";
    }

    void awaitReady(Process process, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline && process.isAlive()) {
            HttpURLConnection connection = null;
            try {
                connection = open("/version");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return;
                }
            } catch (IOException ignored) {
                // Startup is polled until the deadline.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(120);
        }
        throw new IOException("mihomo 控制器未在 90 秒内就绪");
    }

    void awaitTun(Process process, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline && process.isAlive()) {
            HttpURLConnection connection = null;
            try {
                connection = open("/configs");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("mihomo 控制器无法读取 TUN 状态");
                }
                JSONObject tun = new JSONObject(readBody(connection)).optJSONObject("tun");
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
        if (!process.isAlive()) {
            throw new IOException("mihomo 在建立 TUN 时退出");
        }
        throw lastFailure == null
                ? new IOException("mihomo TUN 未在 10 秒内就绪")
                : lastFailure;
    }

    private HttpURLConnection open(String path) throws IOException {
        URL url = new URL("http://" + HOST + ":" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(500);
        connection.setReadTimeout(1_000);
        connection.setRequestProperty("Authorization", "Bearer " + secret);
        return connection;
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
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

    private static int findAvailablePort() throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(true);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), PREFERRED_PORT),
                    1
            );
            return PREFERRED_PORT;
        } catch (IOException ignored) {
            // Fall back to an ephemeral loopback origin.
        }
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress(InetAddress.getByName(HOST), 0), 1);
            return reservation.getLocalPort();
        }
    }
}
