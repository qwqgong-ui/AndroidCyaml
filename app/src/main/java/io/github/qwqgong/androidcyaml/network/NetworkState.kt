package io.github.qwqgong.androidcyaml.network

/** Immutable view of the physical network selected by Android's own scoring. */
data class NetworkState(
    val networkHandle: Long,
    val cachePathSignature: String,
    val ipv6Usable: Boolean,
    val wifi: Boolean,
    val dnsServers: List<String>,
    val selectionIdentity: String,
    val selectionKind: String,
    val selectionLabel: String,
) {
    fun available(): Boolean = networkHandle != 0L

    // Cache scope and selection memory answer different questions, so they must
    // not share a key. Selection memory keys on the SSID alone, deliberately, so
    // that roaming across the access points of one Wi-Fi keeps a single profile.
    // The direct-DNS scope needs the opposite guarantee: two networks that merely
    // share a name -- a chain cafe, a carrier hotspot, an office with one SSID
    // across sites -- hand out different local answers, and reusing one scope
    // between them serves the other network's long-lived candidates. Mixing the
    // physical path signature back in separates those while still collapsing to
    // one scope when the path is genuinely the same.
    fun cacheIdentity(): String {
        if (!available()) {
            return ""
        }
        val kind = selectionKind.ifBlank { if (wifi) "wifi" else "cellular" }
        return NetworkIdentityResolver.pathFingerprint(
            kind,
            selectionIdentity + PATH_SEPARATOR + cachePathSignature,
        )
    }

    fun transitionFrom(previous: NetworkState?): NetworkTransition = NetworkTransition(
        routeChanged = previous == null ||
            networkHandle != previous.networkHandle ||
            available() != previous.available(),
        dnsChanged = previous == null || dnsServers != previous.dnsServers,
        ipv6Changed = previous == null || ipv6Usable != previous.ipv6Usable,
        identityChanged = previous == null || selectionIdentity != previous.selectionIdentity,
        cacheChanged = previous == null || cacheIdentity() != previous.cacheIdentity(),
    )

    companion object {
        private const val PATH_SEPARATOR = "|"

        fun unavailable(): NetworkState = NetworkState(
            0L,
            "",
            false,
            false,
            emptyList(),
            "",
            "",
            "",
        )

        fun of(
            networkHandle: Long,
            cachePathSignature: String?,
            ipv6Usable: Boolean,
            wifi: Boolean,
            dnsServers: List<String>?,
            selectionIdentity: String?,
            selectionKind: String?,
            selectionLabel: String?,
        ): NetworkState {
            if (networkHandle == 0L) {
                return unavailable()
            }
            return NetworkState(
                networkHandle,
                cachePathSignature ?: "",
                ipv6Usable,
                wifi,
                dnsServers?.toList() ?: emptyList(),
                selectionIdentity ?: "",
                selectionKind ?: "",
                selectionLabel ?: "",
            )
        }

        fun of(
            networkHandle: Long,
            cachePathSignature: String?,
            ipv6Usable: Boolean,
            wifi: Boolean,
            dnsServers: List<String>?,
            selectionIdentity: String?,
        ): NetworkState = of(
            networkHandle,
            cachePathSignature,
            ipv6Usable,
            wifi,
            dnsServers,
            selectionIdentity,
            if (wifi) "wifi" else "cellular",
            if (wifi) "Wi-Fi" else "移动数据",
        )
    }
}

/** Describes independent network concerns so one change cannot trigger unrelated work. */
data class NetworkTransition(
    val routeChanged: Boolean,
    val dnsChanged: Boolean,
    val ipv6Changed: Boolean,
    val identityChanged: Boolean,
    val cacheChanged: Boolean,
) {
    fun changed(): Boolean =
        routeChanged || dnsChanged || ipv6Changed || identityChanged || cacheChanged

    /**
     * Folds dimensions an earlier transition could not finish back into this one.
     *
     * A native call that fails leaves its dimension unreconciled, and the state it
     * was reconciling towards has already been observed -- so nothing later will
     * report that dimension as changed again. Carrying the unfinished dimensions
     * forward is what makes the next transition retry them instead of dropping
     * the work silently.
     */
    fun mergePending(pending: NetworkTransition?): NetworkTransition {
        if (pending == null || !pending.changed()) {
            return this
        }
        return NetworkTransition(
            routeChanged = routeChanged || pending.routeChanged,
            dnsChanged = dnsChanged || pending.dnsChanged,
            ipv6Changed = ipv6Changed || pending.ipv6Changed,
            identityChanged = identityChanged || pending.identityChanged,
            cacheChanged = cacheChanged || pending.cacheChanged,
        )
    }

    companion object {
        fun none(): NetworkTransition = NetworkTransition(
            routeChanged = false,
            dnsChanged = false,
            ipv6Changed = false,
            identityChanged = false,
            cacheChanged = false,
        )
    }
}
