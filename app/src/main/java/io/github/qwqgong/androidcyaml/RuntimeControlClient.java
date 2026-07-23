package io.github.qwqgong.androidcyaml;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

final class RuntimeControlClient {
    interface Listener {
        void onRuntimeSnapshot(
                int state,
                String detail,
                boolean alwaysOn,
                boolean lockdown,
                String dashboardUrl,
                int controllerPort,
                String tunStackOverride
        );

        void onControlDisconnected();
    }

    interface ResultCallback {
        void onComplete(boolean success, String detail);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IAppControl service;
    private boolean binding;
    private boolean bound;

    private final IControlCallback callback = new IControlCallback.Stub() {
        @Override
        public void onStateChanged(
                int state,
                String detail,
                boolean alwaysOn,
                boolean lockdown,
                String dashboardUrl,
                int controllerPort,
                String tunStackOverride
        ) {
            mainHandler.post(() -> listener.onRuntimeSnapshot(
                    state,
                    detail,
                    alwaysOn,
                    lockdown,
                    dashboardUrl,
                    controllerPort,
                    tunStackOverride
            ));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            bound = true;
            service = IAppControl.Stub.asInterface(binder);
            try {
                service.registerCallback(callback);
            } catch (RemoteException exception) {
                disconnect();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            disconnect();
        }
    };

    RuntimeControlClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean bind() {
        if (binding || bound) {
            return true;
        }
        binding = true;
        boolean requested = context.bindService(
                new Intent(context, AppControlService.class),
                connection,
                Context.BIND_AUTO_CREATE
        );
        if (!requested) {
            binding = false;
            listener.onControlDisconnected();
        }
        return requested;
    }

    void unbind() {
        IAppControl current = service;
        if (current != null) {
            try {
                current.unregisterCallback(callback);
            } catch (RemoteException ignored) {
                // The service process may already be gone.
            }
        }
        service = null;
        if (binding || bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The binding may already have been removed.
            }
        }
        binding = false;
        bound = false;
    }

    void restart(ResultCallback result) {
        IAppControl current = service;
        if (current == null) {
            result.onComplete(false, "运行时控制服务暂不可用");
            return;
        }
        try {
            current.restartRuntime(operationCallback(result));
        } catch (RemoteException exception) {
            disconnect();
            result.onComplete(false, usefulMessage(exception));
        }
    }

    void importConfig(Uri source, ResultCallback result) {
        IAppControl current = service;
        if (current == null) {
            result.onComplete(false, "运行时控制服务暂不可用");
            return;
        }
        try {
            current.importConfig(source, operationCallback(result));
        } catch (RemoteException exception) {
            disconnect();
            result.onComplete(false, usefulMessage(exception));
        }
    }

    void setTunStackOverride(TunStackOverride override, ResultCallback result) {
        IAppControl current = service;
        if (current == null) {
            result.onComplete(false, "运行时控制服务暂不可用");
            return;
        }
        TunStackOverride value = override == null ? TunStackOverride.CONFIG : override;
        try {
            current.setTunStackOverride(value.wireValue(), operationCallback(result));
        } catch (RemoteException exception) {
            disconnect();
            result.onComplete(false, usefulMessage(exception));
        }
    }

    private IOperationCallback operationCallback(ResultCallback result) {
        return new IOperationCallback.Stub() {
            @Override
            public void onComplete(boolean success, String detail) {
                mainHandler.post(() -> result.onComplete(success, detail));
            }
        };
    }

    private void disconnect() {
        binding = false;
        bound = false;
        service = null;
        mainHandler.post(listener::onControlDisconnected);
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
