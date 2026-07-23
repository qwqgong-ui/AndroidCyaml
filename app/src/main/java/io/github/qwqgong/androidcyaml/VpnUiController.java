package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;

final class VpnUiController {
    interface Listener {
        void onToggleRequested(boolean checked);

        void onMessage(String message);

        void onAutomaticPermissionDenied();
    }

    private final Activity activity;
    private final int permissionRequestCode;
    private final Listener listener;
    private boolean automaticPermissionRequest;

    VpnUiController(Activity activity, int permissionRequestCode, Listener listener) {
        this.activity = activity;
        this.permissionRequestCode = permissionRequestCode;
        this.listener = listener;
    }

    void requestStart(RuntimeState state, boolean automatic) {
        if (state == RuntimeState.STARTING || state == RuntimeState.RUNNING) {
            return;
        }
        try {
            Intent permission = VpnService.prepare(activity);
            if (permission == null) {
                automaticPermissionRequest = false;
                startService();
                return;
            }
            automaticPermissionRequest = automatic;
            activity.startActivityForResult(permission, permissionRequestCode);
        } catch (RuntimeException exception) {
            automaticPermissionRequest = false;
            listener.onToggleRequested(false);
            listener.onMessage(activity.getString(R.string.vpn_permission_failed));
            if (automatic) {
                listener.onAutomaticPermissionDenied();
            }
        }
    }

    void handlePermissionResult(boolean granted) {
        boolean automatic = automaticPermissionRequest;
        automaticPermissionRequest = false;
        if (granted) {
            startService();
            return;
        }
        listener.onToggleRequested(false);
        if (automatic) {
            listener.onAutomaticPermissionDenied();
            listener.onMessage(activity.getString(R.string.auto_start_vpn_permission_denied));
        } else {
            listener.onMessage(activity.getString(R.string.vpn_permission_denied));
        }
    }

    void requestStop(boolean alwaysOn) {
        if (alwaysOn) {
            listener.onToggleRequested(true);
            listener.onMessage(activity.getString(R.string.vpn_always_on_controlled));
            return;
        }
        try {
            activity.startService(
                    new Intent(activity, AndroidVpnService.class)
                            .setAction(AndroidVpnService.ACTION_STOP)
            );
        } catch (RuntimeException exception) {
            listener.onToggleRequested(true);
            listener.onMessage(activity.getString(
                    R.string.vpn_start_failed,
                    usefulMessage(exception)
            ));
        }
    }

    private void startService() {
        try {
            activity.startForegroundService(
                    new Intent(activity, AndroidVpnService.class)
                            .setAction(AndroidVpnService.ACTION_START)
            );
            listener.onToggleRequested(true);
        } catch (RuntimeException exception) {
            listener.onToggleRequested(false);
            listener.onMessage(activity.getString(
                    R.string.vpn_start_failed,
                    usefulMessage(exception)
            ));
        }
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
