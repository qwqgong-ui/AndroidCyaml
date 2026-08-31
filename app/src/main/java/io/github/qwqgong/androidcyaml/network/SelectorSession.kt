package io.github.qwqgong.androidcyaml.network

import android.util.Log
import io.github.qwqgong.androidcyaml.MihomoRuntime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Owns the selector checkpoint associated with the current physical network. */
class SelectorSession(
    private val store: NetworkSelectionStore,
    private val networkMonitor: UnderlyingNetworkMonitor,
) {
    private var identity = ""
    private var kind = ""
    private var label = ""
    private var ready = false

    fun begin(runtime: MihomoRuntime?, networkState: NetworkState?) {
        identity = networkState?.selectionIdentity ?: ""
        kind = networkState?.selectionKind ?: ""
        label = networkState?.selectionLabel ?: ""
        ready = false
        restoreOrRemember(runtime)
    }

    fun reset() {
        identity = ""
        kind = ""
        label = ""
        ready = false
    }

    fun moveTo(networkState: NetworkState, runtime: MihomoRuntime?): Boolean {
        // An outage is not a network change -- the same network usually comes
        // back. Holding the session across it lets a choice the user made while
        // the network was down survive; ending it would make the return look
        // like a fresh arrival and restore the remembered node over the top. A
        // network that is present but unidentifiable is a real move and still
        // ends the session, otherwise its choices would be checkpointed under
        // the previous network's identity.
        if (!networkState.available()) {
            return false
        }
        if (identity == networkState.selectionIdentity) {
            return false
        }
        checkpoint(runtime)
        identity = networkState.selectionIdentity
        kind = networkState.selectionKind
        label = networkState.selectionLabel
        ready = false
        return true
    }

    fun checkpoint(runtime: MihomoRuntime?): Boolean {
        if (!ready || runtime == null || identity.isBlank()) {
            return true
        }
        return save(runtime, identity, kind, label)
    }

    fun restoreOrRemember(runtime: MihomoRuntime?) {
        if (runtime == null || identity.isBlank()) {
            return
        }
        val remembered = store.selections(identity)
        if (remembered.isEmpty()) {
            // Nothing to restore, and nothing worth writing yet. The core's
            // current choice at this moment is still whatever the previous
            // network restored, so persisting it here would record one network's
            // node as another's -- permanently, and again after every expiry,
            // seeded from whichever network happened to be active. A network
            // passed through for two seconds during a handover would also leave
            // a profile behind. The session is live from here, so the ordinary
            // checkpoint on departure records what this network actually ended
            // on, which is the state worth remembering.
            ready = true
            return
        }
        try {
            val result = runtime.restoreSelectorSelections(remembered)
            Log.i(
                TAG,
                "Restored network selector choices: restored=" + result.restored +
                    ", automaticFallback=" + result.fallback +
                    ", skipped=" + result.skipped +
                    ", failed=" + result.failed,
            )
            // Rewrite legacy profiles that remembered every Selector so only
            // the first configured user Selector remains on disk.
            save(runtime, identity, kind, label)
        } catch (exception: IOException) {
            // A memory restore timeout must leave the core's current choices intact.
            Log.w(TAG, "Unable to restore selector choices for this network", exception)
        } finally {
            ready = true
        }
    }

    fun catalog(runtime: MihomoRuntime): String {
        val stored = HashMap<String, NetworkSelectionStore.Profile>()
        for (profile in store.profiles()) {
            stored[profile.identity] = profile
        }

        val profiles = LinkedHashMap<String, NetworkIdentityResolver.Profile>()
        if (identity.isNotBlank()) {
            profiles[identity] = NetworkIdentityResolver.Profile(identity, kind, label)
        }
        for (profile in networkMonitor.availableProfiles()) {
            profiles[profile.identity] = profile
        }
        for (profile in stored.values) {
            val storedLabel = profile.label
            // v1 records cannot be mapped back to a displayable network without
            // weakening the hashed identity, so keep them hidden in the UI.
            if (storedLabel.isBlank()) {
                continue
            }
            profiles.putIfAbsent(
                profile.identity,
                NetworkIdentityResolver.Profile(profile.identity, profile.kind, storedLabel),
            )
        }

        // The live group/option catalog is identical for every remembered
        // network; only the "selected" overlay differs per profile. Fetch it
        // once instead of once per profile (previously up to
        // NetworkSelectionStore.MAX_NETWORKS controller round-trips).
        val proxiesSnapshot = runtime.proxySnapshot()
        val encodedProfiles = JSONArray()
        for (profile in profiles.values) {
            val storedProfile = stored[profile.identity]
            var selections = storedProfile?.selections ?: emptyMap()
            if (selections.isEmpty() && profile.identity == identity) {
                selections = runtime.selectorSelections(proxiesSnapshot)
            }
            val encoded = JSONObject()
            encoded.put("identity", profile.identity)
            encoded.put("kind", profile.kind)
            encoded.put("label", profile.label)
            encoded.put("current", profile.identity == identity)
            encoded.put("groups", runtime.selectorCatalog(proxiesSnapshot, selections))
            encodedProfiles.put(encoded)
        }
        return JSONObject().put("profiles", encodedProfiles).toString()
    }

    fun select(
        runtime: MihomoRuntime,
        requestedIdentity: String?,
        group: String?,
        target: String?,
    ): String {
        if (requestedIdentity.isNullOrBlank() || group.isNullOrBlank() || target.isNullOrBlank()) {
            throw IOException("网络、策略组或节点为空")
        }
        var selectedKind = ""
        var selectedLabel = ""
        for (profile in networkMonitor.availableProfiles()) {
            if (requestedIdentity == profile.identity) {
                selectedKind = profile.kind
                selectedLabel = profile.label
                break
            }
        }
        val selections = HashMap(store.selections(requestedIdentity))
        if (selections.isEmpty()) {
            selections.putAll(runtime.selectorSelections())
        }
        val groupCatalog = runtime.selectorCatalog(selections).optJSONObject(group)
        if (groupCatalog == null || !catalogContains(groupCatalog, target)) {
            throw IOException("策略组或节点已不存在")
        }
        selections[group] = target
        if (requestedIdentity == identity) {
            runtime.selectSelector(group, target)
            selectedKind = kind
            selectedLabel = label
        }
        val outcome = store.save(requestedIdentity, selectedKind, selectedLabel, selections)
        if (!outcome.persisted) {
            throw IOException("节点选择无法写入设备存储")
        }
        retire(runtime, outcome)
        if (requestedIdentity == identity) {
            ready = true
        }
        return "已为 " + nonBlank(selectedLabel, "该网络") + " 设置 " + group + " → " + target
    }

    private fun save(
        runtime: MihomoRuntime,
        targetIdentity: String,
        targetKind: String,
        targetLabel: String,
    ): Boolean = try {
        val outcome = store.save(targetIdentity, targetKind, targetLabel, runtime.selectorSelections())
        retire(runtime, outcome)
        outcome.persisted
    } catch (exception: IOException) {
        Log.w(TAG, "Unable to remember selector choices", exception)
        false
    }

    /**
     * Retires the core's direct-DNS candidates for networks this store just
     * dropped, so the two long-term stores let go of a network together.
     *
     * A failure here is not worth failing the write for: the entries still carry
     * their own expiry, so the worst case is the behaviour that existed before
     * the two stores were joined at all.
     */
    private fun retire(runtime: MihomoRuntime, outcome: NetworkSelectionStore.SaveOutcome) {
        for (identity in outcome.retired) {
            try {
                runtime.retireNetworkScope(identity)
            } catch (exception: IOException) {
                Log.w(TAG, "Unable to retire DNS candidates for a dropped network", exception)
            }
        }
        if (outcome.retired.isNotEmpty()) {
            Log.i(TAG, "Retired " + outcome.retired.size + " network profiles and their DNS candidates")
        }
    }

    private companion object {
        const val TAG = "AndroidCyaml/SelectorSession"

        fun catalogContains(group: JSONObject, target: String): Boolean {
            val options = group.optJSONArray("options") ?: return false
            for (index in 0 until options.length()) {
                val option = options.optJSONObject(index)
                if (option != null && target == option.optString("name", "")) {
                    return option.optBoolean("available", true)
                }
            }
            return false
        }

        fun nonBlank(preferred: String?, fallback: String): String =
            if (preferred.isNullOrBlank()) fallback else preferred
    }
}
