package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Persists recent selector choices by hashed physical-network identity. */
final class NetworkSelectionStore {
    record Profile(
            String identity,
            String kind,
            String label,
            long updatedAt,
            Map<String, String> selections
    ) {}

    private record StoredNetwork(
            String kind,
            String label,
            long updatedAt,
            Map<String, String> selections
    ) {}

    private static final String TAG = "AndroidCyaml/Selections";
    private static final String PREFERENCES = "androidcyaml_network_selections";
    private static final String DOCUMENT = "network_selections_v1";
    private static final String NETWORKS = "networks";
    private static final String UPDATED_AT = "updatedAt";
    private static final String SELECTIONS = "selections";
    private static final String KIND = "kind";
    private static final String LABEL = "label";
    private static final int MAX_NETWORKS = 24;
    private static final int MAX_SELECTORS_PER_NETWORK = 128;
    private static final long MAX_AGE_MILLIS = 90L * 24L * 60L * 60L * 1_000L;

    private final SharedPreferences preferences;

    NetworkSelectionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    Map<String, String> selections(String networkIdentity) {
        if (networkIdentity == null || networkIdentity.isBlank()) {
            return Map.of();
        }
        Map<String, StoredNetwork> networks = readNetworks();
        StoredNetwork stored = networks.get(networkIdentity);
        if (stored == null || isExpired(stored.updatedAt(), System.currentTimeMillis())) {
            return Map.of();
        }
        return Map.copyOf(stored.selections());
    }

    boolean save(String networkIdentity, Map<String, String> selections) {
        return save(networkIdentity, "", "", selections);
    }

    boolean save(
            String networkIdentity,
            String kind,
            String label,
            Map<String, String> selections
    ) {
        if (networkIdentity == null || networkIdentity.isBlank()
                || selections == null || selections.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Map<String, String> sanitized = sanitizeSelections(selections);
        if (sanitized.isEmpty()) {
            return true;
        }
        Map<String, StoredNetwork> networks = readNetworks();
        networks.entrySet().removeIf(entry -> isExpired(entry.getValue().updatedAt(), now));
        StoredNetwork previous = networks.get(networkIdentity);
        String storedKind = nonBlank(kind, previous == null ? "" : previous.kind());
        String storedLabel = nonBlank(label, previous == null ? "" : previous.label());
        networks.put(
                networkIdentity,
                new StoredNetwork(storedKind, storedLabel, now, sanitized)
        );
        trimOldest(networks);
        // These writes happen only on network/lifecycle boundaries. commit()
        // makes the memory-kill acknowledgement truthful: once it returns, the
        // latest selector state is on disk rather than queued in this process.
        return preferences.edit().putString(DOCUMENT, encodeNetworks(networks)).commit();
    }

    List<Profile> profiles() {
        List<Profile> profiles = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, StoredNetwork> entry : readNetworks().entrySet()) {
            StoredNetwork stored = entry.getValue();
            if (!isExpired(stored.updatedAt(), now)) {
                profiles.add(new Profile(
                        entry.getKey(),
                        stored.kind(),
                        stored.label(),
                        stored.updatedAt(),
                        Map.copyOf(stored.selections())
                ));
            }
        }
        profiles.sort(Comparator.comparingLong(Profile::updatedAt).reversed());
        return List.copyOf(profiles);
    }

    private Map<String, StoredNetwork> readNetworks() {
        Map<String, StoredNetwork> networks = new HashMap<>();
        String document = preferences.getString(DOCUMENT, "");
        if (document == null || document.isBlank()) {
            return networks;
        }
        try {
            JSONObject root = new JSONObject(document);
            JSONObject encodedNetworks = root.optJSONObject(NETWORKS);
            if (encodedNetworks == null) {
                return networks;
            }
            Iterator<String> identities = encodedNetworks.keys();
            while (identities.hasNext()) {
                String identity = identities.next();
                JSONObject encodedNetwork = encodedNetworks.optJSONObject(identity);
                if (encodedNetwork == null) {
                    continue;
                }
                JSONObject encodedSelections = encodedNetwork.optJSONObject(SELECTIONS);
                if (encodedSelections == null) {
                    continue;
                }
                Map<String, String> selections = new HashMap<>();
                Iterator<String> groups = encodedSelections.keys();
                while (groups.hasNext() && selections.size() < MAX_SELECTORS_PER_NETWORK) {
                    String group = groups.next();
                    String target = encodedSelections.optString(group, "");
                    if (!group.isBlank() && !target.isBlank()) {
                        selections.put(group, target);
                    }
                }
                if (!selections.isEmpty()) {
                    networks.put(identity, new StoredNetwork(
                            encodedNetwork.optString(KIND, ""),
                            encodedNetwork.optString(LABEL, ""),
                            encodedNetwork.optLong(UPDATED_AT, 0L),
                            Map.copyOf(selections)
                    ));
                }
            }
        } catch (JSONException | ClassCastException exception) {
            Log.w(TAG, "Ignoring malformed network-selection memory", exception);
        }
        return networks;
    }

    private static String encodeNetworks(Map<String, StoredNetwork> networks) {
        try {
            JSONObject encodedNetworks = new JSONObject();
            for (Map.Entry<String, StoredNetwork> entry : networks.entrySet()) {
                JSONObject encodedSelections = new JSONObject();
                for (Map.Entry<String, String> selection
                        : entry.getValue().selections().entrySet()) {
                    encodedSelections.put(selection.getKey(), selection.getValue());
                }
                JSONObject encodedNetwork = new JSONObject();
                encodedNetwork.put(KIND, entry.getValue().kind());
                encodedNetwork.put(LABEL, entry.getValue().label());
                encodedNetwork.put(UPDATED_AT, entry.getValue().updatedAt());
                encodedNetwork.put(SELECTIONS, encodedSelections);
                encodedNetworks.put(entry.getKey(), encodedNetwork);
            }
            JSONObject root = new JSONObject();
            root.put(NETWORKS, encodedNetworks);
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode network selections", impossible);
        }
    }

    private static Map<String, String> sanitizeSelections(Map<String, String> selections) {
        Map<String, String> sanitized = new HashMap<>();
        for (Map.Entry<String, String> entry : selections.entrySet()) {
            String group = entry.getKey();
            String target = entry.getValue();
            if (group == null || target == null || group.isBlank() || target.isBlank()) {
                continue;
            }
            sanitized.put(group, target);
            if (sanitized.size() >= MAX_SELECTORS_PER_NETWORK) {
                break;
            }
        }
        return Map.copyOf(sanitized);
    }

    private static void trimOldest(Map<String, StoredNetwork> networks) {
        if (networks.size() <= MAX_NETWORKS) {
            return;
        }
        List<Map.Entry<String, StoredNetwork>> ordered = new ArrayList<>(networks.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().updatedAt()));
        for (int index = 0; index < ordered.size() - MAX_NETWORKS; index++) {
            networks.remove(ordered.get(index).getKey());
        }
    }

    private static boolean isExpired(long updatedAt, long now) {
        return updatedAt <= 0L || now - updatedAt > MAX_AGE_MILLIS;
    }

    private static String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank()
                ? (fallback == null ? "" : fallback)
                : preferred;
    }
}
