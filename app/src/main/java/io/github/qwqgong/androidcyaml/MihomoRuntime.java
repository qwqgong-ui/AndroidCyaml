package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class MihomoRuntime implements AutoCloseable {
    private static final String TAG = "AndroidCyaml/Runtime";

    private final Context context;
    private final MihomoFileStore fileStore;
    private final AndroidTunManager tunManager;
    private final NativePlatformCallbacks platformCallbacks;
    private final RuntimeOverrideSettings settings;
    private final boolean ipv6Enabled;
    private final String controllerSecret;

    private MihomoController controller;
    private boolean started;

    MihomoRuntime(
            Context context,
            MihomoFileStore fileStore,
            AndroidTunManager tunManager,
            NativePlatformCallbacks platformCallbacks,
            RuntimeOverrideSettings settings,
            boolean ipv6Enabled
    ) {
        this.context = context.getApplicationContext();
        this.fileStore = fileStore;
        this.tunManager = tunManager;
        this.platformCallbacks = platformCallbacks;
        this.settings = settings == null ? RuntimeOverrideSettings.defaults() : settings;
        this.ipv6Enabled = ipv6Enabled;
        controllerSecret = ControllerSecretStore.getOrCreate(this.context);
    }

    String start() throws IOException, InterruptedException {
        MihomoPaths paths = fileStore.ensureReady();
        controller = MihomoController.reserve(controllerSecret);
        TunOptions tunOptions = MihomoNative.prepareTun(paths, settings, ipv6Enabled);
        ParcelFileDescriptor tunnel = tunManager.open(tunOptions);
        ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(tunnel.getFileDescriptor());
        int nativeFd = duplicate.detachFd();
        boolean nativeAcceptedDescriptor = false;
        try {
            MihomoNative.start(
                    paths,
                    controller,
                    controllerSecret,
                    settings,
                    ipv6Enabled,
                    nativeFd,
                    platformCallbacks
            );
            nativeAcceptedDescriptor = true;
            controller.awaitReady(90, TimeUnit.SECONDS);
            controller.awaitTun(10, TimeUnit.SECONDS);
            started = true;
            return "mihomo " + shortCommit()
                    + " · JNI/CGO"
                    + " · " + stackDetail(settings.tunStack())
                    + (settings.processMatching() ? " · 进程匹配" : " · 不匹配进程")
                    + (ipv6Enabled ? " · IPv6" : " · IPv4-only")
                    + " · zashboard " + BuildConfig.ZASHBOARD_VERSION;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                MihomoNative.stop();
            } catch (IOException stopFailure) {
                exception.addSuppressed(stopFailure);
            }
            if (!nativeAcceptedDescriptor) {
                closeDetachedDescriptor(nativeFd);
            }
            controller = null;
            throw exception;
        }
    }

    int controllerPort() {
        return controller == null ? 0 : controller.port();
    }

    String dashboardUrl() {
        return controller == null ? "" : controller.dashboardUrl();
    }

    int trimRebuildableCaches() {
        return MihomoNative.trimMemory();
    }

    void onUnderlyingNetworkChanged() throws IOException {
        if (started && MihomoNative.isRunning()) {
            MihomoNative.notifyNetworkChanged();
        }
    }

    @Override
    public void close() {
        if (!started && !MihomoNative.isRunning()) {
            controller = null;
            return;
        }
        started = false;
        try {
            MihomoNative.stop();
        } catch (IOException exception) {
            Log.w(TAG, "Unable to stop embedded mihomo cleanly", exception);
        }
        controller = null;
    }

    private static void closeDetachedDescriptor(int fileDescriptor) {
        try (ParcelFileDescriptor adopted = ParcelFileDescriptor.adoptFd(fileDescriptor)) {
            // Closing the adopted wrapper releases the duplicated descriptor.
        } catch (IOException | RuntimeException ignored) {
            // Startup is already failing; descriptor cleanup is best effort.
        }
    }

    private static String stackDetail(TunStackMode stack) {
        return switch (stack) {
            case SYSTEM -> "system 全栈";
            case GVISOR -> "gVisor 全栈";
            case MIXED -> "mixed（TCP system / UDP gVisor）";
        };
    }

    private static String shortCommit() {
        String commit = BuildConfig.MIHOMO_COMMIT;
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
