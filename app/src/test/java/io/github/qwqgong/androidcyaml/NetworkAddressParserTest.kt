package io.github.qwqgong.androidcyaml

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NetworkAddressParserTest {
    @Test
    fun interfacePrefixPreservesIpv4HostBits() {
        val prefix = NetworkAddressParser.parseAddressPrefix("172.19.0.1/30")

        assertEquals("172.19.0.1", prefix.address.hostAddress)
        assertEquals(30, prefix.prefixLength)
    }

    @Test
    fun interfacePrefixPreservesIpv6HostBits() {
        val prefix = NetworkAddressParser.parseAddressPrefix("fdfe:dcba:9876::1/126")

        assertEquals("fdfe:dcba:9876:0:0:0:0:1", prefix.address.hostAddress)
        assertEquals(126, prefix.prefixLength)
    }

    @Test
    fun interfacePrefixRejectsInvalidLengths() {
        assertThrows(IOException::class.java) {
            NetworkAddressParser.parseAddressPrefix("172.19.0.1/33")
        }
        assertThrows(IOException::class.java) {
            NetworkAddressParser.parseAddressPrefix("fdfe::1/129")
        }
    }

    @Test
    fun underlyingStateSeparatesPathIpv6AndTransportChanges() {
        val wifiIpv4 = Ipv6EnvironmentMonitor.State.of(
            100L, "wlan0", false, true, listOf("192.168.1.1"), "home",
        )
        val wifiIpv6 = Ipv6EnvironmentMonitor.State.of(
            100L, "wlan0", true, true, listOf("192.168.1.1"), "home",
        )
        val mobileIpv6 = Ipv6EnvironmentMonitor.State.of(
            200L, "rmnet_data0", true, false, listOf("10.0.0.1"), "mobile",
        )
        val wifiDnsChanged = Ipv6EnvironmentMonitor.State.of(
            100L, "wlan0-new-dns", true, true, listOf("1.1.1.1"), "home",
        )
        val officeWifi = Ipv6EnvironmentMonitor.State.of(
            100L, "wlan0", true, true, listOf("192.168.1.1"), "office",
        )

        assertFalse(wifiIpv6.pathChangedFrom(wifiIpv4))
        assertTrue(wifiDnsChanged.pathChangedFrom(wifiIpv6))
        assertTrue(mobileIpv6.pathChangedFrom(wifiIpv6))
        assertFalse(officeWifi.pathChangedFrom(wifiIpv6))
        assertNotEquals(officeWifi, wifiIpv6)
        assertTrue(Ipv6EnvironmentMonitor.State.unavailable().pathChangedFrom(wifiIpv6))
        assertTrue(wifiIpv4.available())
        assertTrue(wifiIpv6.wifi)
        assertEquals(listOf("192.168.1.1"), wifiIpv6.dnsServers)
        assertEquals("home", wifiIpv6.selectionIdentity)
        assertFalse(mobileIpv6.wifi)
        assertFalse(Ipv6EnvironmentMonitor.State.unavailable().available())
        assertEquals("home", wifiIpv6.cacheIdentity())
        assertEquals("mobile", mobileIpv6.cacheIdentity())
    }

    @Test
    fun cacheIdentityFallsBackToHashedPhysicalPath() {
        val first = Ipv6EnvironmentMonitor.State.of(
            100L, "if=wlan0|addr=192.168.1.2/24", true, true, emptyList(), "",
        )
        val samePathNewHandle = Ipv6EnvironmentMonitor.State.of(
            200L, "if=wlan0|addr=192.168.1.2/24", true, true, emptyList(), "",
        )
        val otherPath = Ipv6EnvironmentMonitor.State.of(
            300L, "if=wlan0|addr=192.168.2.2/24", true, true, emptyList(), "",
        )

        assertTrue(first.cacheIdentity().isNotBlank())
        assertEquals(first.cacheIdentity(), samePathNewHandle.cacheIdentity())
        assertNotEquals(first.cacheIdentity(), otherPath.cacheIdentity())
        assertEquals("", Ipv6EnvironmentMonitor.State.unavailable().cacheIdentity())
    }

    @Test
    fun wifiSelectionIdentitySurvivesAccessPointRoaming() {
        val firstAccessPoint = NetworkIdentityResolver.wifiFingerprint(
            "Home",
            "00:11:22:33:44:55",
        )
        val secondAccessPoint = NetworkIdentityResolver.wifiFingerprint(
            "Home",
            "66:77:88:99:aa:bb",
        )

        assertEquals(firstAccessPoint, secondAccessPoint)
        assertNotEquals(
            firstAccessPoint,
            NetworkIdentityResolver.wifiFingerprint("Office", "00:11:22:33:44:55"),
        )
    }

    @Test
    fun wifiSelectionIdentityFallsBackToBssidWhenSsidIsUnavailable() {
        assertNotEquals(
            NetworkIdentityResolver.wifiFingerprint("", "00:11:22:33:44:55"),
            NetworkIdentityResolver.wifiFingerprint("", "66:77:88:99:aa:bb"),
        )
    }

    @Test
    fun cellularSelectionIdentityUsesStableCarrierProperties() {
        val identity = NetworkIdentityResolver.cellularFingerprint(1, "46000", 1435, "Carrier")

        assertEquals(
            identity,
            NetworkIdentityResolver.cellularFingerprint(
                1,
                "46000",
                1435,
                "Localized carrier name",
            ),
        )
        assertNotEquals(
            identity,
            NetworkIdentityResolver.cellularFingerprint(2, "46000", 1435, "Carrier"),
        )
    }

    @Test
    fun foregroundVpnStartKeepsWifiIdentityLocationAccessWhenGranted() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            AndroidVpnService.foregroundServiceTypes(true, true, true),
        )
    }

    @Test
    fun coarseOnlyBackgroundOrDeniedStartDoesNotRequireLocationServiceAccess() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            AndroidVpnService.foregroundServiceTypes(false, true, true),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            AndroidVpnService.foregroundServiceTypes(false, false, true),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            AndroidVpnService.foregroundServiceTypes(true, true, false),
        )
    }
}
