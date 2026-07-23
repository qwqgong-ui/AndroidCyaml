package io.github.qwqgong.androidcyaml;

import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.IOException;

final class AndroidPlatformBridge implements Closeable {
    private final AndroidTunManager tunManager;
    private final ConnectionOwnerResolver ownerResolver;
    private final AndroidPlatformServer server;

    AndroidPlatformBridge(VpnPlatformHost host) throws IOException {
        tunManager = new AndroidTunManager(host);
        ownerResolver = new ConnectionOwnerResolver(host.platformContext());
        server = new AndroidPlatformServer(this::dispatch);
    }

    String coreSocketAddress() {
        return server.coreSocketAddress();
    }

    boolean hasUsableTunnel() {
        return tunManager.hasUsableTunnel();
    }

    private AndroidPlatformProtocol.Reply dispatch(AndroidPlatformProtocol.Request request)
            throws Exception {
        return switch (request.operation()) {
            case "open_tun" -> openTun(request);
            case "find_process" -> ownerResolver.resolve(request);
            default -> throw new IOException(
                    "unsupported Android platform operation: " + request.operation()
            );
        };
    }

    private AndroidPlatformProtocol.Reply openTun(AndroidPlatformProtocol.Request request)
            throws Exception {
        AndroidPlatformProtocol.TunOptions options = AndroidPlatformProtocol.readTunOptions(request);
        ParcelFileDescriptor descriptor = tunManager.open(options);
        return AndroidPlatformProtocol.success(descriptor.getFileDescriptor());
    }

    @Override
    public void close() {
        server.close();
        tunManager.close();
    }
}
