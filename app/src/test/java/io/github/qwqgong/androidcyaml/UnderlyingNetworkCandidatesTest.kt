package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkCandidatesTest {
    @Test
    fun wifiLossFallsBackToPreviouslyReportedCellularCandidate() {
        val candidates = UnderlyingNetworkCandidates()
        candidates.update(WIFI, 3)
        candidates.update(CELLULAR, 2)

        assertEquals(WIFI, candidates.best()!!.networkHandle)
        assertTrue(candidates.onLost(WIFI))
        assertEquals(CELLULAR, candidates.best()!!.networkHandle)
    }

    @Test
    fun wifiArrivalReplacesCellularAndCellularLossDoesNotClearWifi() {
        val candidates = UnderlyingNetworkCandidates()
        candidates.update(CELLULAR, 2)
        candidates.update(WIFI, 3)

        assertEquals(WIFI, candidates.best()!!.networkHandle)
        assertFalse(candidates.onLost(CELLULAR))
        assertEquals(WIFI, candidates.best()!!.networkHandle)
    }

    @Test
    fun staleLostNetworkCannotBeReintroducedBySynchronousFallback() {
        val candidates = UnderlyingNetworkCandidates()
        candidates.update(WIFI, 3)
        assertTrue(candidates.onLost(WIFI))
        val loss = candidates.exclusion()

        assertFalse(candidates.acceptsFallback(WIFI, loss))
        assertTrue(candidates.acceptsFallback(CELLULAR, loss))

        candidates.onAvailable(WIFI)
        assertFalse(candidates.acceptsFallback(CELLULAR, loss))
        candidates.update(WIFI, 3)
        assertEquals(WIFI, candidates.best()!!.networkHandle)
    }

    private companion object {
        const val WIFI = 100L
        const val CELLULAR = 200L
    }
}
