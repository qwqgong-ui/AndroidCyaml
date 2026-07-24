package io.github.qwqgong.androidcyaml;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

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
                settings.processMatching()
        ));
        if (payload == null) {
            throw new IOException("mihomo 未返回 Android TUN 参数");
        }
        return TunOptionsCodec.fromJson(payload);
    }

    static void start(
            MihomoPaths paths,
            MihomoController controller,
            String secret,
            RuntimeOverrideSettings settings,
            boolean ipv6Enabled,
            int tunFileDescriptor,
            NativePlatformCallbacks callbacks
    ) throws IOException {
        requireSuccess(nativeStart(
                paths.home().getAbsolutePath(),
                paths.config().getAbsolutePath(),
                paths.ui().getAbsolutePath(),
                "127.0.0.1:" + controller.port(),
                secret,
                settings.tunStack().wireValue(),
                tunFileDescriptor,
                ipv6Enabled,
                settings.processMatching(),
                callbacks
        ));
    }

    static void stop() throws IOException {
        requireSuccess(nativeStop());
    }

    static void notifyNetworkChanged() throws IOException {
        requireSuccess(nativeNotifyNetworkChanged());
    }

    static boolean isRunning() {
        return nativeIsRunning();
    }

    static int trimMemory() {
        return nativeTrimMemory();
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
            boolean processMatching
    );

    private static native String nativeStart(
            String home,
            String configPath,
            String uiPath,
            String controllerAddress,
            String secret,
            String stack,
            int tunFileDescriptor,
            boolean ipv6Enabled,
            boolean processMatching,
            NativePlatformCallbacks callbacks
    );

    private static native String nativeStop();

    private static native String nativeNotifyNetworkChanged();

    private static native boolean nativeIsRunning();

    private static native int nativeTrimMemory();
}
