package io.github.qwqgong.androidcyaml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log

/** Implements the memory-pressure callback protocol documented by the Android Gold Alliance. */
class FairMemoryManager private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private val workerThread = HandlerThread("AndroidCyaml-fair-memory")
    private val worker: Handler

    private var lastPssSampleElapsed = 0L
    private var lastPssKb = -1L
    private var lastHandledElapsed = Long.MIN_VALUE

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ignored: Context, intent: Intent) {
            handleRequest(intent)
        }
    }

    init {
        workerThread.start()
        worker = Handler(workerThread.looper)
    }

    private fun handleRequest(intent: Intent?) {
        val receivedAction = intent?.action
        var notifyType = -1
        var notifyId = -1
        var reason = ""
        var requestedAction = ""
        var callback: IBinder? = null
        var handled = ACTION_TRIM == receivedAction || ACTION_KILL == receivedAction
        try {
            val common = intent?.getBundleExtra(EXTRA_COMMON)
                ?: throw IllegalArgumentException("missing common bundle")
            val detail = intent.getBundleExtra(EXTRA_DETAIL)
            notifyType = common.getInt(KEY_NOTIFY_TYPE, -1)
            notifyId = common.getInt(KEY_NOTIFY_ID, -1)
            reason = safeString(common, KEY_REASON)
            requestedAction = safeString(common, KEY_ACTION)
            callback = common.getBinder(KEY_CALLBACK)
                ?: throw IllegalArgumentException("missing callback binder")

            val now = SystemClock.elapsedRealtime()
            val throttled = now - lastHandledElapsed < MIN_HANDLE_INTERVAL_MILLIS
            var clearedCacheGroups = 0
            var statePersisted = true
            if (handled && !throttled) {
                lastHandledElapsed = now
                clearedCacheGroups = releaseLocalCaches()
                statePersisted = ACTION_KILL != receivedAction ||
                    RuntimeCoordinator.persistStateForMemoryKill()
                handled = statePersisted
            }
            val snapshot = snapshotMemory()
            DiagnosticsLog.append(
                context,
                "memory." + (if (ACTION_KILL == receivedAction) "kill" else "trim"),
                "reason=" + DiagnosticsLog.oneLine(reason) +
                    " request=" + DiagnosticsLog.oneLine(requestedAction) +
                    " throttled=" + throttled +
                    " handled=" + handled +
                    " clearedCacheGroups=" + clearedCacheGroups +
                    " javaHeapKb=" + snapshot.heapAllocatedKb +
                    " pssKb=" + snapshot.pssKb,
            )
            Log.i(
                TAG,
                "action=" + receivedAction +
                    " notifyType=" + notifyType +
                    " notifyId=" + notifyId +
                    " reason=" + reason +
                    " request=" + requestedAction +
                    " heap=" + snapshot.heapAllocatedKb + "/" +
                    snapshot.heapCapacityKb + " KiB" +
                    " pss=" + snapshot.pssKb + " KiB" +
                    suppliedLimits(detail) +
                    " throttled=" + throttled +
                    " clearedCacheGroups=" + clearedCacheGroups +
                    " statePersisted=" + statePersisted,
            )
        } catch (exception: RuntimeException) {
            handled = false
            Log.w(TAG, "Malformed fair-memory request", exception)
        }

        sendReply(
            callback,
            notifyType,
            notifyId,
            if (handled) RESULT_HANDLED else RESULT_NOT_HANDLED,
            if (handled) {
                "released reclaimable caches; persistent state is already on disk"
            } else {
                "unsupported or malformed memory request"
            },
        )
    }

    private fun snapshotMemory(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val allocatedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val capacityKb = runtime.maxMemory() / 1024L
        val now = SystemClock.elapsedRealtime()
        if (lastPssKb < 0 || now - lastPssSampleElapsed >= PSS_SAMPLE_INTERVAL_MILLIS) {
            lastPssKb = Debug.getPss()
            lastPssSampleElapsed = now
        }
        return MemorySnapshot(allocatedKb, capacityKb, lastPssKb)
    }

    private data class MemorySnapshot(
        val heapAllocatedKb: Long,
        val heapCapacityKb: Long,
        val pssKb: Long,
    )

    companion object {
        const val ACTION_TRIM = "itgsa.intent.action.TRIM"
        const val ACTION_KILL = "itgsa.intent.action.KILL"

        private const val TAG = "AndroidCyaml/Memory"
        private const val EXTRA_COMMON = "common"
        private const val EXTRA_DETAIL = "extra"
        private const val KEY_NOTIFY_TYPE = "notifyType"
        private const val KEY_NOTIFY_ID = "notifyId"
        private const val KEY_REASON = "reason"
        private const val KEY_ACTION = "action"
        private const val KEY_CALLBACK = "callback"
        private const val KEY_REPLY = "reply"
        private const val RESULT_HANDLED = 0
        private const val RESULT_NOT_HANDLED = 1
        private const val PSS_SAMPLE_INTERVAL_MILLIS = 30_000L

        // This receiver is RECEIVER_EXPORTED by design (the OEM memory-pressure
        // subsystem is a different process/app), so any app on the device can
        // send TRIM/KILL. Bound how often a burst can actually trigger real work
        // instead of relying on the sender to behave.
        private const val MIN_HANDLE_INTERVAL_MILLIS = 200L

        fun start(context: Context): FairMemoryManager? {
            val manager = FairMemoryManager(context)
            val filter = IntentFilter()
            filter.addAction(ACTION_TRIM)
            filter.addAction(ACTION_KILL)
            return try {
                manager.context.registerReceiver(
                    manager.receiver,
                    filter,
                    null,
                    manager.worker,
                    Context.RECEIVER_EXPORTED,
                )
                Log.i(TAG, "Fair-memory callbacks enabled in the service process")
                manager
            } catch (exception: RuntimeException) {
                manager.workerThread.quitSafely()
                Log.w(TAG, "Unable to register fair-memory callbacks", exception)
                null
            }
        }

        fun releaseLocalCaches(): Int = RuntimeCoordinator.trimMemoryCachesIfCreated()

        private fun suppliedLimits(detail: Bundle?): String {
            if (detail == null) {
                return ""
            }
            val pssKb = detail.getInt("pss", -1)
            val pssLimitKb = detail.getInt("pssLimit", -1)
            val heapAllocatedKb = detail.getInt("heapAlloc", -1)
            val heapCapacityKb = detail.getInt("heapCapacity", -1)
            return " suppliedPss=$pssKb/$pssLimitKb KiB" +
                " suppliedHeap=$heapAllocatedKb/$heapCapacityKb KiB"
        }

        private fun safeString(bundle: Bundle, key: String): String = bundle.getString(key) ?: ""

        private fun sendReply(
            callback: IBinder?,
            notifyType: Int,
            notifyId: Int,
            result: Int,
            message: String,
        ) {
            if (callback == null) {
                Log.w(TAG, "Fair-memory request omitted its Binder callback")
                return
            }
            val data = Parcel.obtain()
            try {
                data.writeInt(notifyType)
                data.writeInt(notifyId)
                data.writeInt(result)
                val extra = Bundle()
                extra.putString(KEY_REPLY, message)
                data.writeBundle(extra)
                callback.transact(
                    IBinder.FIRST_CALL_TRANSACTION,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY,
                )
            } catch (exception: RemoteException) {
                Log.w(TAG, "Unable to return fair-memory callback", exception)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Unable to return fair-memory callback", exception)
            } finally {
                data.recycle()
            }
        }
    }
}
