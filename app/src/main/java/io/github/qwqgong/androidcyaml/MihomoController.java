package io.github.qwqgong.androidcyaml;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

final class MihomoController {
    record SelectionRestoreResult(int restored, int fallback, int skipped, int failed) {}

    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String PUBLIC_HOST = "0.0.0.0";
    private static final int PREFERRED_PORT = 17_890;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<String> AUTOMATIC_PROXY_TYPES = Set.of(
            "urltest",
            "fallback",
            "loadbalance",
            "smart"
    );

    private final String listenerHost;
    private final int port;
    private String secret = "";

    private MihomoController(String listenerHost, int port) {
        this.listenerHost = listenerHost;
        this.port = port;
    }

    static MihomoController reserve(boolean lanWebUiPublic) throws IOException {
        String listenerHost = lanWebUiPublic ? PUBLIC_HOST : LOCAL_HOST;
        return new MihomoController(listenerHost, findAvailablePort(listenerHost));
    }

    int port() {
        return port;
    }

    String listenerAddress() {
        return listenerHost + ":" + port;
    }

    void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    String dashboardUrl() {
        return "http://" + LOCAL_HOST + ":" + port
                + "/ui/#/setup?hostname=" + LOCAL_HOST
                + "&port=" + port
                + "&secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&disableUpgradeCore=1&disableTunMode=1&type=clash";
    }

    void awaitReady(long timeout, TimeUnit unit) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline && MihomoNative.isRunning()) {
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
        if (!MihomoNative.isRunning()) {
            throw new IOException("mihomo JNI 核心已停止");
        }
        throw new IOException("mihomo 控制器未在 90 秒内就绪");
    }

    void awaitTun(long timeout, TimeUnit unit) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline && MihomoNative.isRunning()) {
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
                lastFailure = new IOException("mihomo TUN 未使用 Android 文件描述符");
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
        if (!MihomoNative.isRunning()) {
            throw new IOException("mihomo JNI 核心在建立 TUN 时停止");
        }
        throw lastFailure == null
                ? new IOException("mihomo TUN 未在 10 秒内就绪")
                : lastFailure;
    }

    Map<String, String> selectorSelections() throws IOException {
        JSONObject proxies = proxySnapshot();
        Map<String, String> selections = new TreeMap<>();
        Iterator<String> names = proxies.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject proxy = proxies.optJSONObject(name);
            if (!isSelector(proxy)) {
                continue;
            }
            String selected = proxy.optString("now", "");
            if (!selected.isBlank() && isAvailable(proxies, selected)) {
                selections.put(name, selected);
            }
        }
        return Map.copyOf(selections);
    }

    SelectionRestoreResult restoreSelectorSelections(Map<String, String> remembered)
            throws IOException {
        if (remembered == null || remembered.isEmpty()) {
            return new SelectionRestoreResult(0, 0, 0, 0);
        }
        JSONObject proxies = proxySnapshot();
        int restored = 0;
        int fallback = 0;
        int skipped = 0;
        int failed = 0;
        for (Map.Entry<String, String> selection : new TreeMap<>(remembered).entrySet()) {
            JSONObject selector = proxies.optJSONObject(selection.getKey());
            if (!isSelector(selector)) {
                skipped++;
                continue;
            }
            String target = selection.getValue();
            if (!containsTarget(selector, target) || !isAvailable(proxies, target)) {
                target = automaticFallback(selector, proxies);
                if (target.isBlank()) {
                    skipped++;
                    continue;
                }
                fallback++;
            } else {
                restored++;
            }
            if (target.equals(selector.optString("now", ""))) {
                continue;
            }
            try {
                selectProxy(selection.getKey(), target);
                selector.put("now", target);
            } catch (IOException | JSONException exception) {
                failed++;
            }
        }
        return new SelectionRestoreResult(restored, fallback, skipped, failed);
    }

    private JSONObject proxySnapshot() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open("/proxies");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "mihomo 控制器无法读取策略组：HTTP "
                                + connection.getResponseCode()
                );
            }
            JSONObject proxies = new JSONObject(readBody(connection)).optJSONObject("proxies");
            if (proxies == null) {
                throw new IOException("mihomo 控制器未返回策略组");
            }
            return proxies;
        } catch (JSONException exception) {
            throw new IOException("无法解析 mihomo 策略组", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void selectProxy(String group, String target) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open("/proxies/" + encodePathSegment(group));
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body;
            try {
                body = new JSONObject().put("name", target).toString()
                        .getBytes(StandardCharsets.UTF_8);
            } catch (JSONException impossible) {
                throw new AssertionError("Unable to encode proxy selection", impossible);
            }
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK
                    && responseCode != HttpURLConnection.HTTP_NO_CONTENT) {
                throw new IOException(
                        "mihomo 策略组切换失败：HTTP " + responseCode
                );
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isSelector(JSONObject proxy) {
        return proxy != null && "selector".equalsIgnoreCase(proxy.optString("type", ""));
    }

    private static boolean containsTarget(JSONObject selector, String target) {
        JSONArray all = selector.optJSONArray("all");
        if (all == null || target == null || target.isBlank()) {
            return false;
        }
        for (int index = 0; index < all.length(); index++) {
            if (target.equals(all.optString(index, ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAvailable(JSONObject proxies, String target) {
        JSONObject proxy = proxies.optJSONObject(target);
        return proxy != null && (!proxy.has("alive") || proxy.optBoolean("alive", true));
    }

    private static String automaticFallback(JSONObject selector, JSONObject proxies) {
        JSONArray all = selector.optJSONArray("all");
        if (all == null) {
            return "";
        }
        for (int index = 0; index < all.length(); index++) {
            String candidate = all.optString(index, "");
            JSONObject proxy = proxies.optJSONObject(candidate);
            if (proxy == null || !isAvailable(proxies, candidate)) {
                continue;
            }
            String type = proxy.optString("type", "").replace("-", "")
                    .toLowerCase(java.util.Locale.ROOT);
            if (AUTOMATIC_PROXY_TYPES.contains(type)) {
                return candidate;
            }
        }
        return "";
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpURLConnection open(String path) throws IOException {
        URL url = new URL("http://" + LOCAL_HOST + ":" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(500);
        connection.setReadTimeout(1_000);
        if (!secret.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + secret);
        }
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

    private static int findAvailablePort(String listenerHost) throws IOException {
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(true);
            reservation.bind(
                    new InetSocketAddress(
                            InetAddress.getByName(listenerHost),
                            PREFERRED_PORT
                    ),
                    1
            );
            return PREFERRED_PORT;
        } catch (IOException ignored) {
            // Fall back to an ephemeral address on the same listener interface.
        }
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(
                    new InetSocketAddress(InetAddress.getByName(listenerHost), 0),
                    1
            );
            return reservation.getLocalPort();
        }
    }
}
