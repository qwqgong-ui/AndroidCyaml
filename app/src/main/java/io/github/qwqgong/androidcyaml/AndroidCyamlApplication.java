package io.github.qwqgong.androidcyaml;

import android.app.Application;
import android.webkit.WebView;

public final class AndroidCyamlApplication extends Application {
    @SuppressWarnings("FieldCanBeLocal")
    private FairMemoryManager fairMemoryManager;

    @Override
    public void onCreate() {
        super.onCreate();
        if (getPackageName().equals(Application.getProcessName())) {
            // The service process never renders UI. Keep WebView entirely in :ui.
            WebView.disableWebView();
            fairMemoryManager = FairMemoryManager.start(this);
        }
        TombstoneStore.captureAsync(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        FairMemoryManager.releaseLocalCaches();
    }

    @Override
    public void onLowMemory() {
        FairMemoryManager.releaseLocalCaches();
        super.onLowMemory();
    }
}
