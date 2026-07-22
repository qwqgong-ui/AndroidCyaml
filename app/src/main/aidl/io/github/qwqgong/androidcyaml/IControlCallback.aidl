package io.github.qwqgong.androidcyaml;

oneway interface IControlCallback {
    void onStateChanged(
            int coreState,
            String coreDetail,
            int vpnState,
            String vpnDetail,
            boolean alwaysOn,
            boolean lockdown,
            String dashboardUrl,
            int controllerPort,
            String processMatchOverride
    );
}
