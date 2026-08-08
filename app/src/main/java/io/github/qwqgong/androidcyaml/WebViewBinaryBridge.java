package io.github.qwqgong.androidcyaml;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Binary frames exchanged with the WebView message port. */
final class WebViewBinaryBridge {
    static final byte TYPE_REQUEST_BODY_CHUNK = 1;
    static final byte TYPE_RESPONSE_CHUNK = 2;
    static final byte TYPE_RESPONSE_COMPLETE = 3;
    static final byte TYPE_RESPONSE_ERROR = 4;
    static final byte TYPE_REQUEST_START = 5;
    static final byte TYPE_RESPONSE_HEADERS = 6;
    static final int REQUEST_BODY_HEADER_BYTES = 18;
    static final int RESPONSE_HEADER_BYTES = 9;

    private WebViewBinaryBridge() {}

    static byte[] encodeRequestStart(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            throw new IllegalArgumentException("Request metadata is empty");
        }
        byte[] payload = metadataJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(1 + payload.length);
        frame.put(TYPE_REQUEST_START);
        frame.put(payload);
        return frame.array();
    }

    static byte[] encodeRequestBodyChunk(
            long bodyId,
            long readId,
            int status,
            byte[] payload,
            int length
    ) {
        if (bodyId <= 0L || readId <= 0L || status < -1 || status > 1) {
            throw new IllegalArgumentException("Invalid request body frame metadata");
        }
        int payloadLength = status > 0 ? length : 0;
        if (payloadLength < 0 || (payloadLength > 0
                && (payload == null || payloadLength > payload.length))) {
            throw new IllegalArgumentException("Invalid request body frame payload");
        }
        ByteBuffer frame = ByteBuffer
                .allocate(REQUEST_BODY_HEADER_BYTES + payloadLength)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(TYPE_REQUEST_BODY_CHUNK);
        frame.putLong(bodyId);
        frame.putLong(readId);
        frame.put((byte) status);
        if (payloadLength > 0) {
            frame.put(payload, 0, payloadLength);
        }
        return frame.array();
    }

    static ResponseFrame decodeResponseFrame(byte[] frameBytes) {
        if (frameBytes == null || frameBytes.length < RESPONSE_HEADER_BYTES) {
            return null;
        }
        ByteBuffer frame = ByteBuffer.wrap(frameBytes).order(ByteOrder.BIG_ENDIAN);
        byte type = frame.get();
        if (type != TYPE_RESPONSE_CHUNK
                && type != TYPE_RESPONSE_COMPLETE
                && type != TYPE_RESPONSE_ERROR
                && type != TYPE_RESPONSE_HEADERS) {
            return null;
        }
        long requestId = frame.getLong();
        if (requestId <= 0L) {
            return null;
        }
        byte[] payload = Arrays.copyOfRange(
                frameBytes,
                RESPONSE_HEADER_BYTES,
                frameBytes.length
        );
        return new ResponseFrame(
                type,
                requestId,
                payload,
                type == TYPE_RESPONSE_ERROR
                        ? new String(payload, StandardCharsets.UTF_8)
                        : ""
        );
    }

    record ResponseFrame(byte type, long requestId, byte[] data, String error) {}
}
