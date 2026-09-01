package io.github.qwqgong.androidcyaml

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Samples memory and runtime counters on a timer while log mode is on.
 *
 * The battery contract is the whole design constraint: this uses no alarm, no
 * job, and no wake lock. `Handler.postDelayed` runs on the awake clock, which
 * does not advance while the device is suspended, so the sampler cannot wake
 * the CPU -- it pauses through doze and resumes on a wake-up something else
 * already paid for. Each sample is one small `/proc` read, one task-directory
 * walk, and one lock-free call into the Go core.
 */
object DiagnosticsSampler {
    private const val TAG = "AndroidCyaml/Diag"
    private const val SAMPLE_INTERVAL_MILLIS = 60_000L
    private const val EXIT_HISTORY_LIMIT = 16
    private const val ART_THREAD_PREFIX = "Thread-"

    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    // Handed over between reading the native payload and writing it out, so the
    // metrics line is assembled and appended before the lines it summarises.
    private var pendingCoreLog: JSONArray? = null
    private var pendingCoreLogDropped: Long = 0L

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            start(context.applicationContext)
        } else {
            stop(context.applicationContext)
        }
    }

    @Synchronized
    fun isRunning(): Boolean = handler != null

    @Synchronized
    private fun start(context: Context) {
        if (handler != null) {
            return
        }
        val thread = HandlerThread("AndroidCyaml-diagnostics", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val target = Handler(thread.looper)
        worker = thread
        handler = target
        DiagnosticsLog.setEnabled(true)
        target.post {
            setCoreDiagnostics(true)
            DiagnosticsLog.append(context, "diag.start", "interval=" + SAMPLE_INTERVAL_MILLIS)
            // Historical exits are the only first-hand evidence of the reported
            // low-memory kills, and they carry the PSS/RSS the process died at.
            appendExitHistory(context)
            sample(context)
            scheduleNext(context, target)
        }
    }

    @Synchronized
    private fun stop(context: Context) {
        val thread = worker ?: return
        val target = handler
        // Drop the pending tick first, then hand the closing line to the worker
        // so no file write lands on the caller's thread. quitSafely still runs
        // what is already queued.
        target?.removeCallbacksAndMessages(null)
        target?.post {
            DiagnosticsLog.append(context, "diag.stop", "")
            DiagnosticsLog.setEnabled(false)
            setCoreDiagnostics(false)
        }
        thread.quitSafely()
        worker = null
        handler = null
    }

    private fun scheduleNext(context: Context, target: Handler) {
        target.postDelayed({
            sample(context)
            scheduleNext(context, target)
        }, SAMPLE_INTERVAL_MILLIS)
    }

    private fun sample(context: Context) {
        val detail = StringBuilder(320)
        appendProcessStatus(detail)
        appendJavaHeap(detail)
        appendArtAttachFloor(detail)
        detail.append(' ').append(NetworkDiagnostics.sample())
        appendCoreMetrics(detail)
        DiagnosticsLog.append(context, "sample", detail.toString().trim())
        appendCoreLog(context)
    }

    /**
     * Writes mihomo's own lines from this window into the diagnostics log.
     *
     * The counters in the sample above say a storm happened; only these lines
     * say what was dialed, which rule matched and through which outbound. They
     * live in the core's log stream, which the dashboard shows and then forgets
     * on restart, so they are copied into the file that rotates and exports.
     *
     * Each line is its own record: one `sample` event carrying thousands of
     * lines would be unreadable, and rotation could then discard a whole window
     * at once instead of its oldest part.
     */
    private fun appendCoreLog(context: Context) {
        val lines = pendingCoreLog
        val dropped = pendingCoreLogDropped
        pendingCoreLog = null
        pendingCoreLogDropped = 0L

        if (dropped > 0L) {
            DiagnosticsLog.append(context, "core.dropped", "lines=" + dropped)
        }
        if (lines == null) {
            return
        }
        for (index in 0 until lines.length()) {
            val line = lines.optString(index, "")
            if (line.isNotEmpty()) {
                DiagnosticsLog.append(context, "core", line)
            }
        }
    }

    private fun appendProcessStatus(out: StringBuilder) {
        var found = false
        try {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> {
                        out.append(" rssKb=").append(numericValue(line))
                        found = true
                    }
                    line.startsWith("VmSwap:") -> out.append(" swapKb=").append(numericValue(line))
                    line.startsWith("Threads:") -> out.append(" threads=").append(numericValue(line))
                }
            }
        } catch (failure: IOException) {
            Log.d(TAG, "Unable to read process status", failure)
        }
        if (!found) {
            out.append(" rssKb=-1")
        }
    }

    private fun appendJavaHeap(out: StringBuilder) {
        val runtime = Runtime.getRuntime()
        out.append(" javaHeapKb=").append((runtime.totalMemory() - runtime.freeMemory()) / 1024L)
        out.append(" javaHeapMaxKb=").append(runtime.maxMemory() / 1024L)
    }

    /**
     * Highest `Thread-N` ordinal among the threads still alive. ART names a
     * thread when it attaches, so this rises whenever something crosses into
     * the JVM on a new thread. High-numbered threads that already exited are
     * invisible here, which makes it a floor rather than an attach count -- it
     * answers "are attaches still happening", not "how many".
     */
    private fun appendArtAttachFloor(out: StringBuilder) {
        var highest = -1
        try {
            val tasks = File("/proc/self/task").listFiles()
            if (tasks != null) {
                for (task in tasks) {
                    val name = File(task, "comm").readText().trim()
                    if (!name.startsWith(ART_THREAD_PREFIX)) {
                        continue
                    }
                    val ordinal = name.removePrefix(ART_THREAD_PREFIX).toIntOrNull() ?: continue
                    if (ordinal > highest) {
                        highest = ordinal
                    }
                }
            }
        } catch (failure: IOException) {
            Log.d(TAG, "Unable to read the ART attach floor", failure)
        }
        out.append(" artAttachFloor=").append(highest)
    }

    /** Nothing subscribes to the core's log fan-out unless diagnostics is on. */
    private fun setCoreDiagnostics(enabled: Boolean) {
        try {
            MihomoNative.setDiagnostics(enabled)
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to switch the core log classifier", failure)
        } catch (failure: LinkageError) {
            Log.w(TAG, "Core library is unavailable for diagnostics", failure)
        }
    }

    private fun appendCoreMetrics(out: StringBuilder) {
        val payload: JSONObject? = try {
            MihomoNative.runtimeMetrics()
        } catch (failure: IOException) {
            out.append(" core=unavailable")
            return
        } catch (failure: LinkageError) {
            out.append(" core=unloaded")
            return
        }
        val metrics = payload?.optJSONObject("metrics")
        if (metrics == null) {
            out.append(" core=empty")
            return
        }
        pendingCoreLog = payload.optJSONArray("coreLog")
        pendingCoreLogDropped = payload.optLong("coreLogDropped", 0L)
        for (key in metrics.keys().asSequence().sorted()) {
            out.append(' ').append(key).append('=').append(metrics.optLong(key, -1L))
        }
        val unavailable = payload.optJSONArray("unavailable") ?: return
        if (unavailable.length() != 0) {
            out.append(" coreMetricsMissing=").append(unavailable.length())
        }
    }

    private fun appendExitHistory(context: Context) {
        try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
            for (exit in activityManager.getHistoricalProcessExitReasons(
                null,
                0,
                EXIT_HISTORY_LIMIT,
            )) {
                DiagnosticsLog.append(
                    context,
                    "exit",
                    "at=" + exit.timestamp +
                        " process=" + DiagnosticsLog.oneLine(exit.processName) +
                        " reason=" + exit.reason +
                        " status=" + exit.status +
                        " importance=" + exit.importance +
                        " pssKb=" + exit.pss +
                        " rssKb=" + exit.rss +
                        " description=" + DiagnosticsLog.oneLine(exit.description),
                )
            }
        } catch (failure: RuntimeException) {
            Log.d(TAG, "Unable to read historical exit reasons", failure)
        }
    }

    /** `/proc/self/status` values are `Name:\t<number> kB` or a bare number. */
    private fun numericValue(line: String): Long {
        val separator = line.indexOf(':')
        if (separator < 0) {
            return -1L
        }
        val digits = line.substring(separator + 1).trim().substringBefore(' ')
        return digits.toLongOrNull() ?: -1L
    }
}
