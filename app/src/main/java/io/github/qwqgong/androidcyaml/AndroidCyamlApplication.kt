package io.github.qwqgong.androidcyaml

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView

class AndroidCyamlApplication : Application() {
    private var fairMemoryManager: FairMemoryManager? = null

    override fun onCreate() {
        super.onCreate()
        if (isServiceProcess()) {
            // WebView is not created unless the XHTTP WebView override is
            // enabled. Do not call WebView.disableWebView(): that API is
            // irreversible for the lifetime of this VPN process.
            fairMemoryManager = FairMemoryManager.start(this)
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
