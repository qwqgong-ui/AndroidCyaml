package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;

final class RuntimeOverrideStore {
    private static final String PREFERENCES = "androidcyaml_runtime_overrides";
    private static final String TUN_STACK = "tun_stack";

    private final SharedPreferences preferences;

    RuntimeOverrideStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    TunStackOverride tunStackOverride() {
        String value = preferences.getString(TUN_STACK, TunStackOverride.CONFIG.wireValue());
        try {
            return TunStackOverride.fromWireValue(value);
        } catch (IllegalArgumentException ignored) {
            return TunStackOverride.CONFIG;
        }
    }

    void setTunStackOverride(TunStackOverride override) {
        TunStackOverride value = override == null ? TunStackOverride.CONFIG : override;
        boolean persisted = preferences.edit()
                .putString(TUN_STACK, value.wireValue())
                .commit();
        if (!persisted) {
            throw new IllegalStateException("无法保存运行时覆写");
        }
    }
}
