package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class MihomoProcess implements AutoCloseable {
    interface ExitListener {
        void onUnexpectedExit(MihomoProcess source, int exitCode, String diagnostics);
    }

    private static final String TAG = "AndroidCyaml/Runtime";
    private static final int MAX_LOG_LINES = 120;

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final Object processLock = new Object();
    private final Object logLock = new Object();
    private final ArrayDeque<String> recentLogs = new ArrayDeque<>();
    private final ExitListener exitListener;

    private Process process;
    private volatile boolean ready;
    private volatile boolean intentionalStop;

    private MihomoProcess(ExitListener exitListener) {
        this.exitListener = exitListener;
    }

    static MihomoProcess start(
            Context context,
            MihomoPaths paths,
            String platformSocket,
            MihomoController controller,
            String secret,
            boolean processMatching,
            boolean ipv6Enabled,
            ExitListener exitListener
    ) throws IOException {
        StaleCoreReaper.stopMatching(paths.binary());
        List<String> command = command(
                paths,
                platformSocket,
                controller,
                secret,
                processMatching,
                ipv6Enabled
        );
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(paths.home())
                .redirectErrorStream(true);
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

        MihomoProcess runtimeProcess = new MihomoProcess(exitListener);
        Process candidate = builder.start();
        synchronized (runtimeProcess.processLock) {
            runtimeProcess.process = candidate;
        }
        runtimeProcess.readLogs(candidate);
        runtimeProcess.watchExit(candidate);
        return runtimeProcess;
    }

    Process rawProcess() throws IOException {
        synchronized (processLock) {
            if (process == null) {
                throw new IOException("mihomo 进程未启动");
            }
            return process;
        }
    }

    void markReady() {
        ready = true;
    }

    int trimLogCache() {
        synchronized (logLock) {
            int removed = recentLogs.size();
            recentLogs.clear();
            return removed;
        }
    }

    String diagnostics() {
        synchronized (logLock) {
            if (recentLogs.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : recentLogs) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(line);
                if (builder.length() > 2_000) {
                    break;
                }
            }
            return builder.toString();
        }
    }

    @Override
    public void close() {
        intentionalStop = true;
        ready = false;
        Process current;
        synchronized (processLock) {
            current = process;
            process = null;
        }
        if (current != null) {
            stopProcess(current);
        }
        ioExecutor.shutdownNow();
    }

    private void readLogs(Process source) {
        ioExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                    synchronized (logLock) {
                        recentLogs.addLast(line);
                        while (recentLogs.size() > MAX_LOG_LINES) {
                            recentLogs.removeFirst();
                        }
                    }
                }
            } catch (IOException exception) {
                if (source.isAlive()) {
                    Log.w(TAG, "mihomo log reader stopped", exception);
                }
            }
        });
    }

    private void watchExit(Process source) {
        ioExecutor.execute(() -> {
            try {
                int exitCode = source.waitFor();
                boolean notify;
                synchronized (processLock) {
                    if (process != source) {
                        return;
                    }
                    process = null;
                    notify = ready && !intentionalStop;
                    ready = false;
                }
                if (notify && exitListener != null) {
                    exitListener.onUnexpectedExit(this, exitCode, diagnostics());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static List<String> command(
            MihomoPaths paths,
            String platformSocket,
            MihomoController controller,
            String secret,
            boolean processMatching,
            boolean ipv6Enabled
    ) {
        ArrayList<String> command = new ArrayList<>();
        command.add(paths.binary().getAbsolutePath());
        command.add("-d");
        command.add(paths.home().getAbsolutePath());
        command.add("-f");
        command.add(paths.config().getAbsolutePath());
        command.add("-ext-ui");
        command.add(paths.ui().getAbsolutePath());
        command.add("-ext-ctl");
        command.add("127.0.0.1:" + controller.port());
        command.add("-secret");
        command.add(secret);
        command.add("-android-platform-socket");
        command.add(platformSocket);
        command.add("-android-tun-stack-override");
        command.add("gvisor");
        command.add("-android-process-matching=" + processMatching);
        command.add("-android-ipv6=" + ipv6Enabled);
        return command;
    }

    private static void stopProcess(Process target) {
        target.destroy();
        try {
            if (!target.waitFor(2, TimeUnit.SECONDS)) {
                target.destroyForcibly();
                target.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }
}
