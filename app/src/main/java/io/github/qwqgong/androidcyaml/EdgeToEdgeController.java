package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;

final class EdgeToEdgeController {
    private EdgeToEdgeController() {}

    static void apply(Activity activity, View root) {
        Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        boolean nightMode = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        window.setStatusBarColor(activity.getColor(R.color.vpn_bar_background));
        window.setNavigationBarColor(activity.getColor(R.color.surface));
        window.setNavigationBarContrastEnforced(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    nightMode ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            );
        }
        View statusBarScrim = activity.findViewById(R.id.status_bar_scrim);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            ViewGroup.LayoutParams scrimLayout = statusBarScrim.getLayoutParams();
            if (scrimLayout.height != bars.top) {
                scrimLayout.height = bars.top;
                statusBarScrim.setLayoutParams(scrimLayout);
            }
            view.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
