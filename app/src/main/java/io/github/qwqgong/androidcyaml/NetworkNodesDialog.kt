package io.github.qwqgong.androidcyaml

import android.content.Context
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import org.json.JSONException
import org.json.JSONObject

object NetworkNodesDialog {
    interface Listener {
        fun onMessage(message: String)

        fun onTargetSelected(identity: String, group: String, target: String)
    }

    private const val HEADER = 0
    private const val FIRST_ENTRY = 1

    fun show(context: Context, anchor: View, catalogJson: String, listener: Listener) {
        try {
            showProfiles(context, anchor, JSONObject(catalogJson), listener)
        } catch (exception: JSONException) {
            listener.onMessage("无法解析网络节点列表")
        }
    }

    private fun showProfiles(
        context: Context,
        anchor: View,
        catalog: JSONObject,
        listener: Listener,
    ) {
        val profiles = catalog.optJSONArray("profiles")
        if (profiles == null || profiles.length() == 0) {
            listener.onMessage("当前没有可识别或已记忆的 Wi-Fi / 移动数据网络")
            return
        }
        val values = ArrayList<JSONObject>()
        val labels = ArrayList<String>()
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index) ?: continue
            values.add(profile)
            var label = profile.optString("label", "已记忆网络")
            if (profile.optBoolean("current", false)) {
                label += "（当前）"
            }
            val summary = summarizeGroups(profile.optJSONObject("groups"))
            labels.add(if (summary.isBlank()) label else label + " · " + summary)
        }
        showMenu(context, anchor, context.getString(R.string.network_nodes_title), labels) { which ->
            try {
                val profile = values[which]
                val groups = profile.optJSONObject("groups")
                if (groups == null || groups.length() == 0) {
                    listener.onMessage("config.yaml 的第一个策略组不是 Selector")
                    return@showMenu
                }
                showTargets(context, anchor, profile, groups.keys().next(), listener)
            } catch (exception: JSONException) {
                listener.onMessage("无法解析节点列表")
            }
        }
    }

    private fun showTargets(
        context: Context,
        anchor: View,
        profile: JSONObject,
        groupName: String,
        listener: Listener,
    ) {
        val group = profile.getJSONObject("groups").getJSONObject(groupName)
        val selected = group.optString("selected", "")
        val options = group.optJSONArray("options")
        val values = ArrayList<JSONObject>()
        val labels = ArrayList<String>()
        if (options != null) {
            for (index in 0 until options.length()) {
                val option = options.optJSONObject(index)
                if (option == null || !option.optBoolean("available", true)) {
                    continue
                }
                values.add(option)
                val name = option.optString("name", "")
                var label = displayTarget(name, option.optString("effective", ""))
                if (name == selected) {
                    label += "（当前）"
                }
                labels.add(label)
            }
        }
        if (values.isEmpty()) {
            listener.onMessage("该策略组当前没有可用节点")
            return
        }
        val title = context.getString(R.string.network_targets_title, groupName)
        showMenu(context, anchor, title, labels) { which ->
            val target = values[which].optString("name", "")
            listener.onTargetSelected(profile.optString("identity", ""), groupName, target)
        }
    }

    private fun showMenu(
        context: Context,
        anchor: View,
        title: String,
        labels: List<String>,
        onSelected: (Int) -> Unit,
    ) {
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        menu.add(Menu.NONE, HEADER, 0, title).isEnabled = false
        for (index in labels.indices) {
            menu.add(Menu.NONE, FIRST_ENTRY + index, FIRST_ENTRY + index, labels[index])
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == HEADER) {
                false
            } else {
                onSelected(item.itemId - FIRST_ENTRY)
                true
            }
        }
        popup.show()
    }

    private fun summarizeGroups(groups: JSONObject?): String {
        if (groups == null) {
            return ""
        }
        val summaries = ArrayList<String>()
        for (name in groups.keys()) {
            val group = groups.optJSONObject(name)
            if (group != null) {
                summaries.add(
                    name + "：" + displayTarget(
                        group.optString("selected", ""),
                        group.optString("effective", ""),
                    ),
                )
            }
        }
        summaries.sort()
        return summaries.joinToString("；")
    }

    private fun displayTarget(selected: String?, effective: String?): String {
        if (selected.isNullOrBlank()) {
            return "未选择"
        }
        return if (effective.isNullOrBlank() || effective == selected) {
            selected
        } else {
            "$selected → $effective"
        }
    }
}
