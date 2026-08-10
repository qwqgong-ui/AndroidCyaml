package io.github.qwqgong.androidcyaml;

import java.util.Locale;

enum ProcessMatchingMode {
    STRICT("strict"),
    ALWAYS("always"),
    OFF("off");

    private final String wireValue;

    ProcessMatchingMode(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static ProcessMatchingMode fromWireValue(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return ALWAYS;
        }
        for (ProcessMatchingMode mode : values()) {
            if (mode.wireValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的进程匹配模式：" + value);
    }
}
