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

    fun cacheIdentity(): String {
        if (!available()) {
            return ""
        }
        if (selectionIdentity.isNotBlank()) {
            return selectionIdentity
        }
        val fallbackKind = selectionKind.ifBlank { if (wifi) "wifi" else "cellular" }
        return NetworkIdentityResolver.pathFingerprint(fallbackKind, cachePathSignature)
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
}
