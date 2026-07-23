package io.github.qwqgong.androidcyaml;

import android.content.Context;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class MihomoRuntime implements AutoCloseable {
    interface ExitListener {
        void onUnexpectedExit(MihomoRuntime runtime, int exitCode, String diagnostics);
    }

    private final Context context;
    private final String platformSocket;
    private final ExitListener exitListener;
    private final MihomoFileStore fileStore;
    private final boolean processMatching;
    private final boolean ipv6Enabled;
    private final String controllerSecret;

    private MihomoProcess process;
    private MihomoController controller;

    MihomoRuntime(
            Context context,
            String platformSocket,
            MihomoFileStore fileStore,
            boolean processMatching,
            boolean ipv6Enabled,
            ExitListener exitListener
    ) {
        this.context = context.getApplicationContext();
        this.platformSocket = platformSocket;
        this.fileStore = fileStore;
        this.processMatching = processMatching;
        this.ipv6Enabled = ipv6Enabled;
        this.exitListener = exitListener;
        controllerSecret = ControllerSecretStore.getOrCreate(this.context);
    }

    String start() throws IOException, InterruptedException {
        MihomoPaths paths = fileStore.ensureReady();
        controller = MihomoController.reserve(controllerSecret);
        process = MihomoProcess.start(
                context,
                paths,
                platformSocket,
                controller,
                controllerSecret,
                processMatching,
                ipv6Enabled,
                this::handleUnexpectedExit
        );
        try {
            Process rawProcess = process.rawProcess();
            controller.awaitReady(rawProcess, 90, TimeUnit.SECONDS);
            controller.awaitTun(rawProcess, 10, TimeUnit.SECONDS);
            process.markReady();
            return "mihomo " + shortCommit()
                    + " · gVisor"
                    + (processMatching ? " · 进程匹配" : " · 不匹配进程")
                    + (ipv6Enabled ? " · IPv6" : " · IPv4-only")
                    + " · zashboard " + BuildConfig.ZASHBOARD_VERSION;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String diagnostics = process.diagnostics();
            close();
            if (!diagnostics.isBlank()) {
                throw new IOException(usefulMessage(exception) + "：" + diagnostics, exception);
            }
            throw exception;
        }
    }

    int controllerPort() {
        return controller == null ? 0 : controller.port();
    }

    String dashboardUrl() {
        return controller == null ? "" : controller.dashboardUrl();
    }

    int trimLogCache() {
        return process == null ? 0 : process.trimLogCache();
    }

    @Override
    public void close() {
        if (process != null) {
            process.close();
            process = null;
        }
        controller = null;
    }

    private void handleUnexpectedExit(MihomoProcess source, int exitCode, String diagnostics) {
        if (process == source && exitListener != null) {
            exitListener.onUnexpectedExit(this, exitCode, diagnostics);
        }
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static String shortCommit() {
        String commit = BuildConfig.MIHOMO_COMMIT;
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
