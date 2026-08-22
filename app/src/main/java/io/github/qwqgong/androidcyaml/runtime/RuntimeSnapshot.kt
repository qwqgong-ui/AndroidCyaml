package io.github.qwqgong.androidcyaml

data class RuntimeSnapshot(
    @get:JvmName("state") val state: RuntimeState,
    @get:JvmName("detail") val detail: String,
    @get:JvmName("dashboardUrl") val dashboardUrl: String,
    @get:JvmName("controllerPort") val controllerPort: Int,
) {
    companion object {
        @JvmStatic
        fun stopped(): RuntimeSnapshot = RuntimeSnapshot(RuntimeState.STOPPED, "VPN 未连接", "", 0)
    }
}
