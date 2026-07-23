package io.github.qwqgong.androidcyaml;

record RuntimeSnapshot(
        RuntimeState state,
        String detail,
        String dashboardUrl,
        int controllerPort
) {
    static RuntimeSnapshot stopped() {
        return new RuntimeSnapshot(RuntimeState.STOPPED, "VPN 未连接", "", 0);
    }
}
