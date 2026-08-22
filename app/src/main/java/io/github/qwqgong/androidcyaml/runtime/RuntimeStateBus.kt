package io.github.qwqgong.androidcyaml

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

class RuntimeStateBus {
    fun interface Listener {
        fun onRuntimeStateChanged(snapshot: RuntimeSnapshot)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var currentSnapshot: RuntimeSnapshot = RuntimeSnapshot.stopped()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val current = currentSnapshot
        mainHandler.post { listener.onRuntimeStateChanged(current) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun snapshot(): RuntimeSnapshot = currentSnapshot

    fun publish(next: RuntimeSnapshot) {
        currentSnapshot = next
        mainHandler.post {
            for (listener in listeners) {
                listener.onRuntimeStateChanged(next)
            }
        }
    }
}
