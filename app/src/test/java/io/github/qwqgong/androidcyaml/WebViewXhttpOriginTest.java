package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public final class WebViewXhttpOriginTest {
    @Test
    public void normalizesHttpsOrigin() throws Exception {
        assertEquals(
                "https://plus.238139.xyz",
                WebViewXhttpDialer.originOf(
                        "https://PLUS.238139.XYZ:443/eb7cd544d5357f2989bdf75b.html?id=x"
                )
        );
        assertEquals(
                "https://[2001:db8::1]:8443",
                WebViewXhttpDialer.originOf("https://[2001:db8::1]:8443/x")
        );
    }

    @Test
    public void rejectsNonHttpsAndUserInfo() {
        assertThrows(
                IOException.class,
                () -> WebViewXhttpDialer.originOf("http://example.com/x")
        );
        assertThrows(
                IOException.class,
                () -> WebViewXhttpDialer.originOf("https://user@example.com/x")
        );
    }
}
