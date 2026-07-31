package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        TunStackMode tunStack,
        boolean processMatching,
        boolean ipv6Enabled,
        RuntimeLogLevel logLevel,
        boolean adaptiveTcpConcurrent,
        boolean lanWebUiPublic
) {
    RuntimeOverrideSettings {
        tunStack = tunStack == null ? TunStackMode.SYSTEM : tunStack;
        logLevel = logLevel == null ? RuntimeLogLevel.WARNING : logLevel;
    }

    static RuntimeOverrideSettings defaults() {
        return new RuntimeOverrideSettings(
                TunStackMode.SYSTEM,
                true,
                true,
                RuntimeLogLevel.WARNING,
                false,
                false
        );
    }
}
