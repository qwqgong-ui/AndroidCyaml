package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UnderlyingNetworkCandidatesTest {
    private static final long WIFI = 100L;
    private static final long CELLULAR = 200L;

    @Test
    public void wifiLossFallsBackToPreviouslyReportedCellularCandidate() {
        UnderlyingNetworkCandidates candidates = new UnderlyingNetworkCandidates();
        candidates.update(WIFI, 3);
        candidates.update(CELLULAR, 2);

        assertEquals(WIFI, candidates.best().networkHandle());
        assertTrue(candidates.onLost(WIFI));
        assertEquals(CELLULAR, candidates.best().networkHandle());
    }

    @Test
    public void wifiArrivalReplacesCellularAndCellularLossDoesNotClearWifi() {
        UnderlyingNetworkCandidates candidates = new UnderlyingNetworkCandidates();
        candidates.update(CELLULAR, 2);
        candidates.update(WIFI, 3);

        assertEquals(WIFI, candidates.best().networkHandle());
        assertFalse(candidates.onLost(CELLULAR));
        assertEquals(WIFI, candidates.best().networkHandle());
    }

    @Test
    public void staleLostNetworkCannotBeReintroducedBySynchronousFallback() {
        UnderlyingNetworkCandidates candidates = new UnderlyingNetworkCandidates();
        candidates.update(WIFI, 3);
        assertTrue(candidates.onLost(WIFI));
        UnderlyingNetworkCandidates.Exclusion loss = candidates.exclusion();

        assertFalse(candidates.acceptsFallback(WIFI, loss));
        assertTrue(candidates.acceptsFallback(CELLULAR, loss));

        candidates.onAvailable(WIFI);
        assertFalse(candidates.acceptsFallback(CELLULAR, loss));
        candidates.update(WIFI, 3);
        assertEquals(WIFI, candidates.best().networkHandle());
    }
}
