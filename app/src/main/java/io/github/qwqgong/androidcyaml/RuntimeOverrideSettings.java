package io.github.qwqgong.androidcyaml;

record RuntimeOverrideSettings(
        boolean processMatching,
        boolean ipv6Enabled
) {
    static RuntimeOverrideSettings defaults() {
        return new RuntimeOverrideSettings(true, true);
    }
}
