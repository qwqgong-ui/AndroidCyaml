package io.github.qwqgong.androidcyaml

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException

class RuntimeControlClient(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onRuntimeSnapshot(payload: RuntimeSnapshotPayload)

        fun onControlDisconnected()
    }

    fun interface ResultCallback {
        fun onComplete(success: Boolean, detail: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: IAppControl? = null
    private var binding = false
    private var bound = false

    private val callback: IControlCallback = object : IControlCallback.Stub() {
        override fun onStateChanged(payload: RuntimeSnapshotPayload) {
            mainHandler.post { listener.onRuntimeSnapshot(payload) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            bound = true
            val connected = IAppControl.Stub.asInterface(binder)
            service = connected
            try {
                connected.registerCallback(callback)
            } catch (exception: RemoteException) {
                disconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            disconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            disconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            disconnect()
        }
    }

    fun bind(): Boolean {
        if (binding || bound) {
            return true
        }
        binding = true
        val requested = context.bindService(
            Intent(context, AppControlService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!requested) {
            binding = false
            listener.onControlDisconnected()
        }
        return requested
    }

    fun unbind() {
        val current = service
        if (current != null) {
            try {
                current.unregisterCallback(callback)
            } catch (ignored: RemoteException) {
                // The service process may already be gone.
            }
        }
        service = null
        if (binding || bound) {
            try {
                context.unbindService(connection)
            } catch (ignored: IllegalArgumentException) {
                // The binding may already have been removed.
            }
        }
        binding = false
        bound = false
    }

    fun restart(result: ResultCallback) {
        val current = service ?: return unavailable(result)
        try {
            current.restartRuntime(operationCallback(result))
        } catch (exception: RemoteException) {
            failed(result, exception)
        }
    }

    fun importConfig(source: Uri, result: ResultCallback) {
        val current = service ?: return unavailable(result)
        try {
            current.importConfig(source, operationCallback(result))
        } catch (exception: RemoteException) {
            failed(result, exception)
        }
    }

    fun getNetworkSelectionCatalog(result: ResultCallback) {
        val current = service ?: return unavailable(result)
        try {
            current.getNetworkSelectionCatalog(operationCallback(result))
        } catch (exception: RemoteException) {
            failed(result, exception)
        }
    }

    fun setNetworkSelection(
        identity: String,
        group: String,
        target: String,
        result: ResultCallback,
    ) {
        val current = service ?: return unavailable(result)
        try {
            current.setNetworkSelection(identity, group, target, operationCallback(result))
        } catch (exception: RemoteException) {
            failed(result, exception)
        }
    }

    fun setRuntimeOverrides(
        processMatchingMode: ProcessMatchingMode?,
        ipv6Enabled: Boolean,
        logLevel: RuntimeLogLevel?,
        adaptiveTcpConcurrent: Boolean,
        webViewXhttp: Boolean,
        lanWebUiPublic: Boolean,
        result: ResultCallback,
    ) {
        val current = service ?: return unavailable(result)
        val level = logLevel ?: RuntimeLogLevel.WARNING
        val mode = processMatchingMode ?: ProcessMatchingMode.ALWAYS
        try {
            current.setRuntimeOverrides(
                mode.wireValue,
                ipv6Enabled,
                level.wireValue,
                adaptiveTcpConcurrent,
                webViewXhttp,
                lanWebUiPublic,
                operationCallback(result),
            )
        } catch (exception: RemoteException) {
            failed(result, exception)
        }
    }

    private fun unavailable(result: ResultCallback) {
        result.onComplete(false, "运行时控制服务暂不可用")
    }

    private fun failed(result: ResultCallback, exception: RemoteException) {
        disconnect()
        result.onComplete(false, Exceptions.usefulMessage(exception))
    }

    private fun operationCallback(result: ResultCallback): IOperationCallback =
        object : IOperationCallback.Stub() {
            override fun onComplete(success: Boolean, detail: String) {
                mainHandler.post { result.onComplete(success, detail) }
            }
        }

    private fun disconnect() {
        binding = false
        bound = false
        service = null
        mainHandler.post { listener.onControlDisconnected() }
    }
}
