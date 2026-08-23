package io.github.qwqgong.androidcyaml

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Serializes every transition that can replace the TUN or embedded mihomo runtime. */
class RuntimeCoordinator private constructor(context: Context) :
    RuntimeConfigTransactions.Lifecycle,
    RuntimeConfigTransactions.Host,
    NetworkReconciler.Host {

    fun interface OperationCallback {
        fun onComplete(success: Boolean, detail: String)
    }

    private data class RuntimeStartRequest(
        val settings: RuntimeOverrideSettings,
        val detailSuffix: String,
    )

    private val context: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val stateBus = RuntimeStateBus()
    private val fileStore = MihomoFileStore(this.context)
    private val configInstaller = ConfigInstaller(this.context, fileStore)
    private val overrideStore = RuntimeOverrideStore(this.context)
    private val selectorSession: SelectorSession
    private val lifecycle: RuntimeLifecycle
    private val networkReconciler: NetworkReconciler
    private val configTransactions: RuntimeConfigTransactions

    init {
        val networkMonitor = Ipv6EnvironmentMonitor(this.context)
        selectorSession = SelectorSession(NetworkSelectionStore(this.context), networkMonitor)
        lifecycle = RuntimeLifecycle(fileStore, selectorSession)
        networkReconciler = NetworkReconciler(
            networkMonitor,
            overrideStore,
            lifecycle,
            selectorSession,
            this,
        )
        configTransactions = RuntimeConfigTransactions(
            configInstaller,
            overrideStore,
            this,
            this,
        )
    }

    fun runtimeOverrideSettings(): RuntimeOverrideSettings = overrideStore.settings()

    override fun effectiveIpv6Enabled(): Boolean = lifecycle.effectiveIpv6Enabled

    fun addListener(listener: RuntimeStateBus.Listener) {
        stateBus.addListener(listener)
    }

    fun removeListener(listener: RuntimeStateBus.Listener) {
        stateBus.removeListener(listener)
    }

    fun start(requestedService: AndroidVpnService) {
        executor.execute { startInternal(requestedService) }
    }

    fun stop(requestedService: AndroidVpnService?, completion: Runnable?) {
        executor.execute {
            if (lifecycle.service() != null &&
                requestedService != null &&
                !lifecycle.ownsService(requestedService)
            ) {
                postCompletion(completion)
                return@execute
            }
            if (stateBus.snapshot().state != RuntimeState.STOPPED) {
                publish(RuntimeState.STOPPING, "正在停止 mihomo JNI TUN…")
            }
            cleanupAll()
            publish(RuntimeState.STOPPED, "VPN 未连接")
            postCompletion(completion)
        }
    }

    fun restart(callback: OperationCallback?) {
        executor.execute {
            if (!active()) {
                complete(callback, false, "VPN 未启动")
                return@execute
            }
            try {
                publish(RuntimeState.STARTING, "正在重启 mihomo JNI 核心…")
                publish(RuntimeState.RUNNING, start())
                complete(callback, true, "mihomo 已重启")
            } catch (exception: Exception) {
                val message = Exceptions.usefulMessage(exception)
                failActiveService("mihomo 重启失败：$message")
                complete(callback, false, message)
            }
        }
    }

    fun importConfig(source: Uri?, callback: OperationCallback?) {
        executor.execute { configTransactions.importConfig(source, callback) }
    }

    fun setRuntimeOverrides(
        processMatchingModeValue: String?,
        ipv6Enabled: Boolean,
        logLevelValue: String?,
        adaptiveTcpConcurrent: Boolean,
        webViewXhttp: Boolean,
        lanWebUiPublic: Boolean,
        callback: OperationCallback?,
    ) {
        val logLevel: RuntimeLogLevel
        val processMatchingMode: ProcessMatchingMode
        try {
            logLevel = RuntimeLogLevel.fromWireValue(logLevelValue)
            processMatchingMode = ProcessMatchingMode.fromWireValue(processMatchingModeValue)
        } catch (exception: IllegalArgumentException) {
            complete(callback, false, Exceptions.usefulMessage(exception))
            return
        }
        executor.execute {
            configTransactions.setRuntimeOverrides(
                RuntimeOverrideSettings(
                    processMatchingMode,
                    ipv6Enabled,
                    logLevel,
                    adaptiveTcpConcurrent,
                    webViewXhttp,
                    lanWebUiPublic,
                ),
                callback,
            )
        }
    }

    fun networkSelectionCatalog(callback: OperationCallback?) {
        executor.execute {
            val current = lifecycle.runtime()
            if (current == null) {
                complete(callback, false, "请先启动 VPN，再设置网络节点")
                return@execute
            }
            try {
                complete(callback, true, selectorSession.catalog(current))
            } catch (exception: IOException) {
                complete(callback, false, Exceptions.usefulMessage(exception))
            } catch (exception: JSONException) {
                complete(callback, false, Exceptions.usefulMessage(exception))
            }
        }
    }

    fun setNetworkSelection(
        identity: String?,
        group: String?,
        target: String?,
        callback: OperationCallback?,
    ) {
        executor.execute {
            val current = lifecycle.runtime()
            if (current == null) {
                complete(callback, false, "请先启动 VPN，再设置网络节点")
                return@execute
            }
            try {
                complete(callback, true, selectorSession.select(current, identity, group, target))
            } catch (exception: IOException) {
                complete(callback, false, Exceptions.usefulMessage(exception))
            }
        }
    }

    private fun startInternal(requestedService: AndroidVpnService) {
        val current = stateBus.snapshot().state
        if (lifecycle.ownsService(requestedService) &&
            (current == RuntimeState.STARTING || current == RuntimeState.RUNNING)
        ) {
            stateBus.publish(stateBus.snapshot())
            return
        }

        cleanupAll()
        try {
            val underlyingNetworkState = networkReconciler.start()
            publish(RuntimeState.STARTING, "正在启动同进程 mihomo JNI TUN…")
            val request = runtimeStartRequest()
            val detail = lifecycle.start(
                requestedService,
                request.settings,
                underlyingNetworkState,
                networkReconciler::cancelSelectionRestoration,
            ) + request.detailSuffix
            publish(RuntimeState.RUNNING, detail)
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            failActiveService("VPN 启动失败：" + Exceptions.usefulMessage(exception))
        }
    }

    override fun start(): String {
        val request = runtimeStartRequest()
        return lifecycle.restart(
            request.settings,
            networkReconciler.currentState(),
            networkReconciler::cancelSelectionRestoration,
        ) + request.detailSuffix
    }

    private fun runtimeStartRequest(): RuntimeStartRequest {
        var settings = overrideStore.settings()
        val localNetworkFallback = settings.lanWebUiPublic && !hasLocalNetworkAccess()
        if (localNetworkFallback) {
            settings = settings.copy(lanWebUiPublic = false)
            overrideStore.setSettings(settings)
        }
        return RuntimeStartRequest(
            settings,
            if (localNetworkFallback) " · 局域网权限未授权，WebUI 已恢复本机" else "",
        )
    }

    override fun active(): Boolean = lifecycle.hasActiveService()

    override fun setIdleEffectiveState(
        settings: RuntimeOverrideSettings,
        networkState: Ipv6EnvironmentMonitor.State,
    ) {
        lifecycle.setIdleEffectiveState(settings, networkState)
    }

    override fun effectiveTcpConcurrent(): Boolean = lifecycle.effectiveTcpConcurrent

    override fun controllerPort(): Int = lifecycle.runtime()?.controllerPort() ?: 0

    override fun hasLocalNetworkAccess(): Boolean =
        Build.VERSION.SDK_INT < ANDROID_17_API ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    override fun refreshUnderlyingNetworkState(): Ipv6EnvironmentMonitor.State =
        networkReconciler.refreshState()

    override fun failActiveService(message: String) {
        val failedService = lifecycle.service()
        cleanupAll()
        publish(RuntimeState.FAILED, message)
        if (failedService != null) {
            mainHandler.post { failedService.onCoordinatorFailure(message) }
        }
    }

    private fun cleanupAll() {
        networkReconciler.stop()
        lifecycle.stop()
    }

    override fun submit(operation: Runnable) {
        executor.execute(operation)
    }

    override fun snapshot(): RuntimeSnapshot = stateBus.snapshot()

    override fun publish(snapshot: RuntimeSnapshot) {
        stateBus.publish(snapshot)
    }

    override fun publish(state: RuntimeState, detail: String) {
        val current = lifecycle.runtime()
        val running = state == RuntimeState.RUNNING && current != null
        stateBus.publish(
            RuntimeSnapshot(
                state,
                detail,
                if (running) current!!.dashboardUrl() else "",
                if (running) current!!.controllerPort() else 0,
            ),
        )
    }

    override fun complete(callback: OperationCallback?, success: Boolean, detail: String) {
        if (callback != null) {
            mainHandler.post { callback.onComplete(success, detail) }
        }
    }

    private fun postCompletion(completion: Runnable?) {
        if (completion != null) {
            mainHandler.post(completion)
        }
    }

    companion object {
        private const val TAG = "AndroidCyaml/Coordinator"
        private const val ANDROID_17_API = 37

        @Volatile
        private var instance: RuntimeCoordinator? = null

        fun getInstance(context: Context): RuntimeCoordinator {
            instance?.let { return it }
            synchronized(RuntimeCoordinator::class.java) {
                val existing = instance
                if (existing != null) {
                    return existing
                }
                val created = RuntimeCoordinator(context)
                instance = created
                return created
            }
        }

        fun trimMemoryCachesIfCreated(): Int {
            // Do not initialize the large native core merely because Android asks
            // an otherwise idle service process to trim memory.
            val current = instance?.lifecycle?.runtime()
            return current?.trimRebuildableCaches() ?: 0
        }

        fun persistStateForMemoryKill(): Boolean {
            val local = instance
            if (local == null || local.lifecycle.runtime() == null) {
                return true
            }
            val checkpoint = local.executor.submit<Boolean> {
                local.selectorSession.checkpoint(local.lifecycle.runtime())
            }
            return try {
                checkpoint.get(2L, TimeUnit.SECONDS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (exception: ExecutionException) {
                Log.w(TAG, "Unable to persist selector choices before memory kill", exception)
                false
            } catch (exception: TimeoutException) {
                Log.w(TAG, "Unable to persist selector choices before memory kill", exception)
                false
            }
        }
    }
}
