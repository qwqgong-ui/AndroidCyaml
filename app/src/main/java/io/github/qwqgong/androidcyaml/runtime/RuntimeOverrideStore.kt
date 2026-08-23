package io.github.qwqgong.androidcyaml

import android.content.Context

class RuntimeOverrideStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun settings(): RuntimeOverrideSettings {
        val logLevel = try {
            RuntimeLogLevel.fromWireValue(preferences.getString(LOG_LEVEL, "warning"))
        } catch (ignored: IllegalArgumentException) {
            RuntimeLogLevel.WARNING
        }
        val processMatchingMode = if (preferences.contains(PROCESS_MATCHING_MODE)) {
            try {
                ProcessMatchingMode.fromWireValue(
                    preferences.getString(PROCESS_MATCHING_MODE, "always"),
                )
            } catch (ignored: ClassCastException) {
                ProcessMatchingMode.ALWAYS
            } catch (ignored: IllegalArgumentException) {
                ProcessMatchingMode.ALWAYS
            }
        } else {
            try {
                if (preferences.getBoolean(LEGACY_PROCESS_MATCHING, true)) {
                    ProcessMatchingMode.ALWAYS
                } else {
                    ProcessMatchingMode.OFF
                }
            } catch (ignored: ClassCastException) {
                ProcessMatchingMode.ALWAYS
            }
        }
        return RuntimeOverrideSettings(
            processMatchingMode,
            preferences.getBoolean(IPV6_ENABLED, true),
            logLevel,
            preferences.getBoolean(ADAPTIVE_TCP_CONCURRENT, false),
            preferences.getBoolean(WEBVIEW_XHTTP, false),
            preferences.getBoolean(LAN_WEB_UI_PUBLIC, false),
        )
    }

    fun setSettings(settings: RuntimeOverrideSettings?) {
        val value = settings ?: RuntimeOverrideSettings.defaults()
        val persisted = preferences.edit()
            .putString(PROCESS_MATCHING_MODE, value.processMatchingMode.wireValue)
            .putBoolean(IPV6_ENABLED, value.ipv6Enabled)
            .putString(LOG_LEVEL, value.logLevel.wireValue)
            .putBoolean(ADAPTIVE_TCP_CONCURRENT, value.adaptiveTcpConcurrent)
            .putBoolean(WEBVIEW_XHTTP, value.webViewXhttp)
            .putBoolean(LAN_WEB_UI_PUBLIC, value.lanWebUiPublic)
            .remove("tun_stack_mode")
            .remove(LEGACY_PROCESS_MATCHING)
            .remove("tun_stack")
            .commit()
        if (!persisted) {
            throw IllegalStateException("无法保存运行时覆写")
        }
    }

    private companion object {
        const val PREFERENCES = "androidcyaml_runtime_overrides"
        const val PROCESS_MATCHING_MODE = "process_matching_mode"
        const val LEGACY_PROCESS_MATCHING = "process_matching"
        const val IPV6_ENABLED = "ipv6_enabled"
        const val LOG_LEVEL = "log_level"
        const val ADAPTIVE_TCP_CONCURRENT = "adaptive_tcp_concurrent"
        const val WEBVIEW_XHTTP = "webview_xhttp"
        const val LAN_WEB_UI_PUBLIC = "lan_web_ui_public"
    }
}
