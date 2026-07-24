package io.github.qwqgong.androidcyaml;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class TombstoneStore {
    private static final String TAG = "AndroidCyaml/Tombstone";
    private static final String DIRECTORY_NAME = "tombstones";
    private static final String LOCK_FILE_NAME = ".capture.lock";
    private static final int HISTORY_LIMIT = 32;
    private static final int MAX_TOMBSTONES = 8;
    private static final int MAX_MEMORY_LIMIT_RECORDS = 16;
    private static final long MAX_TOMBSTONE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = 16L * 1024L * 1024L;
    private TombstoneStore() {}

    static void captureAsync(Context context) {
        Context applicationContext = context.getApplicationContext();
        Thread worker = new Thread(
                () -> capture(applicationContext),
                "AndroidCyaml-tombstones"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void capture(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "Unable to create private tombstone cache");
            return;
        }

        File lockFile = new File(directory, LOCK_FILE_NAME);
        try (FileChannel channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            captureLocked(context, directory);
            pruneLocked(directory);
        } catch (IOException | RuntimeException exception) {
            Log.w(TAG, "Unable to cache historical native tombstones", exception);
        }
    }

    private static void captureLocked(Context context, File directory) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(
                null,
                0,
                HISTORY_LIMIT
        );
        int capturedTombstones = 0;
        int capturedMemoryLimitExits = 0;
        for (ApplicationExitInfo exit : exits) {
            try {
                if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    if (captureOne(directory, exit)) {
                        capturedTombstones++;
                    }
                } else if (isAndroid17MemoryLimiterExit(exit)
                        && captureMemoryLimitExit(directory, exit)) {
                    capturedMemoryLimitExits++;
                }
            } catch (IOException exception) {
                Log.w(
                        TAG,
                        "Unable to cache exit diagnostics for " + exit.getProcessName(),
                        exception
                );
            }
        }
        if (capturedTombstones > 0 || capturedMemoryLimitExits > 0) {
            Log.i(
                    TAG,
                    "Cached " + capturedTombstones + " native tombstone(s) and "
                            + capturedMemoryLimitExits + " Android 17 memory-limit exit(s)"
            );
        }
    }

    private static boolean captureOne(File directory, ApplicationExitInfo exit)
            throws IOException {
        String baseName = baseName(exit);
        File destination = new File(directory, baseName + ".tombstone");
        if (destination.isFile()) {
            return false;
        }

        try (InputStream trace = exit.getTraceInputStream()) {
            if (trace == null) {
                return false;
            }

            File temporary = File.createTempFile(".capture-", ".tmp", directory);
            long copied = 0;
            boolean truncated = false;
            try {
                try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = trace.read(buffer)) != -1) {
                        long remaining = MAX_TOMBSTONE_BYTES - copied;
                        if (remaining <= 0) {
                            truncated = true;
                            break;
                        }
                        int writable = (int) Math.min(read, remaining);
                        output.write(buffer, 0, writable);
                        copied += writable;
                        if (writable != read) {
                            truncated = true;
                            break;
                        }
                    }
                    output.getFD().sync();
                }
                if (copied == 0) {
                    return false;
                }
                moveAtomically(temporary, destination);
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    Log.w(TAG, "Unable to delete incomplete tombstone capture");
                }
            }

            writeMetadata(
                    new File(directory, baseName + ".json"),
                    exit,
                    "native_crash",
                    copied,
                    truncated
            );
            return true;
        }
    }

    private static boolean captureMemoryLimitExit(File directory, ApplicationExitInfo exit)
            throws IOException {
        File destination = new File(directory, baseName(exit) + ".memory-limit.json");
        if (destination.isFile()) {
            return false;
        }
        writeMetadata(destination, exit, "android17_memory_limiter", 0, false);
        return true;
    }

    private static void writeMetadata(
            File destination,
            ApplicationExitInfo exit,
            String eventType,
            long bytes,
            boolean truncated
    ) throws IOException {
        JSONObject metadata = new JSONObject();
        String encoded;
        try {
            metadata.put("event_type", eventType);
            metadata.put("process", exit.getProcessName());
            metadata.put("timestamp", exit.getTimestamp());
            metadata.put("pid", exit.getPid());
            metadata.put("reason", exit.getReason());
            metadata.put("status", exit.getStatus());
            metadata.put("importance", exit.getImportance());
            metadata.put("pss_kb", exit.getPss());
            metadata.put("rss_kb", exit.getRss());
            metadata.put("description", exit.getDescription());
            metadata.put("bytes", bytes);
            metadata.put("truncated", truncated);
            metadata.put("captured_at", System.currentTimeMillis());
            encoded = metadata.toString(2) + '\n';
        } catch (JSONException exception) {
            throw new IOException("Unable to encode tombstone metadata", exception);
        }

        File directory = destination.getParentFile();
        File temporary = File.createTempFile(".metadata-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            moveAtomically(temporary, destination);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Unable to delete incomplete tombstone metadata");
            }
        }
    }

    private static void pruneLocked(File directory) {
        File[] tombstones = directory.listFiles(
                (ignored, name) -> name.endsWith(".tombstone")
        );
        if (tombstones == null) {
            return;
        }
        Arrays.sort(tombstones, Comparator.comparingLong(File::lastModified).reversed());
        long retainedBytes = 0;
        for (int index = 0; index < tombstones.length; index++) {
            File tombstone = tombstones[index];
            long nextBytes = retainedBytes + tombstone.length();
            if (index < MAX_TOMBSTONES && nextBytes <= MAX_CACHE_BYTES) {
                retainedBytes = nextBytes;
                continue;
            }
            deleteQuietly(tombstone);
            String name = tombstone.getName();
            String baseName = name.substring(0, name.length() - ".tombstone".length());
            deleteQuietly(new File(directory, baseName + ".json"));
        }

        File[] memoryLimitRecords = directory.listFiles(
                (ignored, name) -> name.endsWith(".memory-limit.json")
        );
        if (memoryLimitRecords == null) {
            return;
        }
        Arrays.sort(
                memoryLimitRecords,
                Comparator.comparingLong(File::lastModified).reversed()
        );
        for (int index = MAX_MEMORY_LIMIT_RECORDS; index < memoryLimitRecords.length; index++) {
            deleteQuietly(memoryLimitRecords[index]);
        }
    }

    private static void moveAtomically(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String safeProcessName(String processName) {
        if (processName == null || processName.isBlank()) {
            return "unknown";
        }
        String safe = processName.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(safe.length() - 80) : safe;
    }

    private static String baseName(ApplicationExitInfo exit) {
        return String.format(
                Locale.ROOT,
                "%d-pid%d-%s",
                exit.getTimestamp(),
                exit.getPid(),
                safeProcessName(exit.getProcessName())
        );
    }

    private static boolean isAndroid17MemoryLimiterExit(ApplicationExitInfo exit) {
        String description = exit.getDescription();
        return exit.getReason() == ApplicationExitInfo.REASON_OTHER
                && description != null
                && description.contains("MemoryLimiter:AnonSwap");
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to prune " + file.getName());
        }
    }
}
