package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WebViewBinaryBridgeTest {
    @Test
    public void encodesUtf8RequestStart() {
        byte[] frame = WebViewBinaryBridge.encodeRequestStart(
                "{\"host\":\"plus.example\",\"note\":\"流\"}"
        );

        assertEquals(WebViewBinaryBridge.TYPE_REQUEST_START, frame[0]);
        assertEquals(
                "{\"host\":\"plus.example\",\"note\":\"流\"}",
                new String(frame, 1, frame.length - 1, java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    @Test
    public void encodesStreamingRequestBodyChunk() {
        byte[] frame = WebViewBinaryBridge.encodeRequestBodyChunk(
                0x0102030405060708L,
                0x1112131415161718L,
                1,
                new byte[]{9, 8, 7, 6},
                3
        );

        ByteBuffer decoded = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        assertEquals(WebViewBinaryBridge.TYPE_REQUEST_BODY_CHUNK, decoded.get());
        assertEquals(0x0102030405060708L, decoded.getLong());
        assertEquals(0x1112131415161718L, decoded.getLong());
        assertEquals(1, decoded.get());
        byte[] payload = new byte[decoded.remaining()];
        decoded.get(payload);
        assertArrayEquals(new byte[]{9, 8, 7}, payload);
    }

    @Test
    public void omitsPayloadForEndOfStream() {
        byte[] frame = WebViewBinaryBridge.encodeRequestBodyChunk(
                1L,
                2L,
                0,
                new byte[]{9, 8, 7},
                3
        );

        assertEquals(WebViewBinaryBridge.REQUEST_BODY_HEADER_BYTES, frame.length);
        assertEquals(0, frame[17]);
    }

    @Test
    public void decodesResponseChunk() {
        ByteBuffer frame = ByteBuffer
                .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + 3)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_CHUNK);
        frame.putLong(42L);
        frame.put(new byte[]{3, 4, 5});

        WebViewBinaryBridge.ResponseFrame decoded =
                WebViewBinaryBridge.decodeResponseFrame(frame.array());

        assertEquals(42L, decoded.requestId());
        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_CHUNK, decoded.type());
        assertArrayEquals(new byte[]{3, 4, 5}, decoded.data());
    }

    @Test
    public void decodesResponseCompletion() {
        ByteBuffer frame = ByteBuffer
                .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_COMPLETE);
        frame.putLong(43L);

        WebViewBinaryBridge.ResponseFrame decoded =
                WebViewBinaryBridge.decodeResponseFrame(frame.array());

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_COMPLETE, decoded.type());
        assertEquals(43L, decoded.requestId());
        assertArrayEquals(new byte[0], decoded.data());
        assertEquals("", decoded.error());
    }

    @Test
    public void decodesUtf8ResponseError() {
        byte[] message = "stream 中断".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer
                .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + message.length)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_ERROR);
        frame.putLong(44L);
        frame.put(message);

        WebViewBinaryBridge.ResponseFrame decoded =
                WebViewBinaryBridge.decodeResponseFrame(frame.array());

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_ERROR, decoded.type());
        assertEquals(44L, decoded.requestId());
        assertEquals("stream 中断", decoded.error());
    }

    @Test
    public void decodesResponseHeaders() {
        byte[] payload = "{\"status\":204,\"headers\":{}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer
                .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_HEADERS);
        frame.putLong(45L);
        frame.put(payload);

        WebViewBinaryBridge.ResponseFrame decoded =
                WebViewBinaryBridge.decodeResponseFrame(frame.array());

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_HEADERS, decoded.type());
        assertEquals(45L, decoded.requestId());
        assertArrayEquals(payload, decoded.data());
    }

    @Test
    public void rejectsMalformedFrames() {
        assertNull(WebViewBinaryBridge.decodeResponseFrame(null));
        assertNull(WebViewBinaryBridge.decodeResponseFrame(new byte[8]));
        assertNull(WebViewBinaryBridge.decodeResponseFrame(new byte[9]));
        byte[] unknownType = new byte[WebViewBinaryBridge.RESPONSE_HEADER_BYTES];
        unknownType[0] = 99;
        assertNull(WebViewBinaryBridge.decodeResponseFrame(unknownType));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebViewBinaryBridge.encodeRequestBodyChunk(0L, 1L, 1, new byte[]{1}, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WebViewBinaryBridge.encodeRequestStart(" ")
        );
    }
}
