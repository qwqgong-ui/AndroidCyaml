package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;

final class RuntimeOverrideStore {
    private static final String PREFERENCES = "androidcyaml_runtime_overrides";
    private static final String TUN_STACK = "tun_stack_mode";
    private static final String PROCESS_MATCHING = "process_matching";
    private static final String IPV6_ENABLED = "ipv6_enabled";

    private final SharedPreferences preferences;

    RuntimeOverrideStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    RuntimeOverrideSettings settings() {
        TunStackMode stack;
        try {
            stack = TunStackMode.fromWireValue(preferences.getString(TUN_STACK, "system"));
        } catch (IllegalArgumentException ignored) {
            stack = TunStackMode.SYSTEM;
        }
        return new RuntimeOverrideSettings(
                stack,
                preferences.getBoolean(PROCESS_MATCHING, true),
                preferences.getBoolean(IPV6_ENABLED, true)
        );
    }

    void setSettings(RuntimeOverrideSettings settings) {
        RuntimeOverrideSettings value = settings == null
                ? RuntimeOverrideSettings.defaults()
                : settings;
        boolean persisted = preferences.edit()
                .putString(TUN_STACK, value.tunStack().wireValue())
                .putBoolean(PROCESS_MATCHING, value.processMatching())
                .putBoolean(IPV6_ENABLED, value.ipv6Enabled())
                .remove("tun_stack")
                .commit();
        if (!persisted) {
            throw new IllegalStateException("无法保存运行时覆写");
        }
    }
}
