package io.github.qwqgong.androidcyaml

data class RuntimeOverrideSettings(
    val processMatchingMode: ProcessMatchingMode,
    val ipv6Enabled: Boolean,
    val logLevel: RuntimeLogLevel,
    val adaptiveTcpConcurrent: Boolean,
    val webViewXhttp: Boolean,
    val lanWebUiPublic: Boolean,
) {
    companion object {
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
