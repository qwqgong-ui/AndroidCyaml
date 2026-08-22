package io.github.qwqgong.androidcyaml

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

object EdgeToEdgeController {
    @JvmStatic
    fun apply(activity: Activity, root: View) {
        val window = activity.window
        window.setDecorFitsSystemWindows(false)
        val nightMode = (activity.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = activity.getColor(R.color.vpn_bar_background)
        window.navigationBarColor = activity.getColor(R.color.surface)
        window.isNavigationBarContrastEnforced = false
        val controller = window.insetsController
        if (controller != null) {
            controller.setSystemBarsAppearance(
                if (nightMode) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }
        val statusBarScrim = activity.findViewById<View>(R.id.status_bar_scrim)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val scrimLayout = statusBarScrim.layoutParams
            if (scrimLayout.height != bars.top) {
                scrimLayout.height = bars.top
                statusBarScrim.layoutParams = scrimLayout
            }
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }
}
