package io.github.qwqgong.androidcyaml;

import android.app.Application;
import android.webkit.WebView;

public final class AndroidCyamlApplication extends Application {
    @SuppressWarnings("FieldCanBeLocal")
    private FairMemoryManager fairMemoryManager;
    private WifiIpv6Monitor wifiIpv6Monitor;

    @Override
    public void onCreate() {
        super.onCreate();
        if (getPackageName().equals(Application.getProcessName())) {
            // The VPN/core process never renders UI. Prevent accidental WebView initialization there.
            WebView.disableWebView();
            fairMemoryManager = FairMemoryManager.start(this);
            MihomoManager manager = MihomoManager.getInstance(this);
            wifiIpv6Monitor = WifiIpv6Monitor.start(
                    this,
                    manager::setWifiIpv6Unavailable
            );
        }
        TombstoneStore.captureAsync(this);
    }

    @Override
    public void onTerminate() {
        if (wifiIpv6Monitor != null) {
            wifiIpv6Monitor.stop();
        }
        super.onTerminate();
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
