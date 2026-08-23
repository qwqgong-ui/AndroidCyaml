package io.github.qwqgong.androidcyaml

import java.util.Locale

enum class RuntimeLogLevel(val wireValue: String) {
    SILENT("silent"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    companion object {
        fun fromWireValue(value: String?): RuntimeLogLevel {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: ""
            return when (normalized) {
                "", "warning", "warn" -> WARNING
                "silent" -> SILENT
                "error" -> ERROR
                "info" -> INFO
                "debug" -> DEBUG
                else -> throw IllegalArgumentException("不支持的日志级别：$value")
            }
        }
    }
}
