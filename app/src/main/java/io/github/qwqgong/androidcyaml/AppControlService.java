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

public final class AppControlService extends Service implements RuntimeStateBus.Listener {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IControlCallback> callbacks = new RemoteCallbackList<>();

    private RuntimeCoordinator coordinator;
    private RuntimeSnapshot snapshot = RuntimeSnapshot.stopped();

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

        @Override
        public void setTunStackOverride(String override, IOperationCallback callback) {
            enforceSameAppCaller();
            coordinator.setTunStackOverride(
                    override,
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
    public void onRuntimeStateChanged(RuntimeSnapshot next) {
        snapshot = next;
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
        RuntimeSnapshot current = snapshot;
        try {
            callback.onStateChanged(
                    current.state().ordinal(),
                    current.detail(),
                    AndroidVpnService.isAlwaysOnMode(),
                    AndroidVpnService.isLockdownMode(),
                    current.state() == RuntimeState.RUNNING ? current.dashboardUrl() : "",
                    current.state() == RuntimeState.RUNNING ? current.controllerPort() : 0,
                    coordinator.tunStackOverride().wireValue()
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
