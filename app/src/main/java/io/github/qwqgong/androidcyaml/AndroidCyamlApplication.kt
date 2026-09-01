package io.github.qwqgong.androidcyaml

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView

class AndroidCyamlApplication : Application() {
    private var fairMemoryManager: FairMemoryManager? = null

    override fun onCreate() {
        super.onCreate()
        if (ProcessRole.isLogProcess(this)) {
            // The log process owns the file and the schedule, and nothing else.
            // DiagnosticsService drives it; there is no runtime here to sample
            // directly and no WebView to configure.
            DiagnosticsLog.setEnabled(UiPreferences(this).diagnosticsEnabled())
        } else if (isServiceProcess()) {
            // WebView is not created unless the XHTTP WebView override is
            // enabled. Do not call WebView.disableWebView(): that API is
            // irreversible for the lifetime of this VPN process.
            fairMemoryManager = FairMemoryManager.start(this)
            // Restore log mode across process restarts. That is exactly when
            // the interesting evidence exists: a low-memory kill has just
            // happened and its exit record is waiting to be read.
            DiagnosticsLog.setEnabled(UiPreferences(this).diagnosticsEnabled())
            if (DiagnosticsLog.isEnabled()) {
                DiagnosticsService.start(this)
            }
        } else {
            // Dashboard and Browser Dialer may coexist in different app
            // processes. Chromium requires a separate data directory for each.
            WebView.setDataDirectorySuffix("ui")
            WebView.setWebContentsDebuggingEnabled(
                applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }
        // Run in both processes so a reclaimed :ui process can record its own
        // exit even while the foreground-service process stayed alive.
        TombstoneStore.captureAsync(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isServiceProcess()) {
            FairMemoryManager.releaseLocalCaches()
        }
    }

    override fun onLowMemory() {
        if (isServiceProcess()) {
            FairMemoryManager.releaseLocalCaches()
        }
        super.onLowMemory()
    }

    private fun isServiceProcess(): Boolean = packageName == getProcessName()
}
