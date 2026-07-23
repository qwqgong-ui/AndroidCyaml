package io.github.qwqgong.androidcyaml;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class StaleCoreReaper {
    private static final String TAG = "AndroidCyaml/Runtime";

    private StaleCoreReaper() {}

    static void stopMatching(File binary) {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return;
        }
        String expected = binary.getAbsolutePath();
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid <= 0 || pid == android.os.Process.myPid()) {
                continue;
            }
            try {
                if (!expected.equals(readCommand(pid))) {
                    continue;
                }
                Log.w(TAG, "Stopping stale mihomo process " + pid);
                Os.kill(pid, OsConstants.SIGTERM);
                Thread.sleep(50);
                if (new File("/proc/" + pid).exists()) {
                    Os.kill(pid, OsConstants.SIGKILL);
                }
            } catch (IOException | ErrnoException exception) {
                Log.d(TAG, "Unable to inspect or stop process " + pid, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String readCommand(int pid) throws IOException {
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            while (output.size() < 4096) {
                int value = input.read();
                if (value <= 0) {
                    break;
                }
                output.write(value);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
