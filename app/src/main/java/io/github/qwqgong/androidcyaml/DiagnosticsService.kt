package io.github.qwqgong.androidcyaml

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Owns the diagnostics log, in its own process.
 *
 * The sampler used to live in the proxy process, next to the runtime it was
 * measuring. That made it share the VPN's lifetime, which is the one thing a
 * logger must not do: a core restart or a low-memory kill is exactly when the
 * evidence matters, and a logger that dies with its subject has none of it. It
 * also made the log file have as many potential writers as there were
 * processes, and two writers rotating two generations lose data by design.
 *
 * So this process holds the file and the clock, and owns no runtime at all.
 * Once a minute it asks the proxy process for one payload -- the core's
 * metrics, the core's own log lines, and whatever the other processes recorded
 * through [DiagnosticsRelay] -- and writes it. One binder round trip a minute,
 * rather than one per event.
 */
class DiagnosticsService : Service() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var control: IAppControl? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            control = IAppControl.Stub.asInterface(binder)
            DiagnosticsLog.append(this@DiagnosticsService, "log.bound", "")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The proxy process died. That is itself the interesting event, and
            // recording it is the reason this process is separate.
            control = null
            DiagnosticsLog.append(this@DiagnosticsService, "log.proxy.lost", "")
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.setEnabled(UiPreferences(this).diagnosticsEnabled())
        val thread = HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        worker = thread
        handler = Handler(thread.looper)

        bindService(
            Intent(this, AppControlService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        DiagnosticsLog.append(this, "log.start", "interval=$SAMPLE_INTERVAL_MILLIS")
        scheduleNext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        try {
            unbindService(connection)
        } catch (ignored: IllegalArgumentException) {
            // Never bound, or already unbound.
        }
        DiagnosticsLog.append(this, "log.stop", "")
        worker?.quitSafely()
        worker = null
        handler = null
        super.onDestroy()
    }

    private fun scheduleNext() {
        handler?.postDelayed({
            collect()
            scheduleNext()
        }, SAMPLE_INTERVAL_MILLIS)
    }

    private fun collect() {
        if (!DiagnosticsLog.isEnabled()) {
            return
        }
        val remote = control
        if (remote == null) {
            DiagnosticsLog.append(this, "log.unbound", "")
            return
        }
        try {
            remote.captureDiagnostics(object : IOperationCallback.Stub() {
                override fun onComplete(success: Boolean, detail: String?) {
                    if (!success || detail.isNullOrEmpty()) {
                        DiagnosticsLog.append(
                            this@DiagnosticsService,
                            "log.capture.failed",
                            detail.orEmpty(),
                        )
                        return
                    }
                    write(detail)
                }
            })
        } catch (failure: RemoteException) {
            DiagnosticsLog.append(this, "log.capture.failed", failure.javaClass.simpleName)
        }
    }

    /**
     * Writes one captured payload. The relayed lines already carry the clocks
     * from when they happened, so they go in verbatim; only the sample line is
     * stamped here, and it is written first so the counters precede the events
     * of the window they summarise.
     */
    private fun write(payload: String) {
        val document = try {
            JSONObject(payload)
        } catch (failure: JSONException) {
            DiagnosticsLog.append(this, "log.capture.malformed", failure.javaClass.simpleName)
            return
        }
        document.optString("sample").takeIf { it.isNotEmpty() }?.let {
            DiagnosticsLog.append(this, "sample", it)
        }
        appendAll(document.optJSONArray("relayed"))
        appendAll(document.optJSONArray("coreLog"), "core")
        val droppedCore = document.optLong("coreLogDropped", 0L)
        if (droppedCore > 0L) {
            DiagnosticsLog.append(this, "core.dropped", "lines=$droppedCore")
        }
    }

    private fun appendAll(lines: JSONArray?, event: String? = null) {
        if (lines == null) {
            return
        }
        for (index in 0 until lines.length()) {
            val line = lines.optString(index, "")
            if (line.isEmpty()) {
                continue
            }
            if (event == null) {
                DiagnosticsLog.appendVerbatim(this, line)
            } else {
                DiagnosticsLog.append(this, event, line)
            }
        }
    }

    companion object {
        private const val TAG = "AndroidCyaml/LogSvc"
        private const val SAMPLE_INTERVAL_MILLIS = 60_000L

        /** Fits the 15-character `comm` limit and is unique at that length. */
        const val WORKER_THREAD_NAME = "acy-log-worker"

        fun start(context: Context) {
            try {
                context.startService(Intent(context, DiagnosticsService::class.java))
            } catch (failure: IllegalStateException) {
                // Background start refused. The next foreground transition
                // retries; log mode is not worth crashing the proxy for.
                Log.w(TAG, "Unable to start the diagnostics process", failure)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DiagnosticsService::class.java))
        }
    }
}
