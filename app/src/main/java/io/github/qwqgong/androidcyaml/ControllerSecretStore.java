package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

final class ControllerSecretStore {
    private static final String PREFERENCES = "androidcyaml";
    private static final String SECRET_KEY = "controller_secret";

    private ControllerSecretStore() {}

    static String getOrCreate(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        String existing = preferences.getString(SECRET_KEY, null);
        if (existing != null && existing.matches("[0-9a-f]{64}")) {
            return existing;
        }

        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        StringBuilder encoded = new StringBuilder(64);
        for (byte value : secret) {
            encoded.append(String.format("%02x", value & 0xff));
        }
        String generated = encoded.toString();
        if (!preferences.edit().putString(SECRET_KEY, generated).commit()) {
            throw new IllegalStateException("无法持久化 mihomo 控制器密钥");
        }
        return generated;
    }
}
