package io.github.qwqgong.androidcyaml.network

import android.content.pm.ServiceInfo
import io.github.qwqgong.androidcyaml.AndroidVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NetworkComponentTest {
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
        val wifiIpv4 = NetworkState.of(
            100L, "wlan0", false, true, listOf("192.168.1.1"), "home",
        )
        val wifiIpv6 = NetworkState.of(
            100L, "wlan0", true, true, listOf("192.168.1.1"), "home",
        )
        val mobileIpv6 = NetworkState.of(
            200L, "rmnet_data0", true, false, listOf("10.0.0.1"), "mobile",
        )
        val wifiDnsChanged = NetworkState.of(
            100L, "wlan0-new-dns", true, true, listOf("1.1.1.1"), "home",
        )
        val officeWifi = NetworkState.of(
            100L, "wlan0", true, true, listOf("192.168.1.1"), "office",
        )

        val ipv6Transition = wifiIpv6.transitionFrom(wifiIpv4)
        assertFalse(ipv6Transition.routeChanged)
        assertTrue(ipv6Transition.ipv6Changed)
        assertFalse(ipv6Transition.cacheChanged)

        val dnsTransition = wifiDnsChanged.transitionFrom(wifiIpv6)
        assertFalse(dnsTransition.routeChanged)
        assertTrue(dnsTransition.dnsChanged)
        assertFalse(dnsTransition.cacheChanged)

        val mobileTransition = mobileIpv6.transitionFrom(wifiIpv6)
        assertTrue(mobileTransition.routeChanged)
        assertTrue(mobileTransition.cacheChanged)

        val identityTransition = officeWifi.transitionFrom(wifiIpv6)
        assertFalse(identityTransition.routeChanged)
        assertTrue(identityTransition.identityChanged)
        assertTrue(identityTransition.cacheChanged)
        assertNotEquals(officeWifi, wifiIpv6)
        assertTrue(NetworkState.unavailable().transitionFrom(wifiIpv6).routeChanged)
        assertTrue(wifiIpv4.available())
        assertTrue(wifiIpv6.wifi)
        assertEquals(listOf("192.168.1.1"), wifiIpv6.dnsServers)
        assertEquals("home", wifiIpv6.selectionIdentity)
        assertFalse(mobileIpv6.wifi)
        assertFalse(NetworkState.unavailable().available())
        assertEquals("home", wifiIpv6.cacheIdentity())
        assertEquals("mobile", mobileIpv6.cacheIdentity())
    }

    @Test
    fun cacheIdentityFallsBackToHashedPhysicalPath() {
        val first = NetworkState.of(
            100L, "if=wlan0|addr=192.168.1.2/24", true, true, emptyList(), "",
        )
        val samePathNewHandle = NetworkState.of(
            200L, "if=wlan0|addr=192.168.1.2/24", true, true, emptyList(), "",
        )
        val otherPath = NetworkState.of(
            300L, "if=wlan0|addr=192.168.2.2/24", true, true, emptyList(), "",
        )

        assertTrue(first.cacheIdentity().isNotBlank())
        assertEquals(first.cacheIdentity(), samePathNewHandle.cacheIdentity())
        val handleTransition = samePathNewHandle.transitionFrom(first)
        assertTrue(handleTransition.routeChanged)
        assertFalse(handleTransition.cacheChanged)
        assertNotEquals(first.cacheIdentity(), otherPath.cacheIdentity())
        assertEquals("", NetworkState.unavailable().cacheIdentity())
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
