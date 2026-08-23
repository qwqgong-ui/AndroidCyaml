package io.github.qwqgong.androidcyaml

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

    private data class StoredNetwork(
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

    fun save(networkIdentity: String?, selections: Map<String, String>?): Boolean =
        save(networkIdentity, "", "", selections)

    fun save(
        networkIdentity: String?,
        kind: String?,
        label: String?,
        selections: Map<String, String>?,
    ): Boolean {
        if (networkIdentity.isNullOrBlank() || selections.isNullOrEmpty()) {
            return true
        }
        val now = System.currentTimeMillis()
        val sanitized = sanitizeSelections(selections)
        if (sanitized.isEmpty()) {
            return true
        }
        val networks = readNetworks()
        networks.entries.removeIf { isExpired(it.value.updatedAt, now) }
        val previous = networks[networkIdentity]
        val storedKind = nonBlank(kind, previous?.kind ?: "")
        val storedLabel = nonBlank(label, previous?.label ?: "")
        networks[networkIdentity] = StoredNetwork(storedKind, storedLabel, now, sanitized)
        trimOldest(networks)
        // These writes happen only on network/lifecycle boundaries. commit()
        // makes the memory-kill acknowledgement truthful: once it returns, the
        // latest selector state is on disk rather than queued in this process.
        return preferences.edit().putString(DOCUMENT, encodeNetworks(networks)).commit()
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

    private companion object {
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

        fun trimOldest(networks: MutableMap<String, StoredNetwork>) {
            if (networks.size <= MAX_NETWORKS) {
                return
            }
            val ordered = networks.entries.sortedBy { it.value.updatedAt }
            for (index in 0 until ordered.size - MAX_NETWORKS) {
                networks.remove(ordered[index].key)
            }
        }

        fun isExpired(updatedAt: Long, now: Long): Boolean =
            updatedAt <= 0L || now - updatedAt > MAX_AGE_MILLIS

        fun nonBlank(preferred: String?, fallback: String): String =
            if (preferred.isNullOrBlank()) fallback else preferred
    }
}
