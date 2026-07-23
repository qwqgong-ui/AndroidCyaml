package io.github.qwqgong.androidcyaml;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class MihomoConfigValidator {
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    private MihomoConfigValidator() {}

    static void validate(MihomoPaths paths, File candidate)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                paths.binary().getAbsolutePath(),
                "-d", paths.home().getAbsolutePath(),
                "-f", candidate.getAbsolutePath(),
                "-t"
        ).directory(paths.home()).redirectErrorStream(true).start();

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        Future<String> output = readerExecutor.submit(
                () -> readOutput(process.getInputStream(), MAX_OUTPUT_BYTES)
        );
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("mihomo 配置校验超时");
            }
            String diagnostics;
            try {
                diagnostics = output.get(5, TimeUnit.SECONDS).trim();
            } catch (ExecutionException | TimeoutException exception) {
                throw new IOException("无法读取 mihomo 配置校验结果", exception);
            }
            if (process.exitValue() != 0) {
                throw new IOException(diagnostics.isEmpty()
                        ? "mihomo 拒绝了 config.yaml"
                        : diagnostics);
            }
        } finally {
            output.cancel(true);
            readerExecutor.shutdownNow();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readOutput(InputStream input, int maximumBytes) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximumBytes) {
                    int remaining = maximumBytes - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, remaining);
                    }
                    break;
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
