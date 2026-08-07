package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        TunStackMode tunStack,
        boolean processMatching,
        boolean ipv6Enabled,
        RuntimeLogLevel logLevel,
        boolean adaptiveTcpConcurrent,
        boolean webViewXhttp,
        boolean lanWebUiPublic
) {
    RuntimeOverrideSettings {
        tunStack = TunStackMode.SYSTEM;
        logLevel = logLevel == null ? RuntimeLogLevel.WARNING : logLevel;
    }

    RuntimeOverrideSettings(
            TunStackMode tunStack,
            boolean processMatching,
            boolean ipv6Enabled,
            RuntimeLogLevel logLevel,
            boolean adaptiveTcpConcurrent,
            boolean lanWebUiPublic
    ) {
        this(
                tunStack,
                processMatching,
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
                true,
                true,
                RuntimeLogLevel.WARNING,
                false,
                false,
                false
        );
    }
}
