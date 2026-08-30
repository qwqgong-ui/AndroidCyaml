package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReconcilerTest {
    @Test
    fun linkPropertyRefreshOnSameNetworkKeepsConnectionsAlive() {
        val before = state(NETWORK, "old-routes")
        val after = state(NETWORK, "new-routes")

        assertTrue(after.pathChangedFrom(before))
        assertFalse(requiresConnectionReset(before, after))
    }

    @Test
    fun physicalNetworkHandoverClosesConnections() {
        assertTrue(
            requiresConnectionReset(
                state(NETWORK, "wifi"),
                state(OTHER_NETWORK, "cellular"),
            ),
        )
    }

    @Test
    fun lossOfUnderlyingNetworkClosesConnections() {
        assertTrue(
            requiresConnectionReset(
                state(NETWORK, "wifi"),
                Ipv6EnvironmentMonitor.State.unavailable(),
            ),
        )
    }

    private fun state(networkHandle: Long, signature: String) =
        Ipv6EnvironmentMonitor.State.of(
            networkHandle,
            signature,
            false,
            true,
            listOf("1.1.1.1"),
            "wifi-home",
        )

    private companion object {
        const val NETWORK = 100L
        const val OTHER_NETWORK = 200L
    }
}
