package io.github.qwqgong.androidcyaml;

import java.util.Locale;

enum TunStackMode {
    SYSTEM("system"),
    GVISOR("gvisor"),
    MIXED("mixed");

    private final String wireValue;

    TunStackMode(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static TunStackMode fromWireValue(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "system" -> SYSTEM;
            case "gvisor" -> GVISOR;
            case "mixed" -> MIXED;
            default -> throw new IllegalArgumentException("不支持的 TUN 栈：" + value);
        };
    }
}
