package io.github.qwqgong.androidcyaml;

import android.net.LocalSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class AndroidPlatformProtocol {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private AndroidPlatformProtocol() {}

    static Request readRequest(InputStream input) throws IOException, JSONException {
        String line = readBoundedLine(input, MAX_REQUEST_BYTES);
        if (line == null || line.isBlank()) {
            throw new IOException("empty Android platform request");
        }
        JSONObject payload = new JSONObject(line);
        String operation = payload.optString("operation", "");
        if (operation.isBlank()) {
            throw new IOException("Android platform request omitted operation");
        }
        return new Request(operation, payload);
    }

    static void writeReply(LocalSocket connection, Reply reply) throws IOException {
        OutputStream output = connection.getOutputStream();
        FileDescriptor descriptor = reply.descriptor();
        if (descriptor != null) {
            connection.setFileDescriptorsForSend(new FileDescriptor[]{descriptor});
        }
        output.write(reply.success() ? 1 : 0);
        output.flush();
        if (descriptor != null) {
            connection.setFileDescriptorsForSend(null);
        }
        output.write((reply.payload().toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static TunOptions readTunOptions(Request request) throws IOException, JSONException {
        JSONObject object = request.payload().optJSONObject("tun");
        if (object == null) {
            throw new IOException("open_tun request omitted TUN options");
        }
        int mtu = object.optInt("mtu", 9000);
        if (mtu < 1280 || mtu > 65_535) {
            throw new IOException("invalid TUN MTU: " + mtu);
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
    }

    static Reply success(FileDescriptor descriptor) throws JSONException {
        return new Reply(true, new JSONObject().put("ok", true), descriptor);
    }

    static Reply processOwner(int uid, String packageName) throws JSONException {
        JSONObject payload = new JSONObject()
                .put("ok", true)
                .put("uid", uid)
                .put("packageName", packageName);
        return new Reply(true, payload, null);
    }

    static Reply error(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", false)
                    .put("error", message == null || message.isBlank() ? "unknown error" : message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new Reply(false, payload, null);
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

    private static String readBoundedLine(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() <= maximumBytes) {
            int value = input.read();
            if (value == -1) {
                return output.size() == 0 ? null : output.toString(StandardCharsets.UTF_8);
            }
            if (value == '\n') {
                return output.toString(StandardCharsets.UTF_8);
            }
            if (value != '\r') {
                output.write(value);
            }
        }
        throw new IOException("Android platform request exceeds 64 KiB");
    }

    record Request(String operation, JSONObject payload) {}

    record Reply(boolean success, JSONObject payload, FileDescriptor descriptor) {}

    record TunOptions(
            int mtu,
            List<String> inet4Address,
            List<String> inet6Address,
            boolean autoRoute,
            List<String> inet4RouteAddress,
            List<String> inet6RouteAddress,
            List<String> inet4RouteExcludeAddress,
            List<String> inet6RouteExcludeAddress,
            List<String> dnsServerAddress,
            List<String> includePackage,
            List<String> excludePackage
    ) {
        String summary() {
            return "mtu=" + mtu
                    + " ipv4=" + inet4Address
                    + " ipv6=" + inet6Address
                    + " autoRoute=" + autoRoute;
        }
    }
}
