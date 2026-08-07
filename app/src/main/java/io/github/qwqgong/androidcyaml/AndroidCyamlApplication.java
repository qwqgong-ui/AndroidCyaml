package io.github.qwqgong.androidcyaml;

import android.app.Application;
import android.webkit.WebView;

public final class AndroidCyamlApplication extends Application {
    @SuppressWarnings("FieldCanBeLocal")
    private FairMemoryManager fairMemoryManager;

    @Override
    public void onCreate() {
        super.onCreate();
        if (isServiceProcess()) {
            // WebView is not created unless the XHTTP WebView override is
            // enabled. Do not call WebView.disableWebView(): that API is
            // irreversible for the lifetime of this VPN process.
            fairMemoryManager = FairMemoryManager.start(this);
        } else {
            // Dashboard and Browser Dialer may coexist in different app
            // processes. Chromium requires a separate data directory for each.
            WebView.setDataDirectorySuffix("ui");
        }
        // Run in both processes so a reclaimed :ui process can record its own
        // exit even while the foreground-service process stayed alive.
        TombstoneStore.captureAsync(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (isServiceProcess()) {
            FairMemoryManager.releaseLocalCaches();
        }
    }

    @Override
    public void onLowMemory() {
        if (isServiceProcess()) {
            FairMemoryManager.releaseLocalCaches();
        }
        super.onLowMemory();
    }

    private boolean isServiceProcess() {
        return getPackageName().equals(Application.getProcessName());
    }
}
