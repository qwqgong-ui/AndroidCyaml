package io.github.qwqgong.androidcyaml

import android.app.PendingIntent
import android.content.Context
import android.net.VpnService

interface VpnPlatformHost {
    fun platformContext(): Context

    fun newPlatformBuilder(): VpnService.Builder

    fun openAppPendingIntent(): PendingIntent
}
