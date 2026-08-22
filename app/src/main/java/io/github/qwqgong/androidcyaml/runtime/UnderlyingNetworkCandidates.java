package io.github.qwqgong.androidcyaml;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps every validated callback candidate so a lower-ranked fallback is not lost. */
final class UnderlyingNetworkCandidates {
    record Candidate(long networkHandle, int transportRank) {
        Candidate {
            if (networkHandle == 0L) {
                throw new IllegalArgumentException("networkHandle must be non-zero");
            }
        }
    }

    record Exclusion(long generation, long networkHandle) {}

    private final Map<Long, Candidate> candidates = new LinkedHashMap<>();
    private long exclusionGeneration;
    private long excludedNetworkHandle;

    void onAvailable(long networkHandle) {
        if (networkHandle == excludedNetworkHandle) {
            excludedNetworkHandle = 0L;
            exclusionGeneration++;
        }
    }

    void update(long networkHandle, int transportRank) {
        onAvailable(networkHandle);
        candidates.put(networkHandle, new Candidate(networkHandle, transportRank));
    }

    boolean onLost(long networkHandle) {
        Candidate selected = best();
        candidates.remove(networkHandle);
        if (selected == null || selected.networkHandle() != networkHandle) {
            return false;
        }
        excludedNetworkHandle = networkHandle;
        exclusionGeneration++;
        return true;
    }

    Candidate best() {
        Candidate best = null;
        for (Candidate candidate : candidates.values()) {
            if (best == null || candidate.transportRank() > best.transportRank()) {
                best = candidate;
            }
        }
        return best;
    }

    Exclusion exclusion() {
        return new Exclusion(exclusionGeneration, excludedNetworkHandle);
    }

    boolean acceptsFallback(long networkHandle, Exclusion expected) {
        return expected != null
                && expected.equals(exclusion())
                && networkHandle != 0L
                && networkHandle != expected.networkHandle();
    }

    void clear() {
        candidates.clear();
        excludedNetworkHandle = 0L;
        exclusionGeneration++;
    }
}
