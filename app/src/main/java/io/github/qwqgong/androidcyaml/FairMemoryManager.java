package io.github.qwqgong.androidcyaml;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/** Implements the memory-pressure callback protocol documented by the Android Gold Alliance. */
final class FairMemoryManager {
    static final String ACTION_TRIM = "itgsa.intent.action.TRIM";
    static final String ACTION_KILL = "itgsa.intent.action.KILL";

    private static final String TAG = "AndroidCyaml/Memory";
    private static final String EXTRA_COMMON = "common";
    private static final String EXTRA_DETAIL = "extra";
    private static final String KEY_NOTIFY_TYPE = "notifyType";
    private static final String KEY_NOTIFY_ID = "notifyId";
    private static final String KEY_REASON = "reason";
    private static final String KEY_ACTION = "action";
    private static final String KEY_CALLBACK = "callback";
    private static final String KEY_REPLY = "reply";
    private static final int RESULT_HANDLED = 0;
    private static final int RESULT_NOT_HANDLED = 1;
    private static final long PSS_SAMPLE_INTERVAL_MILLIS = 30_000L;

    private final Context context;
    private final HandlerThread workerThread;
    private final Handler worker;
    private long lastPssSampleElapsed;
    private long lastPssKb = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            handleRequest(intent);
        }
    };

    private FairMemoryManager(Context context) {
        this.context = context.getApplicationContext();
        workerThread = new HandlerThread("AndroidCyaml-fair-memory");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    static FairMemoryManager start(Context context) {
        FairMemoryManager manager = new FairMemoryManager(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TRIM);
        filter.addAction(ACTION_KILL);
        try {
            manager.context.registerReceiver(
                    manager.receiver,
                    filter,
                    null,
                    manager.worker,
                    Context.RECEIVER_EXPORTED
            );
            Log.i(TAG, "Fair-memory callbacks enabled in the service process");
            return manager;
        } catch (RuntimeException exception) {
            manager.workerThread.quitSafely();
            Log.w(TAG, "Unable to register fair-memory callbacks", exception);
            return null;
        }
    }

    static int releaseLocalCaches() {
        return RuntimeCoordinator.trimMemoryCachesIfCreated();
    }

    private void handleRequest(Intent intent) {
        String receivedAction = intent == null ? null : intent.getAction();
        int notifyType = -1;
        int notifyId = -1;
        String reason = "";
        String requestedAction = "";
        IBinder callback = null;
        Bundle detail = null;
        boolean handled = ACTION_TRIM.equals(receivedAction) || ACTION_KILL.equals(receivedAction);
        try {
            Bundle common = intent == null ? null : intent.getBundleExtra(EXTRA_COMMON);
            detail = intent == null ? null : intent.getBundleExtra(EXTRA_DETAIL);
            if (common == null) {
                throw new IllegalArgumentException("missing common bundle");
            }
            notifyType = common.getInt(KEY_NOTIFY_TYPE, -1);
            notifyId = common.getInt(KEY_NOTIFY_ID, -1);
            reason = safeString(common, KEY_REASON);
            requestedAction = safeString(common, KEY_ACTION);
            callback = common.getBinder(KEY_CALLBACK);
            if (callback == null) {
                throw new IllegalArgumentException("missing callback binder");
            }

            int clearedLogLines = handled ? releaseLocalCaches() : 0;
            boolean statePersisted = !ACTION_KILL.equals(receivedAction)
                    || RuntimeCoordinator.persistStateForMemoryKill();
            handled = handled && statePersisted;
            MemorySnapshot snapshot = snapshotMemory();
            Log.i(
                    TAG,
                    "action=" + receivedAction
                            + " notifyType=" + notifyType
                            + " notifyId=" + notifyId
                            + " reason=" + reason
                            + " request=" + requestedAction
                            + " heap=" + snapshot.heapAllocatedKb + "/"
                            + snapshot.heapCapacityKb + " KiB"
                            + " pss=" + snapshot.pssKb + " KiB"
                            + suppliedLimits(detail)
                            + " clearedLogLines=" + clearedLogLines
                            + " statePersisted=" + statePersisted
            );
        } catch (RuntimeException exception) {
            handled = false;
            Log.w(TAG, "Malformed fair-memory request", exception);
        }

        sendReply(
                callback,
                notifyType,
                notifyId,
                handled ? RESULT_HANDLED : RESULT_NOT_HANDLED,
                handled
                        ? "released reclaimable caches; persistent state is already on disk"
                        : "unsupported or malformed memory request"
        );
    }

    private MemorySnapshot snapshotMemory() {
        Runtime runtime = Runtime.getRuntime();
        long allocatedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L;
        long capacityKb = runtime.maxMemory() / 1024L;
        long now = SystemClock.elapsedRealtime();
        if (lastPssKb < 0 || now - lastPssSampleElapsed >= PSS_SAMPLE_INTERVAL_MILLIS) {
            lastPssKb = Debug.getPss();
            lastPssSampleElapsed = now;
        }
        return new MemorySnapshot(allocatedKb, capacityKb, lastPssKb);
    }

    private static String suppliedLimits(Bundle detail) {
        if (detail == null) {
            return "";
        }
        int pssKb = detail.getInt("pss", -1);
        int pssLimitKb = detail.getInt("pssLimit", -1);
        int heapAllocatedKb = detail.getInt("heapAlloc", -1);
        int heapCapacityKb = detail.getInt("heapCapacity", -1);
        return " suppliedPss=" + pssKb + "/" + pssLimitKb + " KiB"
                + " suppliedHeap=" + heapAllocatedKb + "/" + heapCapacityKb + " KiB";
    }

    private static String safeString(Bundle bundle, String key) {
        String value = bundle.getString(key);
        return value == null ? "" : value;
    }

    private static void sendReply(
            IBinder callback,
            int notifyType,
            int notifyId,
            int result,
            String message
    ) {
        if (callback == null) {
            Log.w(TAG, "Fair-memory request omitted its Binder callback");
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInt(notifyType);
            data.writeInt(notifyId);
            data.writeInt(result);
            Bundle extra = new Bundle();
            extra.putString(KEY_REPLY, message);
            data.writeBundle(extra);
            callback.transact(
                    IBinder.FIRST_CALL_TRANSACTION,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY
            );
        } catch (RemoteException | RuntimeException exception) {
            Log.w(TAG, "Unable to return fair-memory callback", exception);
        } finally {
            data.recycle();
        }
    }

    private record MemorySnapshot(long heapAllocatedKb, long heapCapacityKb, long pssKb) {}
}
