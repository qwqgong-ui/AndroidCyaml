package io.github.qwqgong.androidcyaml

data class TunOptions(
    val mtu: Int,
    val inet4Address: List<String>,
    val inet6Address: List<String>,
    val autoRoute: Boolean,
    val inet4RouteAddress: List<String>,
    val inet6RouteAddress: List<String>,
    val inet4RouteExcludeAddress: List<String>,
    val inet6RouteExcludeAddress: List<String>,
    val dnsServerAddress: List<String>,
    val includePackage: List<String>,
    val excludePackage: List<String>,
) {
    fun summary(): String =
        "mtu=$mtu ipv4=$inet4Address ipv6=$inet6Address autoRoute=$autoRoute"
}
