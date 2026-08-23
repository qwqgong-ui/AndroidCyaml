package io.github.qwqgong.androidcyaml

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException

class AppControlService : Service(), RuntimeStateBus.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = RemoteCallbackList<IControlCallback>()

    private lateinit var coordinator: RuntimeCoordinator
    private var snapshot: RuntimeSnapshot = RuntimeSnapshot.stopped()

    private val binder: IAppControl.Stub = object : IAppControl.Stub() {
        override fun registerCallback(callback: IControlCallback?) {
            enforceSameAppCaller()
            if (callback != null) {
                callbacks.register(callback)
                mainHandler.post { sendSnapshot(callback) }
            }
        }

        override fun unregisterCallback(callback: IControlCallback?) {
            enforceSameAppCaller()
            if (callback != null) {
                callbacks.unregister(callback)
            }
        }

        override fun restartRuntime(callback: IOperationCallback?) {
            enforceSameAppCaller()
            coordinator.restart { success, message -> complete(callback, success, message) }
        }

        override fun importConfig(source: Uri?, callback: IOperationCallback?) {
            enforceSameAppCaller()
            coordinator.importConfig(source) { success, message ->
                complete(callback, success, message)
            }
        }

        override fun getNetworkSelectionCatalog(callback: IOperationCallback?) {
            enforceSameAppCaller()
            coordinator.networkSelectionCatalog { success, message ->
                complete(callback, success, message)
            }
        }

        override fun setNetworkSelection(
            identity: String?,
            group: String?,
            target: String?,
            callback: IOperationCallback?,
        ) {
            enforceSameAppCaller()
            coordinator.setNetworkSelection(identity, group, target) { success, message ->
                complete(callback, success, message)
            }
        }

        override fun setRuntimeOverrides(
            processMatchingMode: String?,
            ipv6Enabled: Boolean,
            logLevel: String?,
            adaptiveTcpConcurrent: Boolean,
            webViewXhttp: Boolean,
            lanWebUiPublic: Boolean,
            callback: IOperationCallback?,
        ) {
            enforceSameAppCaller()
            coordinator.setRuntimeOverrides(
                processMatchingMode,
                ipv6Enabled,
                logLevel,
                adaptiveTcpConcurrent,
                webViewXhttp,
                lanWebUiPublic,
            ) { success, message -> complete(callback, success, message) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        coordinator = RuntimeCoordinator.getInstance(this)
        coordinator.addListener(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (this::coordinator.isInitialized) {
            coordinator.removeListener(this)
        }
        callbacks.kill()
        super.onDestroy()
    }

    override fun onRuntimeStateChanged(snapshot: RuntimeSnapshot) {
        this.snapshot = snapshot
        broadcastSnapshot()
    }

    private fun broadcastSnapshot() {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                sendSnapshot(callbacks.getBroadcastItem(index))
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun sendSnapshot(callback: IControlCallback) {
        val current = snapshot
        val overrides = coordinator.runtimeOverrideSettings()
        try {
            callback.onStateChanged(
                RuntimeSnapshotPayload(
                    current.state.ordinal,
                    current.detail,
                    AndroidVpnService.isAlwaysOnMode(),
                    AndroidVpnService.isLockdownMode(),
                    if (current.state == RuntimeState.RUNNING) current.dashboardUrl else "",
                    if (current.state == RuntimeState.RUNNING) current.controllerPort else 0,
                    overrides.processMatchingMode.wireValue,
                    overrides.ipv6Enabled,
                    coordinator.effectiveIpv6Enabled(),
                    overrides.logLevel.wireValue,
                    overrides.adaptiveTcpConcurrent,
                    overrides.webViewXhttp,
                    overrides.lanWebUiPublic,
                ),
            )
        } catch (ignored: RemoteException) {
            // RemoteCallbackList removes dead UI callbacks automatically.
        }
    }

    private companion object {
        fun complete(callback: IOperationCallback?, success: Boolean, detail: String?) {
            if (callback == null) {
                return
            }
            try {
                callback.onComplete(success, detail ?: "")
            } catch (ignored: RemoteException) {
                // The UI may have been reclaimed while the operation completed.
            }
        }

        fun enforceSameAppCaller() {
            if (Binder.getCallingUid() != Process.myUid()) {
                throw SecurityException("Only AndroidCyaml may use its control service")
            }
        }
    }
}
