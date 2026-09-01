package io.github.qwqgong.androidcyaml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Append-only diagnostics log kept in the app's private no-backup directory.
 *
 * Two 2 MiB generations hold roughly a fortnight of one-per-minute samples,
 * which is the window a slow memory climb actually shows up in. Every line
 * carries three clocks: the wall clock for correlating with anything else, the
 * boot clock, and the awake clock. `boot - awake` is time the device spent
 * suspended, so a gap in the series is explainable instead of suspicious --
 * the sampler stops while the device sleeps, by design.
 *
 * Lines are `key=value` and hold aggregate counters only. This file is meant to
 * be exported and shared, so nothing that identifies a destination, a network,
 * or a peer belongs in it.
 *
 * Only the log process writes the file. Two 2 MiB generations rotating under two
 * writers is not a file that survives: appends interleave mid-line, and both
 * processes can rotate at once and drop a generation the other was still using.
 * Callers in the proxy and UI processes hand their lines to [DiagnosticsRelay]
 * instead, and the log process collects them. `append` is the same call
 * everywhere; where the line lands is decided here, once.
 */
object DiagnosticsLog {
    private const val TAG = "AndroidCyaml/Diag"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val CURRENT_NAME = "sample.log"
    private const val ROTATED_NAME = "sample.1.log"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    @Volatile
    private var enabled = false

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Records one event. Costs a single volatile read when log mode is off, so
     * callers on hot-ish paths do not need their own guard.
     */
    fun append(context: Context, event: String, detail: String) {
        if (!enabled) {
            return
        }
        val line = StringBuilder(detail.length + 96)
            .append("wall=").append(System.currentTimeMillis())
            .append(" boot=").append(SystemClock.elapsedRealtime())
            .append(" awake=").append(SystemClock.uptimeMillis())
            .append(" event=").append(event)
        if (detail.isNotEmpty()) {
            line.append(' ').append(detail)
        }
        record(context, line.toString())
    }

    /**
     * Writes a line that was produced elsewhere and already carries its own
     * clocks. Used by the log process for everything it collected over binder,
     * whose timestamps belong to the moment the event happened rather than the
     * moment it was drained.
     */
    fun appendVerbatim(context: Context, line: String) {
        if (!enabled || line.isEmpty()) {
            return
        }
        record(context, line)
    }

    private fun record(context: Context, line: String) {
        if (ProcessRole.isLogProcess(context)) {
            write(context, line)
        } else {
            Log.i(TAG, line)
            DiagnosticsRelay.record(line)
        }
    }

    /** Copies the retained generations, oldest first, into [destination]. */
    @Synchronized
    fun exportTo(context: Context, destination: OutputStream): Long {
        var copied = 0L
        for (name in arrayOf(ROTATED_NAME, CURRENT_NAME)) {
            val source = File(directory(context), name)
            if (!source.isFile) {
                continue
            }
            source.inputStream().use { input -> copied += input.copyTo(destination) }
        }
        destination.flush()
        return copied
    }

    @Synchronized
    fun retainedBytes(context: Context): Long {
        var total = 0L
        for (name in arrayOf(ROTATED_NAME, CURRENT_NAME)) {
            total += File(directory(context), name).length()
        }
        return total
    }

    @Synchronized
    fun clear(context: Context) {
        for (name in arrayOf(ROTATED_NAME, CURRENT_NAME)) {
            val file = File(directory(context), name)
            if (file.isFile && !file.delete()) {
                Log.w(TAG, "Unable to delete $name")
            }
        }
    }

    /** Collapses whitespace so a value can never break the one-line format. */
    fun oneLine(value: String?): String {
        if (value.isNullOrEmpty()) {
            return "-"
        }
        return value.replace(WHITESPACE, "_")
    }

    @Synchronized
    private fun write(context: Context, line: String) {
        Log.i(TAG, line)
        try {
            val directory = directory(context)
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.w(TAG, "Unable to create the diagnostics directory")
                return
            }
            val current = File(directory, CURRENT_NAME)
            val encoded = (line + "\n").toByteArray(StandardCharsets.UTF_8)
            if (current.length() + encoded.size > MAX_FILE_BYTES) {
                rotate(directory, current)
            }
            FileOutputStream(current, true).use { output -> output.write(encoded) }
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to append a diagnostics line", failure)
        }
    }

    private fun rotate(directory: File, current: File) {
        val rotated = File(directory, ROTATED_NAME)
        if (rotated.isFile && !rotated.delete()) {
            Log.w(TAG, "Unable to drop the oldest diagnostics generation")
            return
        }
        if (!current.renameTo(rotated)) {
            Log.w(TAG, "Unable to rotate the diagnostics log")
        }
    }

    private fun directory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    private val WHITESPACE = Regex("\\s+")
}
