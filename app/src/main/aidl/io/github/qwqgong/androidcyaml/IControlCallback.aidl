package io.github.qwqgong.androidcyaml;

oneway interface IControlCallback {
    void onStateChanged(
            int state,
            String detail,
            boolean alwaysOn,
            boolean lockdown,
            String dashboardUrl,
            int controllerPort,
            String processMatchingMode,
            boolean ipv6Enabled,
            boolean ipv6Effective,
            String logLevel,
            boolean adaptiveTcpConcurrent,
            boolean webViewXhttp,
            boolean lanWebUiPublic
    );
}
