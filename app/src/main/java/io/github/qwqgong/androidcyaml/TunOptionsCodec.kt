package io.github.qwqgong.androidcyaml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

object TunOptionsCodec {
    fun fromJson(payload: JSONObject): TunOptions {
        try {
            val mtu = payload.optInt("mtu", 9000)
            if (mtu < 1280 || mtu > 65_535) {
                throw IOException("无效的 TUN MTU：$mtu")
            }
            return TunOptions(
                mtu,
                strings(payload.optJSONArray("inet4Address")),
                strings(payload.optJSONArray("inet6Address")),
                payload.optBoolean("autoRoute", true),
                strings(payload.optJSONArray("inet4RouteAddress")),
                strings(payload.optJSONArray("inet6RouteAddress")),
                strings(payload.optJSONArray("inet4RouteExcludeAddress")),
                strings(payload.optJSONArray("inet6RouteExcludeAddress")),
                strings(payload.optJSONArray("dnsServerAddress")),
                strings(payload.optJSONArray("includePackage")),
                strings(payload.optJSONArray("excludePackage")),
            )
        } catch (exception: JSONException) {
            throw IOException("无法解析 mihomo TUN 参数", exception)
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) {
            return emptyList()
        }
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.getString(index)
            if (value.isNotBlank()) {
                values.add(value)
            }
        }
        return values
    }
}
