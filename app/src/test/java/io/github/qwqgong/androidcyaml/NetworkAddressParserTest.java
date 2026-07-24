package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public final class NetworkAddressParserTest {
    @Test
    public void interfacePrefixPreservesIpv4HostBits() throws Exception {
        NetworkAddressParser.AddressPrefix prefix =
                NetworkAddressParser.parseAddressPrefix("172.19.0.1/30");

        assertEquals("172.19.0.1", prefix.address().getHostAddress());
        assertEquals(30, prefix.prefixLength());
    }

    @Test
    public void interfacePrefixPreservesIpv6HostBits() throws Exception {
        NetworkAddressParser.AddressPrefix prefix =
                NetworkAddressParser.parseAddressPrefix("fdfe:dcba:9876::1/126");

        assertEquals("fdfe:dcba:9876:0:0:0:0:1", prefix.address().getHostAddress());
        assertEquals(126, prefix.prefixLength());
    }

    @Test
    public void interfacePrefixRejectsInvalidLengths() {
        assertThrows(
                IOException.class,
                () -> NetworkAddressParser.parseAddressPrefix("172.19.0.1/33")
        );
        assertThrows(
                IOException.class,
                () -> NetworkAddressParser.parseAddressPrefix("fdfe::1/129")
        );
    }

    @Test
    public void underlyingStateSeparatesPathAndIpv6Changes() {
        Ipv6EnvironmentMonitor.State wifiIpv4 =
                new Ipv6EnvironmentMonitor.State(100L, "wlan0", false);
        Ipv6EnvironmentMonitor.State wifiIpv6 =
                new Ipv6EnvironmentMonitor.State(100L, "wlan0", true);
        Ipv6EnvironmentMonitor.State mobileIpv6 =
                new Ipv6EnvironmentMonitor.State(200L, "rmnet_data0", true);
        Ipv6EnvironmentMonitor.State wifiDnsChanged =
                new Ipv6EnvironmentMonitor.State(100L, "wlan0-new-dns", true);

        assertFalse(wifiIpv6.pathChangedFrom(wifiIpv4));
        assertTrue(wifiDnsChanged.pathChangedFrom(wifiIpv6));
        assertTrue(mobileIpv6.pathChangedFrom(wifiIpv6));
        assertTrue(Ipv6EnvironmentMonitor.State.unavailable().pathChangedFrom(wifiIpv6));
        assertTrue(wifiIpv4.available());
        assertFalse(Ipv6EnvironmentMonitor.State.unavailable().available());
    }
}
