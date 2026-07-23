package io.github.qwqgong.androidcyaml;

oneway interface IControlCallback {
    void onStateChanged(
            int state,
            String detail,
            boolean alwaysOn,
            boolean lockdown,
            String dashboardUrl,
            int controllerPort,
            boolean processMatching,
            boolean ipv6Enabled,
            boolean ipv6Effective
    );
}
