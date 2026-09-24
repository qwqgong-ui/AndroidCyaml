package io.github.qwqgong.androidcyaml

data class TunOptions(
    val mtu: Int,
    val inet4Address: List<String>,
    val inet6Address: List<String>,
    val dnsServerAddress: List<String>,
) {
    fun summary(): String =
        "mtu=$mtu ipv4=$inet4Address ipv6=$inet6Address fullRoute=true allApps=true"
}
