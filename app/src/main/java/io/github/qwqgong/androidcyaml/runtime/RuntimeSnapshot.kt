package io.github.qwqgong.androidcyaml

data class RuntimeSnapshot(
    val state: RuntimeState,
    val detail: String,
    val dashboardUrl: String,
    val controllerPort: Int,
) {
    companion object {
        fun stopped(): RuntimeSnapshot = RuntimeSnapshot(RuntimeState.STOPPED, "VPN 未连接", "", 0)
    }
}
