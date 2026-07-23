package io.github.qwqgong.androidcyaml;

import java.util.Locale;

enum TunStackOverride {
    CONFIG("config"),
    GVISOR("gvisor");

    private final String wireValue;

    TunStackOverride(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    boolean forcesCoreStack() {
        return this == GVISOR;
    }

    static TunStackOverride fromWireValue(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "config" -> CONFIG;
            case "gvisor" -> GVISOR;
            case "system" -> throw new IllegalArgumentException("system TUN 覆写当前不可用");
            default -> throw new IllegalArgumentException("不支持的 TUN 栈覆写：" + value);
        };
    }
}
