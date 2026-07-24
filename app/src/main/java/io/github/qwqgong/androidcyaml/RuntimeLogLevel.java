package io.github.qwqgong.androidcyaml;

import java.util.Locale;

enum RuntimeLogLevel {
    SILENT("silent"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    private final String wireValue;

    RuntimeLogLevel(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static RuntimeLogLevel fromWireValue(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "warning", "warn" -> WARNING;
            case "silent" -> SILENT;
            case "error" -> ERROR;
            case "info" -> INFO;
            case "debug" -> DEBUG;
            default -> throw new IllegalArgumentException("不支持的日志级别：" + value);
        };
    }
}
