package io.github.qwqgong.androidcyaml;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns the selector checkpoint associated with the current physical network. */
final class SelectorSession {
    private static final String TAG = "AndroidCyaml/SelectorSession";

    private final NetworkSelectionStore store;
    private final Ipv6EnvironmentMonitor networkMonitor;

    private String identity = "";
    private String kind = "";
    private String label = "";
    private boolean ready;

    SelectorSession(
            NetworkSelectionStore store,
            Ipv6EnvironmentMonitor networkMonitor
    ) {
        this.store = store;
        this.networkMonitor = networkMonitor;
    }

    void begin(MihomoRuntime runtime, Ipv6EnvironmentMonitor.State networkState) {
        identity = networkState == null ? "" : networkState.selectionIdentity();
        kind = networkState == null ? "" : networkState.selectionKind();
        label = networkState == null ? "" : networkState.selectionLabel();
        ready = false;
        restoreOrRemember(runtime);
    }

    void reset() {
        identity = "";
        kind = "";
        label = "";
        ready = false;
    }

    boolean moveTo(
            Ipv6EnvironmentMonitor.State networkState,
            MihomoRuntime runtime
    ) {
        if (Objects.equals(identity, networkState.selectionIdentity())) {
            return false;
        }
        checkpoint(runtime);
        identity = networkState.selectionIdentity();
        kind = networkState.selectionKind();
        label = networkState.selectionLabel();
        ready = false;
        return true;
    }

    boolean checkpoint(MihomoRuntime runtime) {
        if (!ready || runtime == null || identity == null || identity.isBlank()) {
            return true;
        }
        return save(runtime, identity, kind, label);
    }

    void restoreOrRemember(MihomoRuntime runtime) {
        if (runtime == null || identity == null || identity.isBlank()) {
            return;
        }
        Map<String, String> remembered = store.selections(identity);
        if (remembered.isEmpty()) {
            save(runtime, identity, kind, label);
            ready = true;
            return;
        }
        try {
            MihomoController.SelectionRestoreResult result =
                    runtime.restoreSelectorSelections(remembered);
            Log.i(
                    TAG,
                    "Restored network selector choices: restored=" + result.restored()
                            + ", automaticFallback=" + result.fallback()
                            + ", skipped=" + result.skipped()
                            + ", failed=" + result.failed()
            );
            // Rewrite legacy profiles that remembered every Selector so only
            // the first configured user Selector remains on disk.
            save(runtime, identity, kind, label);
        } catch (IOException exception) {
            // A memory restore timeout must leave the core's current choices intact.
            Log.w(TAG, "Unable to restore selector choices for this network", exception);
        } finally {
            ready = true;
        }
    }

    String catalog(MihomoRuntime runtime) throws IOException, JSONException {
        Map<String, NetworkSelectionStore.Profile> stored = new HashMap<>();
        for (NetworkSelectionStore.Profile profile : store.profiles()) {
            stored.put(profile.identity(), profile);
        }

        LinkedHashMap<String, NetworkIdentityResolver.Profile> profiles =
                new LinkedHashMap<>();
        if (identity != null && !identity.isBlank()) {
            profiles.put(identity, new NetworkIdentityResolver.Profile(identity, kind, label));
        }
        for (NetworkIdentityResolver.Profile profile : networkMonitor.availableProfiles()) {
            profiles.put(profile.identity(), profile);
        }
        for (NetworkSelectionStore.Profile profile : stored.values()) {
            String storedLabel = profile.label();
            // v1 records cannot be mapped back to a displayable network without
            // weakening the hashed identity, so keep them hidden in the UI.
            if (storedLabel == null || storedLabel.isBlank()) {
                continue;
            }
            profiles.putIfAbsent(
                    profile.identity(),
                    new NetworkIdentityResolver.Profile(
                            profile.identity(),
                            profile.kind(),
                            storedLabel
                    )
            );
        }

        // The live group/option catalog is identical for every remembered
        // network; only the "selected" overlay differs per profile. Fetch it
        // once instead of once per profile (previously up to
        // NetworkSelectionStore.MAX_NETWORKS controller round-trips).
        JSONObject proxiesSnapshot = runtime.proxySnapshot();
        JSONArray encodedProfiles = new JSONArray();
        for (NetworkIdentityResolver.Profile profile : profiles.values()) {
            NetworkSelectionStore.Profile storedProfile = stored.get(profile.identity());
            Map<String, String> selections = storedProfile == null
                    ? Map.of()
                    : storedProfile.selections();
            if (selections.isEmpty() && profile.identity().equals(identity)) {
                selections = runtime.selectorSelections(proxiesSnapshot);
            }
            JSONObject encoded = new JSONObject();
            encoded.put("identity", profile.identity());
            encoded.put("kind", profile.kind());
            encoded.put("label", profile.label());
            encoded.put("current", profile.identity().equals(identity));
            encoded.put("groups", runtime.selectorCatalog(proxiesSnapshot, selections));
            encodedProfiles.put(encoded);
        }
        return new JSONObject().put("profiles", encodedProfiles).toString();
    }

    String select(
            MihomoRuntime runtime,
            String requestedIdentity,
            String group,
            String target
    ) throws IOException {
        if (requestedIdentity == null || requestedIdentity.isBlank()
                || group == null || group.isBlank()
                || target == null || target.isBlank()) {
            throw new IOException("网络、策略组或节点为空");
        }
        String selectedKind = "";
        String selectedLabel = "";
        for (NetworkIdentityResolver.Profile profile : networkMonitor.availableProfiles()) {
            if (requestedIdentity.equals(profile.identity())) {
                selectedKind = profile.kind();
                selectedLabel = profile.label();
                break;
            }
        }
        Map<String, String> selections = new HashMap<>(store.selections(requestedIdentity));
        if (selections.isEmpty()) {
            selections.putAll(runtime.selectorSelections());
        }
        JSONObject groupCatalog = runtime.selectorCatalog(selections).optJSONObject(group);
        if (groupCatalog == null || !catalogContains(groupCatalog, target)) {
            throw new IOException("策略组或节点已不存在");
        }
        selections.put(group, target);
        if (requestedIdentity.equals(identity)) {
            runtime.selectSelector(group, target);
            selectedKind = kind;
            selectedLabel = label;
        }
        if (!store.save(requestedIdentity, selectedKind, selectedLabel, selections)) {
            throw new IOException("节点选择无法写入设备存储");
        }
        if (requestedIdentity.equals(identity)) {
            ready = true;
        }
        return "已为 " + nonBlank(selectedLabel, "该网络")
                + " 设置 " + group + " → " + target;
    }

    private boolean save(
            MihomoRuntime runtime,
            String targetIdentity,
            String targetKind,
            String targetLabel
    ) {
        try {
            return store.save(
                    targetIdentity,
                    targetKind,
                    targetLabel,
                    runtime.selectorSelections()
            );
        } catch (IOException exception) {
            Log.w(TAG, "Unable to remember selector choices", exception);
            return false;
        }
    }

    private static boolean catalogContains(JSONObject group, String target) {
        JSONArray options = group.optJSONArray("options");
        if (options == null) {
            return false;
        }
        for (int index = 0; index < options.length(); index++) {
            JSONObject option = options.optJSONObject(index);
            if (option != null && target.equals(option.optString("name", ""))) {
                return option.optBoolean("available", true);
            }
        }
        return false;
    }

    private static String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
