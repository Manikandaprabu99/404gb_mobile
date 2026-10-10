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
 */
final class BackupScheduler {

    static final String WORK_MEDIA_TRIGGER = "404gb-backup-media-trigger";
    static final String WORK_SAFETY_SCAN = "404gb-backup-safety-scan";
    static final String WORK_UPLOAD = "404gb-backup-upload";

    private static final String TAG = "404gb.BackupScheduler";
    private static final String WORK_TAG = "404gb-backup";

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

    /** Upload work with the user's Wi-Fi/charging constraints. */
    static void enqueueUpload(Context context, ExistingWorkPolicy policy, long delayMillis) {
        BackupPrefs prefs = BackupPrefs.get(context);
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(prefs.wifiOnly() ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly())
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
        enqueueUpload(context, ExistingWorkPolicy.REPLACE, 0);
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
