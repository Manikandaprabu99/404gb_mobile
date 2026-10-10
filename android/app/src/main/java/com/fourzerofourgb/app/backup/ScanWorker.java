package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Runs when photos/videos change (content URI trigger) and every 6 hours
 * (safety scan): records new items in the ledger and hands off to the upload
 * worker. Works with the app closed; WorkManager starts the process for it.
 */
public final class ScanWorker extends Worker {

    static final String KEY_FROM_MEDIA_TRIGGER = "fromMediaTrigger";

    private static final String TAG = "404gb.ScanWorker";

    public ScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        if (!prefs.isEnabled()) {
            return Result.success();
        }
        boolean fromTrigger = getInputData().getBoolean(KEY_FROM_MEDIA_TRIGGER, false);
        // Re-arm first so changes made while this run scans still fire the next trigger.
        BackupScheduler.armMediaTrigger(context, fromTrigger ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP);
        if (!fromTrigger && prefs.needsSignIn()) {
            // The periodic scan also retries a lapsed sign-in once (one cheap 401 at worst).
            prefs.setNeedsSignIn(false);
        }

        BackupStatus.scanStarted(context);
        try {
            MediaScanner.scan(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Media scan failed", e);
        } finally {
            BackupStatus.scanFinished(context);
        }

        // Always hand off: the upload worker rescans when it starts, which also
        // covers items added between this scan and the trigger being re-armed.
        if (prefs.isEnabled()) {
            BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.KEEP, 0);
        }
        return Result.success();
    }
}
