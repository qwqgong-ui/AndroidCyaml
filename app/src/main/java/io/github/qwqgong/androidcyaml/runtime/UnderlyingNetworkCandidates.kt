package io.github.qwqgong.androidcyaml

/** Keeps every validated callback candidate so a lower-ranked fallback is not lost. */
class UnderlyingNetworkCandidates {
    data class Candidate(
        val networkHandle: Long,
        val transportRank: Int,
    ) {
        init {
            require(networkHandle != 0L) { "networkHandle must be non-zero" }
        }
    }

    data class Exclusion(
        val generation: Long,
        val networkHandle: Long,
    )

    private val candidates = LinkedHashMap<Long, Candidate>()
    private var exclusionGeneration = 0L
    private var excludedNetworkHandle = 0L

    fun onAvailable(networkHandle: Long) {
        if (networkHandle == excludedNetworkHandle) {
            excludedNetworkHandle = 0L
            exclusionGeneration++
        }
    }

    fun update(networkHandle: Long, transportRank: Int) {
        onAvailable(networkHandle)
        candidates[networkHandle] = Candidate(networkHandle, transportRank)
    }

    fun onLost(networkHandle: Long): Boolean {
        val selected = best()
        candidates.remove(networkHandle)
        if (selected == null || selected.networkHandle != networkHandle) {
            return false
        }
        excludedNetworkHandle = networkHandle
        exclusionGeneration++
        return true
    }

    fun best(): Candidate? = candidates.values.maxByOrNull { it.transportRank }

    fun exclusion(): Exclusion = Exclusion(exclusionGeneration, excludedNetworkHandle)

    fun acceptsFallback(networkHandle: Long, expected: Exclusion?): Boolean =
        expected != null &&
            expected == exclusion() &&
            networkHandle != 0L &&
            networkHandle != expected.networkHandle

    fun clear() {
        candidates.clear()
        excludedNetworkHandle = 0L
        exclusionGeneration++
    }
}
