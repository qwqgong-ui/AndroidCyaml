package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        TunStackMode tunStack,
        boolean processMatching,
        boolean ipv6Enabled
) {
    RuntimeOverrideSettings {
        tunStack = tunStack == null ? TunStackMode.SYSTEM : tunStack;
    }

    static RuntimeOverrideSettings defaults() {
        return new RuntimeOverrideSettings(TunStackMode.SYSTEM, true, true);
    }
}
