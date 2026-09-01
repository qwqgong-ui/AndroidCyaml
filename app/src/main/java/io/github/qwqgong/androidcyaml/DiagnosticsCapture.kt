package io.github.qwqgong.androidcyaml

import android.content.Context
import org.json.JSONObject

/**
 * Assembles one diagnostics window for the log process.
 *
 * Everything the log needs that only the proxy process can see is gathered
 * here: the platform's own counters, the core's metrics and log lines from the
 * native runtime, and whatever this process recorded through
 * [DiagnosticsRelay] since the last call. It is one payload because it is one
 * binder round trip -- the alternative, a call per event, would put IPC on
 * paths that already run thousands of times a minute during the storms this
 * log exists to explain.
 *
 * The sampler that used to do this on a timer in this process is gone; the
 * clock belongs to the log process now, so that a proxy restart interrupts the
 * subject of the log rather than the log itself.
 */
object DiagnosticsCapture {
    fun collect(context: Context): String {
        val document = JSONObject()
        document.put("sample", DiagnosticsSampler.describe(context))
        document.put("relayed", DiagnosticsRelay.drain())

        val core = try {
            MihomoNative.runtimeMetrics()
        } catch (failure: java.io.IOException) {
            null
        } catch (failure: LinkageError) {
            null
        }
        if (core != null) {
            core.optJSONArray("coreLog")?.let { document.put("coreLog", it) }
            val dropped = core.optLong("coreLogDropped", 0L)
            if (dropped > 0L) {
                document.put("coreLogDropped", dropped)
            }
        }
        return document.toString()
    }
}
