package io.github.qwqgong.androidcyaml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class TunOptionsCodec {
    private TunOptionsCodec() {}

    static TunOptions fromJson(JSONObject object) throws IOException {
        try {
            int mtu = object.optInt("mtu", 9000);
            if (mtu < 1280 || mtu > 65_535) {
                throw new IOException("无效的 TUN MTU：" + mtu);
            }
            return new TunOptions(
                    mtu,
                    strings(object.optJSONArray("inet4Address")),
                    strings(object.optJSONArray("inet6Address")),
                    object.optBoolean("autoRoute", true),
                    strings(object.optJSONArray("inet4RouteAddress")),
                    strings(object.optJSONArray("inet6RouteAddress")),
                    strings(object.optJSONArray("inet4RouteExcludeAddress")),
                    strings(object.optJSONArray("inet6RouteExcludeAddress")),
                    strings(object.optJSONArray("dnsServerAddress")),
                    strings(object.optJSONArray("includePackage")),
                    strings(object.optJSONArray("excludePackage"))
            );
        } catch (JSONException exception) {
            throw new IOException("无法解析 mihomo TUN 参数", exception);
        }
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        if (array == null || array.length() == 0) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            String value = array.getString(index);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
