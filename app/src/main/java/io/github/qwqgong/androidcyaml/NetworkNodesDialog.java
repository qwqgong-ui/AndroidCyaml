package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class NetworkNodesDialog {
    interface Listener {
        void onMessage(String message);

        void onTargetSelected(String identity, String group, String target);
    }

    private NetworkNodesDialog() {}

    static void show(Context context, String catalogJson, Listener listener) {
        try {
            showProfiles(context, new JSONObject(catalogJson), listener);
        } catch (JSONException exception) {
            listener.onMessage("无法解析网络节点列表");
        }
    }

    private static void showProfiles(
            Context context,
            JSONObject catalog,
            Listener listener
    ) throws JSONException {
        JSONArray profiles = catalog.optJSONArray("profiles");
        if (profiles == null || profiles.length() == 0) {
            listener.onMessage("当前没有可识别或已记忆的 Wi-Fi / 移动数据网络");
            return;
        }
        List<JSONObject> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.optJSONObject(index);
            if (profile == null) {
                continue;
            }
            values.add(profile);
            String label = profile.optString("label", "已记忆网络");
            if (profile.optBoolean("current", false)) {
                label += "（当前）";
            }
            String summary = summarizeGroups(profile.optJSONObject("groups"));
            labels.add(summary.isBlank() ? label : label + "\n" + summary);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.network_nodes_title)
                .setItems(labels.toArray(String[]::new), (dialog, which) -> {
                    try {
                        JSONObject profile = values.get(which);
                        JSONObject groups = profile.optJSONObject("groups");
                        if (groups == null || groups.length() == 0) {
                            listener.onMessage("config.yaml 的第一个策略组不是 Selector");
                            return;
                        }
                        Iterator<String> names = groups.keys();
                        showTargets(context, profile, names.next(), listener);
                    } catch (JSONException exception) {
                        listener.onMessage("无法解析节点列表");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void showTargets(
            Context context,
            JSONObject profile,
            String groupName,
            Listener listener
    ) throws JSONException {
        JSONObject group = profile.getJSONObject("groups").getJSONObject(groupName);
        String selected = group.optString("selected", "");
        JSONArray options = group.optJSONArray("options");
        List<JSONObject> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int selectedIndex = -1;
        if (options != null) {
            for (int index = 0; index < options.length(); index++) {
                JSONObject option = options.optJSONObject(index);
                if (option == null || !option.optBoolean("available", true)) {
                    continue;
                }
                values.add(option);
                String name = option.optString("name", "");
                String label = displayTarget(name, option.optString("effective", ""));
                labels.add((name.equals(selected) ? "✓ " : "") + label);
                if (name.equals(selected)) {
                    selectedIndex = values.size() - 1;
                }
            }
        }
        if (values.isEmpty()) {
            listener.onMessage("该策略组当前没有可用节点");
            return;
        }
        int checkedIndex = selectedIndex;
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.network_targets_title, groupName))
                .setSingleChoiceItems(
                        labels.toArray(String[]::new),
                        checkedIndex,
                        (dialog, which) -> {
                            dialog.dismiss();
                            JSONObject option = values.get(which);
                            String target = option.optString("name", "");
                            listener.onTargetSelected(
                                    profile.optString("identity", ""),
                                    groupName,
                                    target
                            );
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String summarizeGroups(JSONObject groups) {
        if (groups == null) {
            return "";
        }
        List<String> summaries = new ArrayList<>();
        Iterator<String> keys = groups.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject group = groups.optJSONObject(name);
            if (group != null) {
                summaries.add(name + "：" + displayTarget(
                        group.optString("selected", ""),
                        group.optString("effective", "")
                ));
            }
        }
        summaries.sort(String::compareTo);
        return String.join("；", summaries);
    }

    private static String displayTarget(String selected, String effective) {
        if (selected == null || selected.isBlank()) {
            return "未选择";
        }
        return effective == null || effective.isBlank() || effective.equals(selected)
                ? selected
                : selected + " → " + effective;
    }
}
