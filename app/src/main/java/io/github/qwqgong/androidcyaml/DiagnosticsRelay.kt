package io.github.qwqgong.androidcyaml

import org.json.JSONArray

/**
 * Holds diagnostics lines produced outside the log process until it collects
 * them.
 *
 * The log lives in one file with two rotating generations. A file like that
 * tolerates exactly one writer: two processes appending to it interleave
 * mid-line, and worse, both can decide to rotate at once and drop a generation
 * the other was still appending to. So the proxy process does not write. It
 * records here, and the log process drains this over the same binder call that
 * fetches the core's metrics -- one round trip a minute rather than one per
 * event.
 *
 * The buffer is bounded and says how much it lost. A diagnostics buffer that
 * grows without limit turns a reporting path into the leak it was added to
 * find, and a silent drop makes a busy minute look like a quiet one.
 */
object DiagnosticsRelay {
    // A minute of ordinary events is tens of lines; a minute containing a
    // reconnect storm is thousands. This holds a storm without letting an
    // unattended process accumulate forever if the log process never binds.
    private const val CAPACITY = 4096

    private val lines = ArrayDeque<String>(64)
    private var dropped = 0L

    @Synchronized
    fun record(line: String) {
        if (lines.size >= CAPACITY) {
            dropped++
            return
        }
        lines.addLast(line)
    }

    /**
     * Hands over everything recorded since the last call, as a JSON array with
     * the drop count appended as a final synthetic line when there was one.
     */
    @Synchronized
    fun drain(): JSONArray {
        val payload = JSONArray()
        for (line in lines) {
            payload.put(line)
        }
        lines.clear()
        if (dropped != 0L) {
            payload.put("event=relay.dropped lines=$dropped")
            dropped = 0L
        }
        return payload
    }

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0L
    }
}
