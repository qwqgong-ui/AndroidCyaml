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
import java.util.HashSet;
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
    private static final int SELECTION_APPLY_ATTEMPTS = 3;
    private static final long SELECTION_VERIFY_DELAY_MILLIS = 100L;
    private static final Set<String> AUTOMATIC_PROXY_TYPES = Set.of(
            "urltest",
            "fallback",
            "loadbalance",
            "smart"
    );

    private final String listenerHost;
    private final int port;
    private String secret = "";
    private String primarySelector = "";

    void setPrimarySelector(String name) {
        primarySelector = name == null ? "" : name.trim();
    }

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
        return selectorSelections(proxySnapshot());
    }

    Map<String, String> selectorSelections(JSONObject proxies) {
        Map<String, String> selections = new TreeMap<>();
        Iterator<String> names = proxies.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals(primarySelector)) {
                continue;
            }
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

    JSONObject selectorCatalog(Map<String, String> overrides) throws IOException {
        return selectorCatalogFor(proxySnapshot(), overrides);
    }

    JSONObject selectorCatalogFor(JSONObject proxies, Map<String, String> overrides)
            throws IOException {
        if (primarySelector.isBlank()) {
            throw new IOException("config.yaml 的第一个策略组不是 Selector");
        }
        JSONObject all = selectorCatalog(
                proxies,
                overrides == null ? Map.of() : overrides
        );
        JSONObject primary = all.optJSONObject(primarySelector);
        if (primary == null) {
            throw new IOException("第一个 Selector 策略组已不存在：" + primarySelector);
        }
        try {
            return new JSONObject().put(primarySelector, primary);
        } catch (JSONException impossible) {
            throw new AssertionError("Unable to encode primary selector", impossible);
        }
    }

    void selectSelector(String group, String target) throws IOException {
        if (!group.equals(primarySelector)) {
            throw new IOException("只允许设置 config.yaml 的第一个 Selector 策略组");
        }
        JSONObject proxies = proxySnapshot();
        JSONObject selector = proxies.optJSONObject(group);
        if (!isSelector(selector) || !containsTarget(selector, target)) {
            throw new IOException("策略组或目标已不存在");
        }
        if (!isAvailable(proxies, target)) {
            throw new IOException("目标节点当前不可用");
        }
        selectProxyAndVerify(group, target);
    }

    static JSONObject selectorCatalog(
            JSONObject proxies,
            Map<String, String> overrides
    ) throws IOException {
        try {
            JSONObject result = new JSONObject();
            java.util.List<String> names = new java.util.ArrayList<>();
            Iterator<String> keys = proxies.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (isSelector(proxies.optJSONObject(name))) {
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            for (String name : names) {
                JSONObject selector = proxies.optJSONObject(name);
                String selected = overrides.getOrDefault(
                        name,
                        selector.optString("now", "")
                );
                if (!containsTarget(selector, selected)) {
                    selected = selector.optString("now", "");
                }
                JSONObject encoded = new JSONObject();
                encoded.put("selected", selected);
                encoded.put(
                        "effective",
                        effectiveNode(proxies, selected, overrides, new HashSet<>())
                );
                JSONArray options = new JSONArray();
                JSONArray all = selector.optJSONArray("all");
                if (all != null) {
                    for (int index = 0; index < all.length(); index++) {
                        String target = all.optString(index, "");
                        if (target.isBlank()) {
                            continue;
                        }
                        JSONObject option = new JSONObject();
                        option.put("name", target);
                        option.put(
                                "effective",
                                effectiveNode(proxies, target, overrides, new HashSet<>())
                        );
                        option.put("available", isAvailable(proxies, target));
                        options.put(option);
                    }
                }
                encoded.put("options", options);
                result.put(name, encoded);
            }
            return result;
        } catch (JSONException exception) {
            throw new IOException("无法生成策略节点目录", exception);
        }
    }

    private static String effectiveNode(
            JSONObject proxies,
            String name,
            Map<String, String> overrides,
            Set<String> visited
    ) {
        if (name == null || name.isBlank() || !visited.add(name)) {
            return name == null ? "" : name;
        }
        JSONObject proxy = proxies.optJSONObject(name);
        if (proxy == null) {
            return name;
        }
        String type = proxy.optString("type", "").replace("-", "")
                .toLowerCase(java.util.Locale.ROOT);
        if (!isSelector(proxy) && !AUTOMATIC_PROXY_TYPES.contains(type)) {
            return name;
        }
        String next = isSelector(proxy)
                ? overrides.getOrDefault(name, proxy.optString("now", ""))
                : proxy.optString("now", "");
        if (next.isBlank()) {
            return name;
        }
        return effectiveNode(proxies, next, overrides, visited);
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
            if (!selection.getKey().equals(primarySelector)) {
                skipped++;
                continue;
            }
            JSONObject selector = proxies.optJSONObject(selection.getKey());
            if (!isSelector(selector)) {
                skipped++;
                continue;
            }
            String target = selection.getValue();
            boolean usedFallback = false;
            if (!containsTarget(selector, target) || !isAvailable(proxies, target)) {
                target = automaticFallback(selector, proxies);
                if (target.isBlank()) {
                    skipped++;
                    continue;
                }
                usedFallback = true;
            }
            if (target.equals(selector.optString("now", ""))) {
                if (usedFallback) {
                    fallback++;
                } else {
                    restored++;
                }
                continue;
            }
            try {
                selectProxyAndVerify(selection.getKey(), target);
                selector.put("now", target);
                if (usedFallback) {
                    fallback++;
                } else {
                    restored++;
                }
            } catch (IOException | JSONException exception) {
                failed++;
            }
        }
        return new SelectionRestoreResult(restored, fallback, skipped, failed);
    }

    JSONObject proxySnapshot() throws IOException {
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

    /**
     * A successful PUT only means the controller accepted the request. During
     * an underlying-network handover the core can concurrently reset its
     * persistent state, so the accepted selection may be lost. Always read
     * the Selector back and retry before reporting a background restore as
     * successful.
     */
    private void selectProxyAndVerify(String group, String target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < SELECTION_APPLY_ATTEMPTS; attempt++) {
            try {
                selectProxy(group, target);
                JSONObject selector = proxySnapshot().optJSONObject(group);
                if (selector != null && target.equals(selector.optString("now", ""))) {
                    return;
                }
                lastFailure = new IOException("策略组切换未生效：" + group + " -> " + target);
            } catch (IOException exception) {
                lastFailure = exception;
            }
            if (attempt + 1 < SELECTION_APPLY_ATTEMPTS) {
                try {
                    Thread.sleep(SELECTION_VERIFY_DELAY_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("策略组切换验证被中断", exception);
                }
            }
        }
        throw lastFailure == null
                ? new IOException("策略组切换未生效")
                : lastFailure;
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
