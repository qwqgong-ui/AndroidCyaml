package io.github.qwqgong.androidcyaml.network

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/** Persists recent selector choices by hashed physical-network identity. */
class NetworkSelectionStore(context: Context) {
    data class Profile(
        val identity: String,
        val kind: String,
        val label: String,
        val updatedAt: Long,
        val selections: Map<String, String>,
    )

    internal data class StoredNetwork(
        val kind: String,
        val label: String,
        val updatedAt: Long,
        val selections: Map<String, String>,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun selections(networkIdentity: String?): Map<String, String> {
        if (networkIdentity.isNullOrBlank()) {
            return emptyMap()
        }
        val stored = readNetworks()[networkIdentity]
        if (stored == null || isExpired(stored.updatedAt, System.currentTimeMillis())) {
            return emptyMap()
        }
        return stored.selections.toMap()
    }

    /**
     * The outcome of a write: whether it reached disk, and which networks left
     * this store for good while it did.
     *
     * Selector choices and the core's direct-DNS candidates are two stores keyed
     * by the same fingerprint, with independent expiry. Nothing used to connect
     * them, so a network aged out or pushed past the cap here kept its DNS branch
     * in the core until each entry's own expiry -- a 24-hour floor, with no
     * profile left to explain it. Reporting retirements is the join; the caller
     * has the runtime and forwards them.
     */
    data class SaveOutcome(val persisted: Boolean, val retired: Set<String>) {
        companion object {
            fun unchanged(): SaveOutcome = SaveOutcome(true, emptySet())
        }
    }

    fun save(networkIdentity: String?, selections: Map<String, String>?): SaveOutcome =
        save(networkIdentity, "", "", selections)

    fun save(
        networkIdentity: String?,
        kind: String?,
        label: String?,
        selections: Map<String, String>?,
    ): SaveOutcome {
        if (networkIdentity.isNullOrBlank() || selections.isNullOrEmpty()) {
            return SaveOutcome.unchanged()
        }
        val now = System.currentTimeMillis()
        val sanitized = sanitizeSelections(selections)
        if (sanitized.isEmpty()) {
            return SaveOutcome.unchanged()
        }
        val networks = readNetworks()
        val retired = LinkedHashSet<String>()
        networks.entries.removeIf { entry ->
            val expired = isExpired(entry.value.updatedAt, now)
            if (expired) retired.add(entry.key)
            expired
        }
        val previous = networks[networkIdentity]
        val storedKind = nonBlank(kind, previous?.kind ?: "")
        val storedLabel = nonBlank(label, previous?.label ?: "")
        networks[networkIdentity] = StoredNetwork(storedKind, storedLabel, now, sanitized)
        retired.addAll(trimOldest(networks))
        // These writes happen only on network/lifecycle boundaries. commit()
        // makes the memory-kill acknowledgement truthful: once it returns, the
        // latest selector state is on disk rather than queued in this process.
        val persisted = preferences.edit().putString(DOCUMENT, encodeNetworks(networks)).commit()
        // Only report a retirement the disk agrees with. A failed commit leaves
        // every profile in place, and evicting the core's answers for a network
        // that is still remembered throws away a warm cache for nothing.
        if (!persisted) {
            return SaveOutcome(false, emptySet())
        }
        // The network being written is by definition not retired, even if an
        // older record under the same identity had already aged out above.
        retired.remove(networkIdentity)
        return SaveOutcome(true, retired)
    }

    fun profiles(): List<Profile> {
        val now = System.currentTimeMillis()
        return readNetworks()
            .filterValues { !isExpired(it.updatedAt, now) }
            .map { (identity, stored) ->
                Profile(
                    identity,
                    stored.kind,
                    stored.label,
                    stored.updatedAt,
                    stored.selections.toMap(),
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun readNetworks(): MutableMap<String, StoredNetwork> {
        val networks = HashMap<String, StoredNetwork>()
        val document = preferences.getString(DOCUMENT, "")
        if (document.isNullOrBlank()) {
            return networks
        }
        try {
            val encodedNetworks = JSONObject(document).optJSONObject(NETWORKS) ?: return networks
            for (identity in encodedNetworks.keys()) {
                val encodedNetwork = encodedNetworks.optJSONObject(identity) ?: continue
                val encodedSelections = encodedNetwork.optJSONObject(SELECTIONS) ?: continue
                val selections = HashMap<String, String>()
                for (group in encodedSelections.keys()) {
                    if (selections.size >= MAX_SELECTORS_PER_NETWORK) {
                        break
                    }
                    val target = encodedSelections.optString(group, "")
                    if (group.isNotBlank() && target.isNotBlank()) {
                        selections[group] = target
                    }
                }
                if (selections.isNotEmpty()) {
                    networks[identity] = StoredNetwork(
                        encodedNetwork.optString(KIND, ""),
                        encodedNetwork.optString(LABEL, ""),
                        encodedNetwork.optLong(UPDATED_AT, 0L),
                        selections.toMap(),
                    )
                }
            }
        } catch (exception: JSONException) {
            Log.w(TAG, "Ignoring malformed network-selection memory", exception)
        } catch (exception: ClassCastException) {
            Log.w(TAG, "Ignoring malformed network-selection memory", exception)
        }
        return networks
    }

    internal companion object {
        const val TAG = "AndroidCyaml/Selections"
        const val PREFERENCES = "androidcyaml_network_selections"
        const val DOCUMENT = "network_selections_v1"
        const val NETWORKS = "networks"
        const val UPDATED_AT = "updatedAt"
        const val SELECTIONS = "selections"
        const val KIND = "kind"
        const val LABEL = "label"
        const val MAX_NETWORKS = 24
        const val MAX_SELECTORS_PER_NETWORK = 128
        const val MAX_AGE_MILLIS = 90L * 24L * 60L * 60L * 1_000L

        fun encodeNetworks(networks: Map<String, StoredNetwork>): String {
            try {
                val encodedNetworks = JSONObject()
                for ((identity, network) in networks) {
                    val encodedSelections = JSONObject()
                    for ((group, target) in network.selections) {
                        encodedSelections.put(group, target)
                    }
                    val encodedNetwork = JSONObject()
                    encodedNetwork.put(KIND, network.kind)
                    encodedNetwork.put(LABEL, network.label)
                    encodedNetwork.put(UPDATED_AT, network.updatedAt)
                    encodedNetwork.put(SELECTIONS, encodedSelections)
                    encodedNetworks.put(identity, encodedNetwork)
                }
                return JSONObject().put(NETWORKS, encodedNetworks).toString()
            } catch (impossible: JSONException) {
                throw IllegalStateException("Unable to encode network selections", impossible)
            }
        }

        fun sanitizeSelections(selections: Map<String, String>): Map<String, String> {
            val sanitized = HashMap<String, String>()
            for ((group, target) in selections) {
                if (group.isBlank() || target.isBlank()) {
                    continue
                }
                sanitized[group] = target
                if (sanitized.size >= MAX_SELECTORS_PER_NETWORK) {
                    break
                }
            }
            return sanitized.toMap()
        }

        /**
         * Drops the least recently used networks past the cap and names them, so
         * the caller can retire their DNS candidates in the core too.
         */
        fun trimOldest(networks: MutableMap<String, StoredNetwork>): Set<String> {
            if (networks.size <= MAX_NETWORKS) {
                return emptySet()
            }
            val removed = LinkedHashSet<String>()
            val ordered = networks.entries.sortedBy { it.value.updatedAt }
            for (index in 0 until ordered.size - MAX_NETWORKS) {
                val identity = ordered[index].key
                networks.remove(identity)
                removed.add(identity)
            }
            return removed
        }

        fun isExpired(updatedAt: Long, now: Long): Boolean =
            updatedAt <= 0L || now - updatedAt > MAX_AGE_MILLIS

        fun nonBlank(preferred: String?, fallback: String): String =
            if (preferred.isNullOrBlank()) fallback else preferred
    }
}
