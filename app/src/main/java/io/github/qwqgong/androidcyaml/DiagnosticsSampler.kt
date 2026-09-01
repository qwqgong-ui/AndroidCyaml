package io.github.qwqgong.androidcyaml

import android.app.ActivityManager
import android.content.Context
import android.util.Log
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

    // A process has dozens of one-off threads whose names carry nothing. Naming
    // every one of them each minute would bury the pools that actually grow.
    private const val THREAD_CENSUS_FLOOR = 2
    private const val EXIT_HISTORY_LIMIT = 16
    private const val ART_THREAD_PREFIX = "Thread-"


    // Handed over between reading the native payload and writing it out, so the
    // metrics line is assembled and appended before the lines it summarises.

    /**
     * Turns the platform half of log mode on or off.
     *
     * This object owns no thread and no clock any more. The log process does --
     * see [DiagnosticsService] -- because a sampler living beside the runtime it
     * measures shares that runtime's lifetime, and a core restart or a
     * low-memory kill is exactly the moment the record has to survive. What is
     * left here is the part that can only run in the proxy process: switching
     * the core's log classifier, and building the sample line from this
     * process's own state.
     */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val application = context.applicationContext
        DiagnosticsLog.setEnabled(enabled)
        setCoreDiagnostics(enabled)
        if (enabled) {
            DiagnosticsLog.append(application, "diag.start", "interval=" + SAMPLE_INTERVAL_MILLIS)
            // Historical exits are the only first-hand evidence of the reported
            // low-memory kills, and they carry the PSS/RSS the process died at.
            appendExitHistory(application)
            DiagnosticsService.start(application)
        } else {
            DiagnosticsLog.append(application, "diag.stop", "")
            DiagnosticsService.stop(application)
            DiagnosticsRelay.clear()
        }
    }

    @Synchronized
    fun isRunning(): Boolean = DiagnosticsLog.isEnabled()

    /**
     * Builds one sample line without writing it. The log process asks for this
     * over binder and decides where it lands.
     */
    fun describe(context: Context): String {
        val detail = StringBuilder(320)
        appendProcessStatus(detail)
        appendJavaHeap(detail)
        appendThreadCensus(detail)
        detail.append(' ').append(NetworkDiagnostics.sample())
        appendCoreMetrics(detail)
        return detail.toString().trim()
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
    /**
     * Reports the ART attach high-water mark and a census of thread names, from
     * one walk of `/proc/self/task`.
     *
     * The census exists because `threads=N` says the count went up and nothing
     * else. Answering "which ones" then meant reading `comm` over adb and
     * guessing, and `comm` truncates at 15 characters, so names that share a
     * prefix are indistinguishable from outside. Counting them here, by the same
     * truncated name the kernel reports, makes a growing pool name itself.
     *
     * ART's own `Thread-N` workers are folded into one entry: they are already
     * summarised by the attach floor, and listing them individually would be
     * thousands of entries describing one thing.
     */
    private fun appendThreadCensus(out: StringBuilder) {
        var highest = -1
        val census = HashMap<String, Int>(32)
        try {
            val tasks = File("/proc/self/task").listFiles()
            if (tasks != null) {
                for (task in tasks) {
                    val name = try {
                        File(task, "comm").readText().trim()
                    } catch (ignored: IOException) {
                        // The thread exited between listing and reading.
                        continue
                    }
                    if (name.isEmpty()) {
                        continue
                    }
                    if (name.startsWith(ART_THREAD_PREFIX)) {
                        val ordinal = name.removePrefix(ART_THREAD_PREFIX).toIntOrNull()
                        if (ordinal != null) {
                            if (ordinal > highest) {
                                highest = ordinal
                            }
                            census[ART_THREAD_PREFIX + "N"] =
                                (census[ART_THREAD_PREFIX + "N"] ?: 0) + 1
                            continue
                        }
                    }
                    census[name] = (census[name] ?: 0) + 1
                }
            }
        } catch (failure: IOException) {
            Log.d(TAG, "Unable to read the thread census", failure)
        }
        out.append(" artAttachFloor=").append(highest)
        for (entry in census.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        )) {
            if (entry.value < THREAD_CENSUS_FLOOR) {
                continue
            }
            out.append(" thread.").append(entry.key.replace(' ', '_'))
                .append('=').append(entry.value)
        }
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
