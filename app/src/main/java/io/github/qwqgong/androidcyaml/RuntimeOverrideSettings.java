package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        TunStackMode tunStack,
        ProcessMatchingMode processMatchingMode,
        boolean ipv6Enabled,
        RuntimeLogLevel logLevel,
        boolean adaptiveTcpConcurrent,
        boolean webViewXhttp,
        boolean lanWebUiPublic
) {
    RuntimeOverrideSettings {
        tunStack = TunStackMode.SYSTEM;
        processMatchingMode = processMatchingMode == null
                ? ProcessMatchingMode.ALWAYS
                : processMatchingMode;
        logLevel = logLevel == null ? RuntimeLogLevel.WARNING : logLevel;
    }

    RuntimeOverrideSettings(
            TunStackMode tunStack,
            ProcessMatchingMode processMatchingMode,
            boolean ipv6Enabled,
            RuntimeLogLevel logLevel,
            boolean adaptiveTcpConcurrent,
            boolean lanWebUiPublic
    ) {
        this(
                tunStack,
                processMatchingMode,
                ipv6Enabled,
                logLevel,
                adaptiveTcpConcurrent,
                false,
                lanWebUiPublic
        );
    }

    static RuntimeOverrideSettings defaults() {
        return new RuntimeOverrideSettings(
                TunStackMode.SYSTEM,
                ProcessMatchingMode.ALWAYS,
                true,
                RuntimeLogLevel.WARNING,
                false,
                false,
                false
        );
    }
}
