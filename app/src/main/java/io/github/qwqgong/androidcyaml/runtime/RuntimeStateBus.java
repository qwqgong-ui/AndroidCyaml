package io.github.qwqgong.androidcyaml;

import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class RuntimeStateBus {
    interface Listener {
        void onRuntimeStateChanged(RuntimeSnapshot snapshot);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.stopped();

    void addListener(Listener listener) {
        listeners.add(listener);
        RuntimeSnapshot current = snapshot;
        mainHandler.post(() -> listener.onRuntimeStateChanged(current));
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    RuntimeSnapshot snapshot() {
        return snapshot;
    }

    void publish(RuntimeSnapshot next) {
        snapshot = next;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onRuntimeStateChanged(next);
            }
        });
    }
}
