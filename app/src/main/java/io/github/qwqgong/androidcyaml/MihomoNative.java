package io.github.qwqgong.androidcyaml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;

final class MihomoNative {
    static {
        System.loadLibrary("androidcyaml");
    }

    private MihomoNative() {}

    static void validate(MihomoPaths paths, File candidate) throws IOException {
        requireSuccess(nativeValidate(
                paths.home().getAbsolutePath(),
                candidate.getAbsolutePath()
        ));
    }

    static TunOptions prepareTun(
            MihomoPaths paths,
            RuntimeOverrideSettings settings,
            boolean ipv6Enabled
    ) throws IOException {
        JSONObject payload = requireSuccess(nativePrepareTun(
                paths.home().getAbsolutePath(),
                paths.config().getAbsolutePath(),
                settings.tunStack().wireValue(),
                ipv6Enabled,
                settings.processMatchingMode().wireValue()
        ));
        if (payload == null) {
            throw new IOException("mihomo 未返回 Android TUN 参数");
        }
        return TunOptionsCodec.fromJson(payload);
    }

    static String start(
            MihomoPaths paths,
            MihomoController controller,
            RuntimeOverrideSettings settings,
            boolean ipv6Enabled,
            int tunFileDescriptor,
            NativePlatformCallbacks callbacks
    ) throws IOException {
        requireSuccess(nativeSetWebViewXhttpEnabled(
                settings.webViewXhttp(),
                settings.webViewXhttp() && callbacks.supportsWebViewXhttpStreamUp()
        ));
        final JSONObject payload;
        try {
            payload = requireSuccess(nativeStart(
                    paths.home().getAbsolutePath(),
                    paths.config().getAbsolutePath(),
                    paths.ui().getAbsolutePath(),
                    controller.listenerAddress(),
                    settings.tunStack().wireValue(),
                    settings.logLevel().wireValue(),
                    tunFileDescriptor,
                    ipv6Enabled,
                    settings.processMatchingMode().wireValue(),
                    settings.lanWebUiPublic(),
                    callbacks
            ));
        } catch (IOException startFailure) {
            try {
                requireSuccess(nativeSetWebViewXhttpEnabled(false, false));
            } catch (IOException disableFailure) {
                startFailure.addSuppressed(disableFailure);
            }
            throw startFailure;
        }
        if (payload == null || !payload.has("controllerSecret")) {
            throw new IOException("mihomo 未返回控制器鉴权信息");
        }
        return payload.optString("controllerSecret", "");
    }

    static void setTcpConcurrent(boolean enabled) throws IOException {
        requireSuccess(nativeSetTcpConcurrent(enabled));
    }

    static void stop() throws IOException {
        requireSuccess(nativeStop());
    }

    static void notifyNetworkChanged() throws IOException {
        requireSuccess(nativeNotifyNetworkChanged());
    }

    static void updateSystemDns(List<String> servers) throws IOException {
        requireSuccess(nativeUpdateSystemDns(
                new JSONArray(servers == null ? List.of() : servers).toString()
        ));
    }

    static boolean isRunning() {
        return nativeIsRunning();
    }

    static int trimMemory() {
        return nativeTrimMemory();
    }

    static int readBrowserRequestBody(long bodyId, byte[] destination) {
        return nativeReadBrowserRequestBody(bodyId, destination);
    }

    static void closeBrowserRequestBody(long bodyId) {
        if (bodyId > 0L) {
            nativeCloseBrowserRequestBody(bodyId);
        }
    }

    static boolean pushBrowserResponseChunk(long requestId, byte[] chunk) {
        return requestId > 0L
                && chunk != null
                && chunk.length > 0
                && nativePushBrowserResponseChunk(requestId, chunk);
    }

    static boolean finishBrowserResponse(long requestId, String error) {
        return requestId > 0L
                && nativeFinishBrowserResponse(requestId, error == null ? "" : error);
    }

    private static JSONObject requireSuccess(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("JNI 核心返回空结果");
        }
        try {
            JSONObject response = new JSONObject(raw);
            if (!response.optBoolean("ok", false)) {
                String error = response.optString("error", "mihomo JNI 操作失败");
                throw new IOException(error.isBlank() ? "mihomo JNI 操作失败" : error);
            }
            return response.optJSONObject("payload");
        } catch (JSONException exception) {
            throw new IOException("无法解析 mihomo JNI 结果", exception);
        }
    }

    private static native String nativeValidate(String home, String configPath);

    private static native String nativePrepareTun(
            String home,
            String configPath,
            String stack,
            boolean ipv6Enabled,
            String processMatchingMode
    );

    private static native String nativeSetWebViewXhttpEnabled(
            boolean enabled,
            boolean requestStreamsSupported
    );

    private static native int nativeReadBrowserRequestBody(long bodyId, byte[] destination);

    private static native void nativeCloseBrowserRequestBody(long bodyId);

    private static native boolean nativePushBrowserResponseChunk(long requestId, byte[] chunk);

    private static native boolean nativeFinishBrowserResponse(long requestId, String error);

    private static native String nativeStart(
            String home,
            String configPath,
            String uiPath,
            String controllerAddress,
            String stack,
            String logLevel,
            int tunFileDescriptor,
            boolean ipv6Enabled,
            String processMatchingMode,
            boolean lanWebUiPublic,
            NativePlatformCallbacks callbacks
    );

    private static native String nativeSetTcpConcurrent(boolean enabled);

    private static native String nativeStop();

    private static native String nativeNotifyNetworkChanged();

    private static native String nativeUpdateSystemDns(String serversJson);

    private static native boolean nativeIsRunning();

    private static native int nativeTrimMemory();
}
