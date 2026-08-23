package io.github.qwqgong.androidcyaml

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

object TombstoneStore {
    private const val TAG = "AndroidCyaml/Tombstone"
    private const val DIRECTORY_NAME = "tombstones"
    private const val LOCK_FILE_NAME = ".capture.lock"
    private const val HISTORY_LIMIT = 32
    private const val MAX_TOMBSTONES = 8
    private const val MAX_MEMORY_LIMIT_RECORDS = 16
    private const val MAX_TOMBSTONE_BYTES = 4L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 16L * 1024L * 1024L

    fun captureAsync(context: Context) {
        val applicationContext = context.applicationContext
        val worker = Thread({ capture(applicationContext) }, "AndroidCyaml-tombstones")
        worker.isDaemon = true
        worker.start()
    }

    private fun capture(context: Context) {
        val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.w(TAG, "Unable to create private tombstone cache")
            return
        }

        val lockFile = File(directory, LOCK_FILE_NAME)
        try {
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    captureLocked(context, directory)
                    pruneLocked(directory)
                }
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to cache historical native tombstones", exception)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to cache historical native tombstones", exception)
        }
    }

    private fun captureLocked(context: Context, directory: File) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, HISTORY_LIMIT)
        var capturedTombstones = 0
        var capturedMemoryLimitExits = 0
        for (exit in exits) {
            try {
                if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    if (captureOne(directory, exit)) {
                        capturedTombstones++
                    }
                } else if (isAndroid17MemoryLimiterExit(exit) &&
                    captureMemoryLimitExit(directory, exit)
                ) {
                    capturedMemoryLimitExits++
                }
            } catch (exception: IOException) {
                Log.w(TAG, "Unable to cache exit diagnostics for " + exit.processName, exception)
            }
        }
        if (capturedTombstones > 0 || capturedMemoryLimitExits > 0) {
            Log.i(
                TAG,
                "Cached $capturedTombstones native tombstone(s) and " +
                    "$capturedMemoryLimitExits Android 17 memory-limit exit(s)",
            )
        }
    }

    private fun captureOne(directory: File, exit: ApplicationExitInfo): Boolean {
        val baseName = baseName(exit)
        val destination = File(directory, "$baseName.tombstone")
        if (destination.isFile) {
            return false
        }

        (exit.traceInputStream ?: return false).use { trace ->
            val temporary = File.createTempFile(".capture-", ".tmp", directory)
            var copied = 0L
            var truncated = false
            try {
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = trace.read(buffer)
                        if (read == -1) {
                            break
                        }
                        val remaining = MAX_TOMBSTONE_BYTES - copied
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }
                        val writable = minOf(read.toLong(), remaining).toInt()
                        output.write(buffer, 0, writable)
                        copied += writable
                        if (writable != read) {
                            truncated = true
                            break
                        }
                    }
                    output.fd.sync()
                }
                if (copied == 0L) {
                    return false
                }
                moveAtomically(temporary, destination)
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    Log.w(TAG, "Unable to delete incomplete tombstone capture")
                }
            }

            writeMetadata(
                File(directory, "$baseName.json"),
                exit,
                "native_crash",
                copied,
                truncated,
            )
            return true
        }
    }

    private fun captureMemoryLimitExit(directory: File, exit: ApplicationExitInfo): Boolean {
        val destination = File(directory, baseName(exit) + ".memory-limit.json")
        if (destination.isFile) {
            return false
        }
        writeMetadata(destination, exit, "android17_memory_limiter", 0, false)
        return true
    }

    private fun writeMetadata(
        destination: File,
        exit: ApplicationExitInfo,
        eventType: String,
        bytes: Long,
        truncated: Boolean,
    ) {
        val encoded = try {
            JSONObject()
                .put("event_type", eventType)
                .put("process", exit.processName)
                .put("timestamp", exit.timestamp)
                .put("pid", exit.pid)
                .put("reason", exit.reason)
                .put("status", exit.status)
                .put("importance", exit.importance)
                .put("pss_kb", exit.pss)
                .put("rss_kb", exit.rss)
                .put("description", exit.description)
                .put("bytes", bytes)
                .put("truncated", truncated)
                .put("captured_at", System.currentTimeMillis())
                .toString(2) + '\n'
        } catch (exception: JSONException) {
            throw IOException("Unable to encode tombstone metadata", exception)
        }

        val directory = destination.parentFile
        val temporary = File.createTempFile(".metadata-", ".tmp", directory)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(encoded.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            moveAtomically(temporary, destination)
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Unable to delete incomplete tombstone metadata")
            }
        }
    }

    private fun pruneLocked(directory: File) {
        val tombstones = directory.listFiles { _, name -> name.endsWith(".tombstone") } ?: return
        tombstones.sortByDescending { it.lastModified() }
        var retainedBytes = 0L
        for ((index, tombstone) in tombstones.withIndex()) {
            val nextBytes = retainedBytes + tombstone.length()
            if (index < MAX_TOMBSTONES && nextBytes <= MAX_CACHE_BYTES) {
                retainedBytes = nextBytes
                continue
            }
            deleteQuietly(tombstone)
            val baseName = tombstone.name.removeSuffix(".tombstone")
            deleteQuietly(File(directory, "$baseName.json"))
        }

        val memoryLimitRecords = directory.listFiles { _, name ->
            name.endsWith(".memory-limit.json")
        } ?: return
        memoryLimitRecords.sortByDescending { it.lastModified() }
        for (index in MAX_MEMORY_LIMIT_RECORDS until memoryLimitRecords.size) {
            deleteQuietly(memoryLimitRecords[index])
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeProcessName(processName: String?): String {
        if (processName.isNullOrBlank()) {
            return "unknown"
        }
        val safe = processName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.length > 80) safe.substring(safe.length - 80) else safe
    }

    private fun baseName(exit: ApplicationExitInfo): String = String.format(
        Locale.ROOT,
        "%d-pid%d-%s",
        exit.timestamp,
        exit.pid,
        safeProcessName(exit.processName),
    )

    private fun isAndroid17MemoryLimiterExit(exit: ApplicationExitInfo): Boolean {
        val description = exit.description
        return exit.reason == ApplicationExitInfo.REASON_OTHER &&
            description != null &&
            description.contains("MemoryLimiter:AnonSwap")
    }

    private fun deleteQuietly(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to prune " + file.name)
        }
    }
}
