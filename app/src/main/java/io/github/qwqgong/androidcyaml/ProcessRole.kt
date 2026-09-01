package io.github.qwqgong.androidcyaml

import android.app.Application
import android.content.Context

/**
 * Which of the three processes this code is running in.
 *
 * The split is deliberate. The proxy process owns the VPN, the native libraries
 * and the Go runtime, and it is the one Android kills first under memory
 * pressure. The UI process owns the dashboard and its WebViews, and comes and
 * goes with the user. The log process owns the diagnostics file, and exists
 * precisely so that neither of the other two taking a restart takes the record
 * of it with them.
 *
 * The name is resolved once. `Application.getProcessName` is a syscall-free
 * read of a cached value, but the answer cannot change for the life of a
 * process, so caching it keeps the check usable on paths that run per event.
 */
object ProcessRole {
    private const val LOG_SUFFIX = ":log"
    private const val UI_SUFFIX = ":ui"

    @Volatile
    private var resolved: String? = null

    private fun processName(context: Context): String {
        resolved?.let { return it }
        val name = Application.getProcessName() ?: context.packageName
        resolved = name
        return name
    }

    fun isLogProcess(context: Context): Boolean = processName(context).endsWith(LOG_SUFFIX)

    fun isUiProcess(context: Context): Boolean = processName(context).endsWith(UI_SUFFIX)

    /** The proxy process is the one whose name carries no suffix. */
    fun isProxyProcess(context: Context): Boolean = processName(context) == context.packageName
}
