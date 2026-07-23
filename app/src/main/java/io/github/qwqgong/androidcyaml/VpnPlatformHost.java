package io.github.qwqgong.androidcyaml;

import android.app.PendingIntent;
import android.content.Context;
import android.net.VpnService;

interface VpnPlatformHost {
    Context platformContext();

    VpnService.Builder newPlatformBuilder();

    PendingIntent openAppPendingIntent();
}
