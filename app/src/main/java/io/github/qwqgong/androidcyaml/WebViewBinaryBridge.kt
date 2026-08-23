package io.github.qwqgong.androidcyaml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Binary frames exchanged with the WebView message port. */
object WebViewBinaryBridge {
    const val TYPE_REQUEST_BODY_CHUNK: Byte = 1
    const val TYPE_RESPONSE_CHUNK: Byte = 2
    const val TYPE_RESPONSE_COMPLETE: Byte = 3
    const val TYPE_RESPONSE_ERROR: Byte = 4
    const val TYPE_REQUEST_START: Byte = 5
    const val TYPE_RESPONSE_HEADERS: Byte = 6
    const val REQUEST_BODY_HEADER_BYTES = 18
    const val RESPONSE_HEADER_BYTES = 9

    data class ResponseFrame(
        val type: Byte,
        val requestId: Long,
        val data: ByteArray,
        val error: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is ResponseFrame) {
                return false
            }
            return type == other.type &&
                requestId == other.requestId &&
                data.contentEquals(other.data) &&
                error == other.error
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + requestId.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + error.hashCode()
            return result
        }
    }

    fun encodeRequestStart(metadataJson: String?): ByteArray {
        if (metadataJson.isNullOrBlank()) {
            throw IllegalArgumentException("Request metadata is empty")
        }
        val payload = metadataJson.toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer.allocate(1 + payload.size)
        frame.put(TYPE_REQUEST_START)
        frame.put(payload)
        return frame.array()
    }

    fun encodeRequestBodyChunk(
        bodyId: Long,
        readId: Long,
        status: Int,
        payload: ByteArray?,
        length: Int,
    ): ByteArray {
        if (bodyId <= 0L || readId <= 0L || status < -1 || status > 1) {
            throw IllegalArgumentException("Invalid request body frame metadata")
        }
        val payloadLength = if (status > 0) length else 0
        if (payloadLength < 0 ||
            (payloadLength > 0 && (payload == null || payloadLength > payload.size))
        ) {
            throw IllegalArgumentException("Invalid request body frame payload")
        }
        val frame = ByteBuffer
            .allocate(REQUEST_BODY_HEADER_BYTES + payloadLength)
            .order(ByteOrder.BIG_ENDIAN)
        frame.put(TYPE_REQUEST_BODY_CHUNK)
        frame.putLong(bodyId)
        frame.putLong(readId)
        frame.put(status.toByte())
        if (payloadLength > 0) {
            frame.put(payload!!, 0, payloadLength)
        }
        return frame.array()
    }

    fun decodeResponseFrame(frameBytes: ByteArray?): ResponseFrame? {
        if (frameBytes == null || frameBytes.size < RESPONSE_HEADER_BYTES) {
            return null
        }
        val frame = ByteBuffer.wrap(frameBytes).order(ByteOrder.BIG_ENDIAN)
        val type = frame.get()
        if (type != TYPE_RESPONSE_CHUNK &&
            type != TYPE_RESPONSE_COMPLETE &&
            type != TYPE_RESPONSE_ERROR &&
            type != TYPE_RESPONSE_HEADERS
        ) {
            return null
        }
        val requestId = frame.getLong()
        if (requestId <= 0L) {
            return null
        }
        val payload = frameBytes.copyOfRange(RESPONSE_HEADER_BYTES, frameBytes.size)
        return ResponseFrame(
            type,
            requestId,
            payload,
            if (type == TYPE_RESPONSE_ERROR) String(payload, StandardCharsets.UTF_8) else "",
        )
    }
}
