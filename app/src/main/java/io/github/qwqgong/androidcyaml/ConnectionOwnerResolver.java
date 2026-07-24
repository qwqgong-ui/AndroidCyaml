package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

final class ConnectionOwnerResolver {
    private final Context context;
    private final ConnectivityManager connectivityManager;
    private final ConcurrentHashMap<Integer, String> packageNames = new ConcurrentHashMap<>();

    ConnectionOwnerResolver(Context context) {
        this.context = context.getApplicationContext();
        connectivityManager = this.context.getSystemService(ConnectivityManager.class);
    }

    String resolveEncoded(
            int protocol,
            String sourceAddress,
            int sourcePort,
            String destinationAddress,
            int destinationPort
    ) throws IOException {
        if (connectivityManager == null) {
            throw new IOException("ConnectivityManager 不可用");
        }
        InetSocketAddress source = endpoint(sourceAddress, sourcePort);
        InetSocketAddress destination = endpoint(destinationAddress, destinationPort);
        int uid = connectivityManager.getConnectionOwnerUid(protocol, source, destination);
        if (uid == android.os.Process.INVALID_UID) {
            throw new IOException("未找到连接所属进程");
        }
        return uid + "\n" + packageNames.computeIfAbsent(uid, this::packageNameForUid);
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
            throw new IOException("无效的连接端口");
        }
        return new InetSocketAddress(NetworkAddressParser.parseAddress(address), port);
    }
}
