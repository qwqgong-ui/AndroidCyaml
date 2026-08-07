package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;

final class RuntimeOverrideStore {
    private static final String PREFERENCES = "androidcyaml_runtime_overrides";
    private static final String PROCESS_MATCHING = "process_matching";
    private static final String IPV6_ENABLED = "ipv6_enabled";
    private static final String LOG_LEVEL = "log_level";
    private static final String ADAPTIVE_TCP_CONCURRENT = "adaptive_tcp_concurrent";
    private static final String WEBVIEW_XHTTP = "webview_xhttp";
    private static final String LAN_WEB_UI_PUBLIC = "lan_web_ui_public";

    private final SharedPreferences preferences;

    RuntimeOverrideStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    RuntimeOverrideSettings settings() {
        RuntimeLogLevel logLevel;
        try {
            logLevel = RuntimeLogLevel.fromWireValue(
                    preferences.getString(LOG_LEVEL, "warning")
            );
        } catch (IllegalArgumentException ignored) {
            logLevel = RuntimeLogLevel.WARNING;
        }
        return new RuntimeOverrideSettings(
                TunStackMode.SYSTEM,
                preferences.getBoolean(PROCESS_MATCHING, true),
                preferences.getBoolean(IPV6_ENABLED, true),
                logLevel,
                preferences.getBoolean(ADAPTIVE_TCP_CONCURRENT, false),
                preferences.getBoolean(WEBVIEW_XHTTP, false),
                preferences.getBoolean(LAN_WEB_UI_PUBLIC, false)
        );
    }

    void setSettings(RuntimeOverrideSettings settings) {
        RuntimeOverrideSettings value = settings == null
                ? RuntimeOverrideSettings.defaults()
                : settings;
        boolean persisted = preferences.edit()
                .putBoolean(PROCESS_MATCHING, value.processMatching())
                .putBoolean(IPV6_ENABLED, value.ipv6Enabled())
                .putString(LOG_LEVEL, value.logLevel().wireValue())
                .putBoolean(ADAPTIVE_TCP_CONCURRENT, value.adaptiveTcpConcurrent())
                .putBoolean(WEBVIEW_XHTTP, value.webViewXhttp())
                .putBoolean(LAN_WEB_UI_PUBLIC, value.lanWebUiPublic())
                .remove("tun_stack_mode")
                .remove("tun_stack")
                .commit();
        if (!persisted) {
            throw new IllegalStateException("无法保存运行时覆写");
        }
    }
}
