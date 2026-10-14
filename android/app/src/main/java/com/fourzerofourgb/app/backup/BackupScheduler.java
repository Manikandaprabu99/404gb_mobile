package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.provider.MediaStore;
import android.util.Log;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * All WorkManager scheduling for backup. WorkManager persists these requests
 * and re-registers them after reboot (and after the app is next opened
 * following a force-stop), so no service or boot receiver is needed:
 *
 *  - media trigger: one-shot work with content URI triggers on the Images and
 *    Video collections; it re-arms itself every time it runs.
 *  - safety scan: periodic full rescan every 6 hours.
 *  - upload: the network/charging-constrained worker that drains the ledger.
 *  - face scan: People matching, hourly plus a one-time "soon"/follow-up run; cancelAll leaves it alone.
 */
final class BackupScheduler {

    static final String WORK_MEDIA_TRIGGER = "404gb-backup-media-trigger";
    static final String WORK_SAFETY_SCAN = "404gb-backup-safety-scan";
    static final String WORK_UPLOAD = "404gb-backup-upload";
    static final String WORK_FACE_SCAN = "404gb-faces-background";
    static final String WORK_FACE_SCAN_SOON = "404gb-faces-soon";

    private static final String TAG = "404gb.BackupScheduler";
    private static final String WORK_TAG = "404gb-backup";
    private static final String FACES_TAG = "404gb-faces";
    private static final long FACE_SCAN_INTERVAL_MINUTES = 60;

    private BackupScheduler() {}

