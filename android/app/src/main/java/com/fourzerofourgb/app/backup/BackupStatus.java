package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import com.getcapacitor.JSObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Builds the BackupStatus object the web app sees (ledger + prefs + what the
 * workers are doing right now) and pushes "statusChanged" to the live plugin,
 * throttled to a few updates per second.
 */
final class BackupStatus {

    static final String STATE_OFF = "off";
    static final String STATE_IDLE = "idle";
    static final String STATE_SCANNING = "scanning";
    static final String STATE_UPLOADING = "uploading";
    static final String STATE_WAITING = "waiting";
    static final String STATE_NEEDS_SIGN_IN = "needs_sign_in";
    static final String STATE_NEEDS_PERMISSION = "needs_permission";

    private static final String TAG = "404gb.BackupStatus";
    private static final long MIN_EMIT_INTERVAL_MS = 250;

    // Runtime activity of the workers. WorkManager runs them in the app process,
    // so plain statics are the source of truth for "what is happening now".
    private static final AtomicInteger activeScans = new AtomicInteger();
    private static volatile boolean uploading;
    private static volatile boolean rateLimited;
    private static volatile String currentName;
    private static volatile long currentDone;
    private static volatile long currentTotal;

    private static final ScheduledExecutorService EMITTER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "404gb-backup-status");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean emitScheduled = new AtomicBoolean(false);
    private static volatile long lastEmitAt;
    // Only read and written on the EMITTER thread.
    private static String lastEmittedJson;

    private BackupStatus() {}

    static void scanStarted(Context context) {
        activeScans.incrementAndGet();
        changed(context);
    }

    static void scanFinished(Context context) {
        activeScans.decrementAndGet();
        changed(context);
    }

    static void setUploading(Context context, boolean value) {
        uploading = value;
        changed(context);
    }

    static void setRateLimited(Context context, boolean value) {
        rateLimited = value;
        changed(context);
    }

    static void setCurrent(Context context, String name, long bytesDone, long bytesTotal) {
        currentName = name == null ? "" : name;
        currentDone = bytesDone;
        currentTotal = bytesTotal;
        changed(context);
    }

    static void clearCurrent(Context context) {
        currentName = null;
        currentDone = 0;
        currentTotal = 0;
        changed(context);
    }

    static JSObject build(Context context) {
        Context app = context.getApplicationContext();
        BackupPrefs prefs = BackupPrefs.get(app);
        BackupLedger.Counts counts = BackupLedger.get(app).counts();

        JSObject status = new JSObject();
        status.put("enabled", prefs.isEnabled());
        status.put("wifiOnly", prefs.wifiOnly());
        status.put("chargingOnly", prefs.chargingOnly());
        status.put("folders", prefs.folders());
        status.put("state", computeState(app, prefs, counts));
        status.put("pending", counts.pending);
        status.put("uploaded", counts.uploaded);
        status.put("skippedDuplicates", counts.duplicates);
        status.put("failed", counts.failed);

        String name = currentName;
        if (name != null && uploading) {
            JSObject current = new JSObject();
            current.put("name", name);
            current.put("bytesDone", currentDone);
            current.put("bytesTotal", currentTotal);
            status.put("current", current);
        } else {
            status.put("current", JSONObject.NULL);
        }

        long last = prefs.lastBackupAt();
        if (last > 0) {
            status.put("lastBackupAt", last);
        } else {
            status.put("lastBackupAt", JSONObject.NULL);
        }
        status.put("batteryOptimized", isBatteryOptimized(app));
        return status;
    }

    private static String computeState(Context context, BackupPrefs prefs, BackupLedger.Counts counts) {
        if (!prefs.isEnabled()) {
            return STATE_OFF;
        }
        if (!MediaAccess.hasFullAccess(context)) {
            return STATE_NEEDS_PERMISSION;
        }
        if (prefs.needsSignIn()) {
            return STATE_NEEDS_SIGN_IN;
        }
        if (rateLimited) {
            return STATE_WAITING;
        }
        if (uploading) {
            return STATE_UPLOADING;
        }
        if (activeScans.get() > 0) {
            return STATE_SCANNING;
        }
        // Work is queued but not running: blocked on Wi-Fi/charging, backoff or a rate limit.
        if (counts.pending > 0) {
            return STATE_WAITING;
        }
        return STATE_IDLE;
    }

    static boolean isBatteryOptimized(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || !power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Schedules a (throttled) statusChanged event if the app's bridge is alive. */
    static void changed(Context context) {
        if (BackupPlugin.live() == null) {
            return;
        }
        if (!emitScheduled.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        long delay = Math.max(0L, lastEmitAt + MIN_EMIT_INTERVAL_MS - SystemClock.elapsedRealtime());
        EMITTER.schedule(() -> emit(app), delay, TimeUnit.MILLISECONDS);
    }

    /** Makes the next emit go out even if the status equals the last one sent (new bridge). */
    static void resetLastEmitted() {
        EMITTER.execute(() -> lastEmittedJson = null);
    }

    private static void emit(Context app) {
        // Cleared before building so changes made meanwhile schedule another emit.
        emitScheduled.set(false);
        lastEmitAt = SystemClock.elapsedRealtime();
        BackupPlugin plugin = BackupPlugin.live();
        if (plugin == null) {
            return;
        }
        JSObject status;
        try {
            status = build(app);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not build backup status", e);
            return;
        }
        String json = status.toString();
        if (json.equals(lastEmittedJson)) {
            return;
        }
        lastEmittedJson = json;
        plugin.emitStatusChanged(status);
    }
}
