package io.github.qwqgong.androidcyaml;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.util.Objects;

final class NativePlatformCallbacks {
    private static final String TAG = "AndroidCyaml/JNI";

    private final VpnService vpnService;
    private final ConnectionOwnerResolver ownerResolver;

    NativePlatformCallbacks(VpnService vpnService) {
        this.vpnService = Objects.requireNonNull(vpnService);
        ownerResolver = new ConnectionOwnerResolver(vpnService);
    }

    @SuppressWarnings("unused")
    public boolean protectSocket(int fileDescriptor) {
        if (fileDescriptor < 0) {
            return false;
        }
        try {
            return vpnService.protect(fileDescriptor);
        } catch (RuntimeException exception) {
            Log.w(TAG, "VpnService.protect failed for fd=" + fileDescriptor, exception);
            return false;
        }
    }

    @SuppressWarnings("unused")
    public String resolveProcessOwner(
            int protocol,
            String sourceAddress,
            int sourcePort,
            String destinationAddress,
            int destinationPort
    ) {
        try {
            return ownerResolver.resolveEncoded(
                    protocol,
                    sourceAddress,
                    sourcePort,
                    destinationAddress,
                    destinationPort
            );
        } catch (IOException | RuntimeException exception) {
            Log.d(TAG, "Unable to resolve connection owner", exception);
            return "";
        }
    }
}
