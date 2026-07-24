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
            // The service process never renders UI. Keep WebView entirely in :ui.
            WebView.disableWebView();
            fairMemoryManager = FairMemoryManager.start(this);
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
