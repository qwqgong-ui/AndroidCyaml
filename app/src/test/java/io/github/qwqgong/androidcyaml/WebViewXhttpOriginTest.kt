package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WebViewXhttpOriginTest {
    @Test
    fun normalizesHttpsOrigin() {
        assertEquals(
            "https://plus.238139.xyz",
            WebViewXhttpDialer.originOf(
                "https://PLUS.238139.XYZ:443/eb7cd544d5357f2989bdf75b.html?id=x",
            ),
        )
        assertEquals(
            "https://[2001:db8::1]:8443",
            WebViewXhttpDialer.originOf("https://[2001:db8::1]:8443/x"),
        )
    }

    @Test
    fun rejectsNonHttpsAndUserInfo() {
        assertThrows(IOException::class.java) {
            WebViewXhttpDialer.originOf("http://example.com/x")
        }
        assertThrows(IOException::class.java) {
            WebViewXhttpDialer.originOf("https://user@example.com/x")
        }
    }
}
