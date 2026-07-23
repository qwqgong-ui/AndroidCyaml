package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

final class MainActionsMenu {
    interface Listener {
        void onUploadConfig();

        void onRestartRuntime();

        void onOpenVpnSettings();

        void onAutoStartChanged(boolean enabled);

        void onHideRecentsChanged(boolean hidden);
    }

    private static final int UPLOAD = 1;
    private static final int RESTART = 2;
    private static final int VPN_SETTINGS = 3;
    private static final int AUTO_START = 4;
    private static final int HIDE_RECENTS = 5;

    private MainActionsMenu() {}

    static void show(
            Context context,
            View anchor,
            boolean autoStartEnabled,
            boolean hiddenFromRecents,
            Listener listener
    ) {
        PopupMenu popup = new PopupMenu(context, anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, UPLOAD, 0, R.string.upload_config);
        menu.add(Menu.NONE, RESTART, 1, R.string.restart_core);
        menu.add(Menu.NONE, VPN_SETTINGS, 2, R.string.vpn_system_settings);
        menu.add(Menu.NONE, AUTO_START, 3, R.string.auto_start_vpn)
                .setCheckable(true)
                .setChecked(autoStartEnabled);
        menu.add(Menu.NONE, HIDE_RECENTS, 4, R.string.hide_from_recents)
                .setCheckable(true)
                .setChecked(hiddenFromRecents);
        popup.setOnMenuItemClickListener(item -> handle(item, listener));
        popup.show();
    }

    private static boolean handle(MenuItem item, Listener listener) {
        switch (item.getItemId()) {
            case UPLOAD -> listener.onUploadConfig();
            case RESTART -> listener.onRestartRuntime();
            case VPN_SETTINGS -> listener.onOpenVpnSettings();
            case AUTO_START -> {
                boolean enabled = !item.isChecked();
                item.setChecked(enabled);
                listener.onAutoStartChanged(enabled);
            }
            case HIDE_RECENTS -> {
                boolean hidden = !item.isChecked();
                item.setChecked(hidden);
                listener.onHideRecentsChanged(hidden);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
