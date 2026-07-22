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

public final class AppControlService extends Service implements
        MihomoManager.Listener,
        AndroidVpnService.Listener {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IControlCallback> callbacks = new RemoteCallbackList<>();

    private MihomoManager manager;
    private MihomoManager.State coreState = MihomoManager.State.STOPPED;
    private String coreDetail = "";
    private AndroidVpnService.State vpnState = AndroidVpnService.State.STOPPED;
    private String vpnDetail = "VPN 未连接";
    private boolean alwaysOn;
    private boolean lockdown;

    private final IAppControl.Stub binder = new IAppControl.Stub() {
        @Override
        public void registerCallback(IControlCallback callback) {
            enforceSameAppCaller();
            if (callback == null) {
                return;
            }
            callbacks.register(callback);
            mainHandler.post(() -> sendSnapshot(callback));
        }

        @Override
        public void unregisterCallback(IControlCallback callback) {
            enforceSameAppCaller();
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public void ensureCoreStarted() {
            enforceSameAppCaller();
            manager.ensureStarted();
        }

        @Override
        public void restartCore() {
            enforceSameAppCaller();
            manager.restart();
        }

        @Override
        public void importConfig(Uri source, IOperationCallback callback) {
            enforceSameAppCaller();
            if (source == null) {
                complete(callback, false, "未选择配置文件");
                return;
            }
            manager.importConfig(
                    source,
                    (success, detail) -> complete(callback, success, detail)
            );
        }

        @Override
        public void setProcessMatchOverride(String mode, IOperationCallback callback) {
            enforceSameAppCaller();
            manager.setProcessMatchOverride(
                    mode,
                    (success, detail) -> complete(callback, success, detail)
            );
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        manager = MihomoManager.getInstance(this);
        manager.addListener(this);
        AndroidVpnService.addListener(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        AndroidVpnService.removeListener(this);
        if (manager != null) {
            manager.removeListener(this);
        }
        callbacks.kill();
        super.onDestroy();
    }

    @Override
    public void onCoreStateChanged(MihomoManager.State state, String detail) {
        coreState = state;
        coreDetail = detail;
        broadcastSnapshot();
    }

    @Override
    public void onVpnStateChanged(AndroidVpnService.State state, String detail) {
        vpnState = state;
        vpnDetail = detail;
        alwaysOn = AndroidVpnService.isAlwaysOnMode();
        lockdown = AndroidVpnService.isLockdownMode();
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
        String dashboardUrl = coreState == MihomoManager.State.RUNNING
                ? manager.getDashboardUrl()
                : "";
        try {
            callback.onStateChanged(
                    coreState.ordinal(),
                    coreDetail,
                    vpnState.ordinal(),
                    vpnDetail,
                    alwaysOn,
                    lockdown,
                    dashboardUrl,
                    manager.getControllerPort(),
                    manager.getProcessMatchOverride()
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
            // The UI may have been paused or reclaimed while work completed.
        }
    }

    private static void enforceSameAppCaller() {
        if (Binder.getCallingUid() != android.os.Process.myUid()) {
            throw new SecurityException("Only AndroidCyaml may use its control service");
        }
    }
}
