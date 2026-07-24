package io.github.qwqgong.androidcyaml;

import java.util.List;

record TunOptions(
        int mtu,
        List<String> inet4Address,
        List<String> inet6Address,
        boolean autoRoute,
        List<String> inet4RouteAddress,
        List<String> inet6RouteAddress,
        List<String> inet4RouteExcludeAddress,
        List<String> inet6RouteExcludeAddress,
        List<String> dnsServerAddress,
        List<String> includePackage,
        List<String> excludePackage
) {
    TunOptions {
        inet4Address = immutable(inet4Address);
        inet6Address = immutable(inet6Address);
        inet4RouteAddress = immutable(inet4RouteAddress);
        inet6RouteAddress = immutable(inet6RouteAddress);
        inet4RouteExcludeAddress = immutable(inet4RouteExcludeAddress);
        inet6RouteExcludeAddress = immutable(inet6RouteExcludeAddress);
        dnsServerAddress = immutable(dnsServerAddress);
        includePackage = immutable(includePackage);
        excludePackage = immutable(excludePackage);
    }

    String summary() {
        return "mtu=" + mtu
                + " ipv4=" + inet4Address
                + " ipv6=" + inet6Address
                + " autoRoute=" + autoRoute;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
