package io.github.qwqgong.androidcyaml

class RuntimeOverrideSettings(
    processMatchingMode: ProcessMatchingMode?,
    @get:JvmName("ipv6Enabled") val ipv6Enabled: Boolean,
    logLevel: RuntimeLogLevel?,
    @get:JvmName("adaptiveTcpConcurrent") val adaptiveTcpConcurrent: Boolean,
    @get:JvmName("webViewXhttp") val webViewXhttp: Boolean,
    @get:JvmName("lanWebUiPublic") val lanWebUiPublic: Boolean,
) {
    @get:JvmName("processMatchingMode")
    val processMatchingMode: ProcessMatchingMode = processMatchingMode ?: ProcessMatchingMode.ALWAYS

    @get:JvmName("logLevel")
    val logLevel: RuntimeLogLevel = logLevel ?: RuntimeLogLevel.WARNING

    companion object {
        @JvmStatic
        fun defaults(): RuntimeOverrideSettings = RuntimeOverrideSettings(
            ProcessMatchingMode.ALWAYS,
            true,
            RuntimeLogLevel.WARNING,
            false,
            false,
            false,
        )
    }
}
