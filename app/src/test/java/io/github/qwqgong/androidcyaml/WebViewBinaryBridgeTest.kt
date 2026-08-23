package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class WebViewBinaryBridgeTest {
    @Test
    fun encodesUtf8RequestStart() {
        val frame = WebViewBinaryBridge.encodeRequestStart(
            "{\"host\":\"plus.example\",\"note\":\"流\"}",
        )

        assertEquals(WebViewBinaryBridge.TYPE_REQUEST_START, frame[0])
        assertEquals(
            "{\"host\":\"plus.example\",\"note\":\"流\"}",
            String(frame, 1, frame.size - 1, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun encodesStreamingRequestBodyChunk() {
        val frame = WebViewBinaryBridge.encodeRequestBodyChunk(
            0x0102030405060708L,
            0x1112131415161718L,
            1,
            byteArrayOf(9, 8, 7, 6),
            3,
        )

        val decoded = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        assertEquals(WebViewBinaryBridge.TYPE_REQUEST_BODY_CHUNK, decoded.get())
        assertEquals(0x0102030405060708L, decoded.getLong())
        assertEquals(0x1112131415161718L, decoded.getLong())
        assertEquals(1.toByte(), decoded.get())
        val payload = ByteArray(decoded.remaining())
        decoded.get(payload)
        assertArrayEquals(byteArrayOf(9, 8, 7), payload)
    }

    @Test
    fun omitsPayloadForEndOfStream() {
        val frame = WebViewBinaryBridge.encodeRequestBodyChunk(
            1L,
            2L,
            0,
            byteArrayOf(9, 8, 7),
            3,
        )

        assertEquals(WebViewBinaryBridge.REQUEST_BODY_HEADER_BYTES, frame.size)
        assertEquals(0.toByte(), frame[17])
    }

    @Test
    fun decodesResponseChunk() {
        val frame = ByteBuffer
            .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + 3)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_CHUNK)
        frame.putLong(42L)
        frame.put(byteArrayOf(3, 4, 5))

        val decoded = WebViewBinaryBridge.decodeResponseFrame(frame.array())!!

        assertEquals(42L, decoded.requestId)
        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_CHUNK, decoded.type)
        assertArrayEquals(byteArrayOf(3, 4, 5), decoded.data)
    }

    @Test
    fun decodesResponseCompletion() {
        val frame = ByteBuffer
            .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_COMPLETE)
        frame.putLong(43L)

        val decoded = WebViewBinaryBridge.decodeResponseFrame(frame.array())!!

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_COMPLETE, decoded.type)
        assertEquals(43L, decoded.requestId)
        assertArrayEquals(ByteArray(0), decoded.data)
        assertEquals("", decoded.error)
    }

    @Test
    fun decodesUtf8ResponseError() {
        val message = "stream 中断".toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer
            .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + message.size)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_ERROR)
        frame.putLong(44L)
        frame.put(message)

        val decoded = WebViewBinaryBridge.decodeResponseFrame(frame.array())!!

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_ERROR, decoded.type)
        assertEquals(44L, decoded.requestId)
        assertEquals("stream 中断", decoded.error)
    }

    @Test
    fun decodesResponseHeaders() {
        val payload = "{\"status\":204,\"headers\":{}}".toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer
            .allocate(WebViewBinaryBridge.RESPONSE_HEADER_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(WebViewBinaryBridge.TYPE_RESPONSE_HEADERS)
        frame.putLong(45L)
        frame.put(payload)

        val decoded = WebViewBinaryBridge.decodeResponseFrame(frame.array())!!

        assertEquals(WebViewBinaryBridge.TYPE_RESPONSE_HEADERS, decoded.type)
        assertEquals(45L, decoded.requestId)
        assertArrayEquals(payload, decoded.data)
    }

    @Test
    fun rejectsMalformedFrames() {
        assertNull(WebViewBinaryBridge.decodeResponseFrame(null))
        assertNull(WebViewBinaryBridge.decodeResponseFrame(ByteArray(8)))
        assertNull(WebViewBinaryBridge.decodeResponseFrame(ByteArray(9)))
        val unknownType = ByteArray(WebViewBinaryBridge.RESPONSE_HEADER_BYTES)
        unknownType[0] = 99
        assertNull(WebViewBinaryBridge.decodeResponseFrame(unknownType))
        assertThrows(IllegalArgumentException::class.java) {
            WebViewBinaryBridge.encodeRequestBodyChunk(0L, 1L, 1, byteArrayOf(1), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebViewBinaryBridge.encodeRequestStart(" ")
        }
    }
}
