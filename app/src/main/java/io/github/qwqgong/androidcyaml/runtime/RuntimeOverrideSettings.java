package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        ProcessMatchingMode processMatchingMode,
        boolean ipv6Enabled,
        RuntimeLogLevel logLevel,
        boolean adaptiveTcpConcurrent,
        boolean webViewXhttp,
        boolean lanWebUiPublic
) {
    RuntimeOverrideSettings {
        processMatchingMode = processMatchingMode == null
                ? ProcessMatchingMode.ALWAYS
                : processMatchingMode;
        logLevel = logLevel == null ? RuntimeLogLevel.WARNING : logLevel;
    }

    static RuntimeOverrideSettings defaults() {
        return new RuntimeOverrideSettings(
                ProcessMatchingMode.ALWAYS,
                true,
                RuntimeLogLevel.WARNING,
                false,
                false,
                false
        );
    }
}
