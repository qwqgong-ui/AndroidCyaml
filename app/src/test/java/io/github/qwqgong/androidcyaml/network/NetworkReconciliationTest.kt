package io.github.qwqgong.androidcyaml.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReconciliationTest {
    @Test fun tunFollowsEffectiveFamilyInBothDirections() {
        val ipv4 = NetworkState.of(1, "wlan0", false, true, emptyList(), "wifi")
        val ipv6 = ipv4.copy(ipv6Usable = true)
        assertFalse(ipv4.effectiveIpv6(true))
        assertTrue(ipv4.requiresTunRebuild(true, true, true))
        assertTrue(ipv6.requiresTunRebuild(true, false, true))
        assertFalse(ipv6.requiresTunRebuild(true, true, true))
        assertFalse(ipv4.requiresTunRebuild(true, false, true))
        assertFalse(ipv6.requiresTunRebuild(false, false, true))
        assertFalse(NetworkState.unavailable().requiresTunRebuild(true, true, true))
        assertFalse(NetworkState.unavailable().requiresTunRebuild(true, false, true))
        assertTrue(ipv4.requiresTunRebuild(true, false, false))
    }

    @Test fun everyFailedDimensionRetriesWithoutRepeatingSuccessfulWork() {
        for (failure in NetworkDimension.entries) {
            val calls = mutableListOf<NetworkDimension>()
            val initial = NetworkTransition(true, true, true, true, true)
            val pending = initial.reconcile {
                calls.add(it)
                it != failure
            }
            assertEquals(NetworkDimension.entries, calls)
            assertTrue(pending.changed())
            assertFalse(pending.identityChanged)
            calls.clear()
            val finished = NetworkTransition.none().mergePending(pending).reconcile {
                calls.add(it)
                true
            }
            assertEquals(listOf(failure), calls)
            assertFalse(finished.changed())
        }
    }

    @Test fun earlierFailuresCannotSkipIpv6OrRouteRetirement() {
        val calls = mutableListOf<NetworkDimension>()
        val pending = NetworkTransition(true, true, true, false, true).reconcile {
            calls.add(it)
            false
        }
        assertEquals(NetworkDimension.entries, calls)
        assertEquals(NetworkTransition(true, true, true, false, true), pending)
    }

    @Test fun ipv6OnlyChangeDoesNotFlushDnsOrCloseConnections() {
        val dualStack = NetworkState.of(1, "wlan0", true, true, listOf("192.0.2.1"), "wifi")
        val ipv4Only = dualStack.copy(ipv6Usable = false)
        val calls = mutableListOf<NetworkDimension>()
        for ((previous, next) in listOf(dualStack to ipv4Only, ipv4Only to dualStack)) {
            val result = next.transitionFrom(previous).reconcile { calls.add(it); true }
            assertFalse(result.changed())
        }
        assertEquals(listOf(NetworkDimension.IPV6, NetworkDimension.IPV6), calls)
    }
}
