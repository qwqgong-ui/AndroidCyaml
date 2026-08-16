package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

public final class MihomoControllerTest {
    @Test
    public void selectorCatalogReportsDirectTargetAndEffectiveLeafNode() throws Exception {
        JSONObject proxies = new JSONObject()
                .put("main", proxy("Selector", "automatic", "automatic", "manual"))
                .put("automatic", proxy("URLTest", "jp-02", "jp-01", "jp-02"))
                .put("manual", proxy("Selector", "us-01", "us-01", "us-02"))
                .put("jp-01", leaf("VLESS"))
                .put("jp-02", leaf("VLESS"))
                .put("us-01", leaf("VLESS"))
                .put("us-02", leaf("VLESS"));

        JSONObject automatic = MihomoController.selectorCatalog(proxies, Map.of())
                .getJSONObject("main");
        assertEquals("automatic", automatic.getString("selected"));
        assertEquals("jp-02", automatic.getString("effective"));

        JSONObject manual = MihomoController.selectorCatalog(
                proxies,
                Map.of("main", "manual", "manual", "us-02")
        ).getJSONObject("main");
        assertEquals("manual", manual.getString("selected"));
        assertEquals("us-02", manual.getString("effective"));
        assertEquals("jp-02", manual.getJSONArray("options")
                .getJSONObject(0).getString("effective"));
    }

    private static JSONObject proxy(String type, String now, String... all) throws Exception {
        return new JSONObject()
                .put("type", type)
                .put("now", now)
                .put("all", new JSONArray(all))
                .put("alive", true);
    }

    private static JSONObject leaf(String type) throws Exception {
        return new JSONObject().put("type", type).put("alive", true);
    }
}
