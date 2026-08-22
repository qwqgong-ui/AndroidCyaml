package io.github.qwqgong.androidcyaml

import java.util.Locale

enum class ProcessMatchingMode(@get:JvmName("wireValue") val wireValue: String) {
    STRICT("strict"),
    ALWAYS("always"),
    OFF("off");

    companion object {
        @JvmStatic
        fun fromWireValue(value: String?): ProcessMatchingMode {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (normalized.isEmpty()) {
                return ALWAYS
            }
            for (mode in values()) {
                if (mode.wireValue == normalized) {
                    return mode
                }
            }
            throw IllegalArgumentException("不支持的进程匹配模式：$value")
        }
    }
}
