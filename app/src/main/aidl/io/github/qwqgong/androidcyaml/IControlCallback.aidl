package io.github.qwqgong.androidcyaml;

oneway interface IControlCallback {
    void onStateChanged(
            int state,
            String detail,
            boolean alwaysOn,
            boolean lockdown,
            String dashboardUrl,
            int controllerPort,
            String tunStackOverride
    );
}
