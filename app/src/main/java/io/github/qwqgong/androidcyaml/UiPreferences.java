package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;

final class UiPreferences {
    private static final String PREFERENCES = "androidcyaml_ui";
    private static final String AUTO_START_KEY = "auto_start_vpn";
    private static final String HIDE_RECENTS_KEY = "hide_from_recents";

    private final SharedPreferences preferences;

    UiPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    boolean autoStartEnabled() {
        return preferences.getBoolean(AUTO_START_KEY, false);
    }

    void setAutoStartEnabled(boolean enabled) {
        preferences.edit().putBoolean(AUTO_START_KEY, enabled).apply();
    }

    boolean hideFromRecents() {
        return preferences.getBoolean(HIDE_RECENTS_KEY, false);
    }

    void setHideFromRecents(boolean hidden) {
        preferences.edit().putBoolean(HIDE_RECENTS_KEY, hidden).apply();
    }
}
