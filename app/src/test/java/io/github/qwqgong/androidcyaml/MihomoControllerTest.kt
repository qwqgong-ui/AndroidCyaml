package io.github.qwqgong.androidcyaml

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MihomoControllerTest {
    @Test
    fun selectorCatalogReportsDirectTargetAndEffectiveLeafNode() {
        val proxies = JSONObject()
            .put("main", proxy("Selector", "automatic", "automatic", "manual"))
            .put("automatic", proxy("URLTest", "jp-02", "jp-01", "jp-02"))
            .put("manual", proxy("Selector", "us-01", "us-01", "us-02"))
            .put("jp-01", leaf("VLESS"))
            .put("jp-02", leaf("VLESS"))
            .put("us-01", leaf("VLESS"))
            .put("us-02", leaf("VLESS"))

        val automatic = MihomoController.selectorCatalog(proxies, emptyMap())
            .getJSONObject("main")
        assertEquals("automatic", automatic.getString("selected"))
        assertEquals("jp-02", automatic.getString("effective"))

        val manual = MihomoController.selectorCatalog(
            proxies,
            mapOf("main" to "manual", "manual" to "us-02"),
        ).getJSONObject("main")
        assertEquals("manual", manual.getString("selected"))
        assertEquals("us-02", manual.getString("effective"))
        assertEquals(
            "jp-02",
            manual.getJSONArray("options").getJSONObject(0).getString("effective"),
        )
    }

    private fun proxy(type: String, now: String, vararg all: String): JSONObject = JSONObject()
        .put("type", type)
        .put("now", now)
        .put("all", JSONArray(all))
        .put("alive", true)

    private fun leaf(type: String): JSONObject =
        JSONObject().put("type", type).put("alive", true)
}
