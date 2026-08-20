package io.github.qwqgong.androidcyaml;

import android.content.pm.ServiceInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

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
    public void underlyingStateSeparatesPathIpv6AndTransportChanges() {
        Ipv6EnvironmentMonitor.State wifiIpv4 =
                new Ipv6EnvironmentMonitor.State(
                        100L, "wlan0", false, true, List.of("192.168.1.1"), "home"
                );
        Ipv6EnvironmentMonitor.State wifiIpv6 =
                new Ipv6EnvironmentMonitor.State(
                        100L, "wlan0", true, true, List.of("192.168.1.1"), "home"
                );
        Ipv6EnvironmentMonitor.State mobileIpv6 =
                new Ipv6EnvironmentMonitor.State(
                        200L, "rmnet_data0", true, false, List.of("10.0.0.1"), "mobile"
                );
        Ipv6EnvironmentMonitor.State wifiDnsChanged =
                new Ipv6EnvironmentMonitor.State(
                        100L, "wlan0-new-dns", true, true, List.of("1.1.1.1"), "home"
                );
        Ipv6EnvironmentMonitor.State officeWifi =
                new Ipv6EnvironmentMonitor.State(
                        100L, "wlan0", true, true, List.of("192.168.1.1"), "office"
                );

        assertFalse(wifiIpv6.pathChangedFrom(wifiIpv4));
        assertTrue(wifiDnsChanged.pathChangedFrom(wifiIpv6));
        assertTrue(mobileIpv6.pathChangedFrom(wifiIpv6));
        assertFalse(officeWifi.pathChangedFrom(wifiIpv6));
        assertFalse(officeWifi.equals(wifiIpv6));
        assertTrue(Ipv6EnvironmentMonitor.State.unavailable().pathChangedFrom(wifiIpv6));
        assertTrue(wifiIpv4.available());
        assertTrue(wifiIpv6.wifi());
        assertEquals(List.of("192.168.1.1"), wifiIpv6.dnsServers());
        assertEquals("home", wifiIpv6.selectionIdentity());
        assertFalse(mobileIpv6.wifi());
        assertFalse(Ipv6EnvironmentMonitor.State.unavailable().available());
    }

    @Test
    public void wifiSelectionIdentitySurvivesAccessPointRoaming() {
        String firstAccessPoint = NetworkIdentityResolver.wifiFingerprint(
                "Home", "00:11:22:33:44:55"
        );
        String secondAccessPoint = NetworkIdentityResolver.wifiFingerprint(
                "Home", "66:77:88:99:aa:bb"
        );

        assertEquals(firstAccessPoint, secondAccessPoint);
        assertFalse(firstAccessPoint.equals(
                NetworkIdentityResolver.wifiFingerprint("Office", "00:11:22:33:44:55")
        ));
    }

    @Test
    public void wifiSelectionIdentityFallsBackToBssidWhenSsidIsUnavailable() {
        assertFalse(NetworkIdentityResolver.wifiFingerprint(
                "", "00:11:22:33:44:55"
        ).equals(NetworkIdentityResolver.wifiFingerprint(
                "", "66:77:88:99:aa:bb"
        )));
    }

    @Test
    public void cellularSelectionIdentityUsesStableCarrierProperties() {
        String identity = NetworkIdentityResolver.cellularFingerprint(
                1, "46000", 1435, "Carrier"
        );

        assertEquals(identity, NetworkIdentityResolver.cellularFingerprint(
                1, "46000", 1435, "Localized carrier name"
        ));
        assertFalse(identity.equals(NetworkIdentityResolver.cellularFingerprint(
                2, "46000", 1435, "Carrier"
        )));
    }

    @Test
    public void foregroundVpnStartKeepsWifiIdentityLocationAccessWhenGranted() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                AndroidVpnService.foregroundServiceTypes(true, true, true)
        );
    }

    @Test
    public void coarseOnlyBackgroundOrDeniedStartDoesNotRequireLocationServiceAccess() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                AndroidVpnService.foregroundServiceTypes(false, true, true)
        );
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                AndroidVpnService.foregroundServiceTypes(false, false, true)
        );
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                AndroidVpnService.foregroundServiceTypes(true, true, false)
        );
    }
}
