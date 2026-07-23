package io.github.qwqgong.androidcyaml;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

public final class AppControlService extends Service implements RuntimeCoordinator.Listener {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IControlCallback> callbacks = new RemoteCallbackList<>();

    private RuntimeCoordinator coordinator;
    private RuntimeCoordinator.State state = RuntimeCoordinator.State.STOPPED;
    private String detail = "VPN 未连接";
    private String dashboardUrl = "";
    private int controllerPort;

    private final IAppControl.Stub binder = new IAppControl.Stub() {
        @Override
        public void registerCallback(IControlCallback callback) {
            enforceSameAppCaller();
            if (callback != null) {
                callbacks.register(callback);
                mainHandler.post(() -> sendSnapshot(callback));
            }
        }

        @Override
        public void unregisterCallback(IControlCallback callback) {
            enforceSameAppCaller();
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public void restartRuntime(IOperationCallback callback) {
            enforceSameAppCaller();
            coordinator.restart((success, message) -> complete(callback, success, message));
        }

        @Override
        public void importConfig(Uri source, IOperationCallback callback) {
            enforceSameAppCaller();
            coordinator.importConfig(
                    source,
                    (success, message) -> complete(callback, success, message)
            );
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        coordinator = RuntimeCoordinator.getInstance(this);
        coordinator.addListener(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (coordinator != null) {
            coordinator.removeListener(this);
        }
        callbacks.kill();
        super.onDestroy();
    }

    @Override
    public void onRuntimeStateChanged(
            RuntimeCoordinator.State newState,
            String newDetail,
            String newDashboardUrl,
            int newControllerPort
    ) {
        state = newState;
        detail = newDetail;
        dashboardUrl = newDashboardUrl;
        controllerPort = newControllerPort;
        broadcastSnapshot();
    }

    private void broadcastSnapshot() {
        int count = callbacks.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                sendSnapshot(callbacks.getBroadcastItem(index));
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    private void sendSnapshot(IControlCallback callback) {
        try {
            callback.onStateChanged(
                    state.ordinal(),
                    detail,
                    AndroidVpnService.isAlwaysOnMode(),
                    AndroidVpnService.isLockdownMode(),
                    state == RuntimeCoordinator.State.RUNNING ? dashboardUrl : "",
                    state == RuntimeCoordinator.State.RUNNING ? controllerPort : 0
            );
        } catch (RemoteException ignored) {
            // RemoteCallbackList removes dead UI callbacks automatically.
        }
    }

    private static void complete(
            IOperationCallback callback,
            boolean success,
            String detail
    ) {
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete(success, detail == null ? "" : detail);
        } catch (RemoteException ignored) {
            // The UI may have been reclaimed while the operation completed.
        }
    }

    private static void enforceSameAppCaller() {
        if (Binder.getCallingUid() != android.os.Process.myUid()) {
            throw new SecurityException("Only AndroidCyaml may use its control service");
        }
    }
}
