package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;

final class ConnectionOwnerResolver {
    private final Context context;

    ConnectionOwnerResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    AndroidPlatformProtocol.Reply resolve(AndroidPlatformProtocol.Request request)
            throws IOException, JSONException {
        JSONObject payload = request.payload();
        String network = payload.optString("network", "");
        int protocol;
        if (network.startsWith("tcp")) {
            protocol = OsConstants.IPPROTO_TCP;
        } else if (network.startsWith("udp")) {
            protocol = OsConstants.IPPROTO_UDP;
        } else {
            throw new IOException("unsupported process lookup network: " + network);
        }

        InetSocketAddress source = endpoint(
                payload.getString("sourceAddress"),
                payload.getInt("sourcePort")
        );
        InetSocketAddress destination = endpoint(
                payload.getString("destinationAddress"),
                payload.getInt("destinationPort")
        );
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            throw new IOException("ConnectivityManager is unavailable");
        }
        int uid = manager.getConnectionOwnerUid(protocol, source, destination);
        if (uid == android.os.Process.INVALID_UID) {
            throw new IOException("connection owner was not found");
        }
        return AndroidPlatformProtocol.processOwner(uid, packageNameForUid(uid));
    }

    private String packageNameForUid(int uid) {
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            Arrays.sort(packages);
            return packages[0];
        }
        String name = packageManager.getNameForUid(uid);
        return name == null || name.isBlank() ? "uid:" + uid : name;
    }

    private static InetSocketAddress endpoint(String address, int port) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IOException("invalid endpoint port");
        }
        return new InetSocketAddress(NetworkAddressParser.parseAddress(address), port);
    }
}
