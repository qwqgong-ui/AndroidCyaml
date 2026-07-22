package io.github.qwqgong.androidcyaml;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Resolves the anonymous loopback SOCKS5 endpoint exposed by the active mihomo config. */
final class MihomoSocksEndpoint {
    private static final String HOST = "127.0.0.1";
    private static final int MAX_CONTROLLER_RESPONSE_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 500;
    private static final int READ_TIMEOUT_MILLIS = 500;

    private MihomoSocksEndpoint() {}

    static int awaitPort(
            MihomoManager manager,
            long timeout,
            TimeUnit unit,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastFailure = null;
        manager.ensureStarted();

        while (System.nanoTime() < deadline) {
            throwIfCancelled(cancelled);
            try {
                ControllerEndpoint controller = parseController(manager.getDashboardUrl());
                Set<Integer> ports = readRuntimePorts(controller);
                IOException transientFailure = null;
                TerminalConfigurationException terminalFailure = null;
                for (int port : ports) {
                    try {
                        verifyAnonymousSocks5(port);
                        return port;
                    } catch (TerminalConfigurationException exception) {
                        terminalFailure = exception;
                    } catch (IOException exception) {
                        transientFailure = exception;
                    }
                }
                if (transientFailure != null) {
                    throw transientFailure;
                }
                if (terminalFailure != null) {
                    throw terminalFailure;
                }
                throw new TerminalConfigurationException(
                        "config.yaml 必须启用 socks-port 或 mixed-port；Android VPN 不会改写配置"
                );
            } catch (TerminalConfigurationException exception) {
                throw exception;
            } catch (JSONException | IOException exception) {
                lastFailure = exception instanceof IOException
                        ? (IOException) exception
                        : new IOException("无法解析 mihomo 运行配置", exception);
            }
            sleepUntilRetry(cancelled);
        }

        throw lastFailure == null
                ? new IOException("mihomo 本地 SOCKS5 入站未在限定时间内就绪")
                : lastFailure;
    }

    private static ControllerEndpoint parseController(String dashboardUrl) throws IOException {
        Uri dashboard = Uri.parse(dashboardUrl);
        int port = dashboard.getPort();
        String fragment = dashboard.getFragment();
        if (port <= 0 || fragment == null) {
            throw new IOException("mihomo 控制器尚未就绪");
        }

        int queryStart = fragment.indexOf('?');
        if (queryStart < 0 || queryStart == fragment.length() - 1) {
            throw new IOException("内置面板缺少 mihomo 控制器凭据");
        }
        Uri fragmentQuery = Uri.parse("http://localhost/?" + fragment.substring(queryStart + 1));
        String secret = fragmentQuery.getQueryParameter("secret");
        if (secret == null || secret.isBlank()) {
            throw new IOException("内置面板缺少 mihomo 控制器密钥");
        }
        return new ControllerEndpoint(port, secret);
    }

    private static Set<Integer> readRuntimePorts(ControllerEndpoint controller)
            throws IOException, JSONException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + HOST + ":" + controller.port + "/configs");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Authorization", "Bearer " + controller.secret);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("mihomo 控制器无法读取本地代理端口");
            }

            JSONObject config = new JSONObject(readResponseBody(connection));
            Set<Integer> ports = new LinkedHashSet<>();
            addValidPort(ports, config.optInt("socks-port", 0));
            addValidPort(ports, config.optInt("mixed-port", 0));
            if (ports.isEmpty()) {
                throw new TerminalConfigurationException(
                        "config.yaml 必须启用 socks-port 或 mixed-port；Android VPN 不会改写配置"
                );
            }
            return ports;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void addValidPort(Set<Integer> ports, int port) {
        if (port > 0 && port <= 65535) {
            ports.add(port);
        }
    }

    private static void verifyAnonymousSocks5(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
            socket.getOutputStream().flush();

            InputStream input = socket.getInputStream();
            int version = input.read();
            int method = input.read();
            if (version != 0x05) {
                throw new TerminalConfigurationException(
                        "配置中的 socks-port/mixed-port 不是可用的 SOCKS5 入站"
                );
            }
            if (method != 0x00) {
                throw new TerminalConfigurationException(
                        "Android VPN 需要允许 127.0.0.1 无认证访问 SOCKS5 入站"
                );
            }
        }
    }

    private static String readResponseBody(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CONTROLLER_RESPONSE_BYTES) {
                    throw new IOException("mihomo 控制器响应过大");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sleepUntilRetry(BooleanSupplier cancelled) throws InterruptedException {
        for (int elapsed = 0; elapsed < 120; elapsed += 20) {
            throwIfCancelled(cancelled);
            Thread.sleep(20);
        }
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("SOCKS5 endpoint resolution cancelled");
        }
    }

    private static final class ControllerEndpoint {
        final int port;
        final String secret;

        ControllerEndpoint(int port, String secret) {
            this.port = port;
            this.secret = secret;
        }
    }

    private static final class TerminalConfigurationException extends IOException {
        TerminalConfigurationException(String message) {
            super(message);
        }
    }
}
