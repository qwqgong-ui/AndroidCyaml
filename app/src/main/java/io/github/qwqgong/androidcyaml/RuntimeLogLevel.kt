package io.github.qwqgong.androidcyaml

import java.util.Locale

enum class RuntimeLogLevel(val wireValue: String) {
    SILENT("silent"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    /**
     * Whether this level asks for mihomo's per-connection lines to be retained
     * in the diagnostics log.
     *
     * Those lines are the evidence that names a destination, so raising the log
     * level is what turns the diagnostics log from "a storm happened" into
     * "this is what it was dialing". They are also one line per connection,
     * which is why the quieter levels leave them out.
     */
    fun capturesConnections(): Boolean = this == INFO || this == DEBUG

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
