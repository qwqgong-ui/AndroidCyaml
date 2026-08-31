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
        // Only the resolvers changed. The path signature is built from the
        // interface, its IPv4 addresses and its routes, so it does not move when
        // DHCP hands out different nameservers on the same network.
        val wifiDnsChanged = NetworkState.of(
            100L, "wlan0", true, true, listOf("1.1.1.1"), "home",
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
        // The cache scope is a fingerprint, never the raw selection identity.
        assertNotEquals(wifiIpv6.selectionIdentity, wifiIpv6.cacheIdentity())
        assertNotEquals(wifiIpv6.cacheIdentity(), mobileIpv6.cacheIdentity())
    }

    @Test
    fun cacheScopeSeparatesDistinctNetworksSharingOneSsid() {
        // Two different physical networks that merely share a name: a chain
        // cafe, a carrier hotspot, one SSID across office sites. Selection
        // memory must treat them as one profile so roaming does not fork it,
        // but their local resolvers answer differently, so the direct-DNS scope
        // must not be shared.
        val firstSite = NetworkState.of(
            100L, "if=wlan0|addr=192.168.1.20/24", true, true, listOf("192.168.1.1"), "cafe",
        )
        val secondSite = NetworkState.of(
            200L, "if=wlan0|addr=10.20.30.40/24", true, true, listOf("10.20.30.1"), "cafe",
        )

        val transition = secondSite.transitionFrom(firstSite)
        assertFalse(transition.identityChanged)
        assertTrue(transition.cacheChanged)
        assertNotEquals(firstSite.cacheIdentity(), secondSite.cacheIdentity())
    }

    @Test
    fun cacheScopeSurvivesReconnectingToTheSamePath() {
        // A Wi-Fi reconnect produces a new Network handle for the same physical
        // path. Rotating the scope there would throw away a warm cache branch
        // for nothing.
        val before = NetworkState.of(
            100L, "if=wlan0|addr=192.168.1.20/24", true, true, listOf("192.168.1.1"), "home",
        )
        val afterReconnect = NetworkState.of(
            200L, "if=wlan0|addr=192.168.1.20/24", true, true, listOf("192.168.1.1"), "home",
        )

        val transition = afterReconnect.transitionFrom(before)
        assertTrue(transition.routeChanged)
        assertFalse(transition.cacheChanged)
        assertEquals(before.cacheIdentity(), afterReconnect.cacheIdentity())
    }

    @Test
    fun unreconciledDimensionsAreReplayedOnTheNextTransition() {
        // A native call that failed leaves its dimension owed. The state it was
        // reconciling towards is already observed, so without carrying it
        // forward nothing would ever report that dimension as changed again.
        val owed = NetworkTransition(
            routeChanged = true,
            dnsChanged = false,
            ipv6Changed = false,
            identityChanged = false,
            cacheChanged = true,
        )
        val dnsOnly = NetworkTransition(
            routeChanged = false,
            dnsChanged = true,
            ipv6Changed = false,
            identityChanged = false,
            cacheChanged = false,
        )

        val replayed = dnsOnly.mergePending(owed)
        assertTrue(replayed.dnsChanged)
        assertTrue(replayed.routeChanged)
        assertTrue(replayed.cacheChanged)
        assertFalse(replayed.ipv6Changed)

        assertFalse(NetworkTransition.none().changed())
        assertEquals(dnsOnly, dnsOnly.mergePending(NetworkTransition.none()))
        assertEquals(dnsOnly, dnsOnly.mergePending(null))
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
