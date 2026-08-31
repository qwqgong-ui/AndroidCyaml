package io.github.qwqgong.androidcyaml.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetirementTest {
    private fun network(updatedAt: Long) = NetworkSelectionStore.StoredNetwork(
        kind = "wifi",
        label = "Wi-Fi",
        updatedAt = updatedAt,
        selections = mapOf("🌐" to "node"),
    )

    @Test
    fun trimNamesTheNetworksItDropped() {
        // The names are the point: selector choices and the core's direct-DNS
        // candidates are two stores keyed by the same fingerprint, and without
        // knowing which network left here, the core's branch for it would sit
        // until each entry's own expiry with no profile left to explain it.
        val networks = LinkedHashMap<String, NetworkSelectionStore.StoredNetwork>()
        for (index in 1..NetworkSelectionStore.MAX_NETWORKS + 3) {
            networks["network-$index"] = network(index.toLong())
        }

        val retired = NetworkSelectionStore.trimOldest(networks)

        assertEquals(setOf("network-1", "network-2", "network-3"), retired)
        assertEquals(NetworkSelectionStore.MAX_NETWORKS, networks.size)
        assertTrue(networks.keys.none { it in retired })
    }

    @Test
    fun trimRetiresNothingUnderTheCap() {
        val networks = LinkedHashMap<String, NetworkSelectionStore.StoredNetwork>()
        for (index in 1..NetworkSelectionStore.MAX_NETWORKS) {
            networks["network-$index"] = network(index.toLong())
        }

        assertTrue(NetworkSelectionStore.trimOldest(networks).isEmpty())
        assertEquals(NetworkSelectionStore.MAX_NETWORKS, networks.size)
    }

    @Test
    fun retirementDropsTheOldestFirst() {
        // Least-recently-updated wins, not insertion order: a network revisited
        // yesterday must outlive one last seen three months ago.
        val networks = LinkedHashMap<String, NetworkSelectionStore.StoredNetwork>()
        networks["recent"] = network(9_000L)
        networks["ancient"] = network(1L)
        for (index in 1 until NetworkSelectionStore.MAX_NETWORKS) {
            networks["filler-$index"] = network(5_000L + index)
        }

        val retired = NetworkSelectionStore.trimOldest(networks)

        assertEquals(setOf("ancient"), retired)
        assertTrue(networks.containsKey("recent"))
    }
}