    /** Makes sure the media trigger and safety scan exist, without resetting them. */
    static void ensureScheduled(Context context) {
        armMediaTrigger(context, ExistingWorkPolicy.KEEP);
        PeriodicWorkRequest safetyScan = new PeriodicWorkRequest.Builder(ScanWorker.class, 6, TimeUnit.HOURS)
            .setInputData(new Data.Builder().putBoolean(ScanWorker.KEY_FROM_MEDIA_TRIGGER, false).build())
            .addTag(WORK_TAG)
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_SAFETY_SCAN, ExistingPeriodicWorkPolicy.KEEP, safetyScan);
    }

    /**
     * Content-triggered scan. From inside a running trigger worker use
     * APPEND_OR_REPLACE: the replacement is chained after the current run
     * instead of cancelling it.
     */
    static void armMediaTrigger(Context context, ExistingWorkPolicy policy) {
        Constraints constraints = new Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScanWorker.class)
            .setConstraints(constraints)
            .setInputData(new Data.Builder().putBoolean(ScanWorker.KEY_FROM_MEDIA_TRIGGER, true).build())
            .addTag(WORK_TAG)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_MEDIA_TRIGGER, policy, request);
    }

    /**
     * Upload work with the user's Wi-Fi/charging choices. Like Google Photos it
     * never uses mobile data while roaming and pauses while the battery is low
     * (Android's own low-battery threshold), resuming by itself afterwards.
     */
    static void enqueueUpload(Context context, ExistingWorkPolicy policy, long delayMillis) {
        BackupPrefs prefs = BackupPrefs.get(context);
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(prefs.wifiOnly() ? NetworkType.UNMETERED : NetworkType.NOT_ROAMING)
            .setRequiresCharging(prefs.chargingOnly())
            .setRequiresBatteryNotLow(true)
            .build();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(UploadWorker.class)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG);
        if (delayMillis > 0) {
            builder.setInitialDelay(delayMillis, TimeUnit.MILLISECONDS);
        }
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_UPLOAD, policy, builder.build());
    }

    /**
     * User-initiated "go now": restarts queued upload work (skipping any backoff
     * or Retry-After delay) but never interrupts an upload that is running.
     */
    static void kickUpload(Context context) {
        if (isUploadRunning(context)) {
            return;
        }
        BackupPrefs.get(context).setResumeAt(0L);
        enqueueUpload(context, ExistingWorkPolicy.REPLACE, 0);
    }

    /**
     * Starts upload work afresh, replacing a running run (a new token or repo):
     * the old run stops at its next request instead of finishing with stale credentials.
     */
    static void restartUpload(Context context) {
        BackupPrefs.get(context).setResumeAt(0L);
        enqueueUpload(context, ExistingWorkPolicy.REPLACE, 0);
    }

    /** Stops upload work after sign-out; setAuth schedules it again. */
    static void cancelUpload(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_UPLOAD);
    }

    /** Ends the current run and starts a new one once GitHub's rate limit has passed. */
    static void resumeAfterRateLimit(Context context, long delayMillis) {
        BackupPrefs.get(context).setResumeAt(System.currentTimeMillis() + delayMillis);
        enqueueUpload(context, ExistingWorkPolicy.REPLACE, delayMillis);
    }

    /** The hourly People run while it is on and a library is set, else cancels all People work. UPDATE never interrupts a run. */
    static void updateFaceScan(Context context) {
        BackupPrefs prefs = BackupPrefs.get(context);
        WorkManager manager = WorkManager.getInstance(context);
        if (!faceScanAllowed(prefs)) {
            manager.cancelUniqueWork(WORK_FACE_SCAN);
            manager.cancelUniqueWork(WORK_FACE_SCAN_SOON);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(FaceScanWorker.class, FACE_SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(faceScanConstraints(prefs))
            .setInputData(faceScanInput(FaceScanWorker.TRIGGER_PERIODIC))
            .addTag(FACES_TAG)
            .build();
        manager.enqueueUniquePeriodicWork(WORK_FACE_SCAN, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /** A one-time People run about 90 s from now (app closed, or new photos backed up); KEEP. */
    static void faceScanSoon(Context context, String trigger) {
        enqueueFaceScanOnce(context, ExistingWorkPolicy.KEEP, FaceScanPolicy.SOON_DELAY_MS, trigger);
    }

    /** Another run after {@code delayMillis}, from inside a run: APPEND_OR_REPLACE chains it instead of cancelling this one. */
    static void faceScanFollowUp(Context context, long delayMillis) {
        enqueueFaceScanOnce(context, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMillis, FaceScanWorker.TRIGGER_FOLLOW_UP);
    }

    /** The app is on screen again: its own scanner takes over (a run going now is stopped). */
    static void cancelFaceScanSoon(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_FACE_SCAN_SOON);
    }

    /** A "soon" or follow-up run is queued (not running yet). */
    static boolean isFaceScanPending(Context context) {
        try {
            List<WorkInfo> infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_FACE_SCAN_SOON).get(2, TimeUnit.SECONDS);
            for (WorkInfo info : infos) {
                WorkInfo.State state = info.getState();
                if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
                    return true;
                }
            }
        } catch (ExecutionException | TimeoutException e) {
            Log.w(TAG, "Could not read People work state", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private static void enqueueFaceScanOnce(Context context, ExistingWorkPolicy policy, long delayMillis, String trigger) {
        BackupPrefs prefs = BackupPrefs.get(context);
        // GitHub refused the token: nothing to do until the app has a new sign-in.
        if (!faceScanAllowed(prefs) || prefs.needsSignIn()) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FaceScanWorker.class)
            .setConstraints(faceScanConstraints(prefs))
            .setInputData(faceScanInput(trigger))
            .setInitialDelay(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS)
            .addTag(FACES_TAG)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_FACE_SCAN_SOON, policy, request);
    }

    private static boolean faceScanAllowed(BackupPrefs prefs) {
        return prefs.faceScanEnabled() && prefs.canUpload();
    }

    /** Online (Wi-Fi if backup is Wi-Fi only), battery not low, charging only if "Only while charging". */
    private static Constraints faceScanConstraints(BackupPrefs prefs) {
        return new Constraints.Builder()
            .setRequiredNetworkType(prefs.wifiOnly() ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresCharging(prefs.faceScanChargingOnly())
            .setRequiresBatteryNotLow(true)
            .build();
    }

    private static Data faceScanInput(String trigger) {
        return new Data.Builder().putString(FaceScanWorker.KEY_TRIGGER, trigger).build();
    }

    static boolean isUploadRunning(Context context) {
        try {
            List<WorkInfo> infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_UPLOAD).get(5, TimeUnit.SECONDS);
            for (WorkInfo info : infos) {
                if (info.getState() == WorkInfo.State.RUNNING) {
                    return true;
                }
            }
        } catch (ExecutionException | TimeoutException e) {
            Log.w(TAG, "Could not read upload work state", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Earlier builds scheduled a periodic watchdog whose worker class no longer
     * exists; WorkManager persists it, so it would keep waking the app.
     */
    static void cancelLegacyWork(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork("404gb-photowatcher-watchdog");
    }

    static void cancelAll(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(WORK_MEDIA_TRIGGER);
        manager.cancelUniqueWork(WORK_SAFETY_SCAN);
        manager.cancelUniqueWork(WORK_UPLOAD);
    }
}
