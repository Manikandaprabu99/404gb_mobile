package com.fourzerofourgb.app.backup;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains the ledger newest-first without needing the WebView. While uploading
 * it asks to run as a dataSync foreground service with a progress
 * notification; if Android refuses (background start limits) it keeps going
 * as a normal worker, and if it is stopped the ledger lets the next run
 * resume where this one left off.
 */
public final class UploadWorker extends Worker {

    private static final String TAG = "404gb.UploadWorker";
    private static final long NOTIFICATION_INTERVAL_MS = 1000;
    private static final long FOREGROUND_RETRY_MS = 30_000;

    private boolean foreground;
    private long lastForegroundAttempt;
    private long lastNotificationAt;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        BackupLedger ledger = BackupLedger.get(context);
        // Off, or waiting for the user to sign in again (backupNow / app launch clears it).
        if (!prefs.isEnabled() || prefs.needsSignIn()) {
            return Result.success();
        }
        if (!MediaAccess.hasFullAccess(context)) {
            BackupStatus.changed(context);
            return Result.success();
        }

        ledger.resetInterrupted();
        scan(context);

        ApiClient api = new ApiClient(context, this::isStopped);
        api.setWaitListener(waiting -> BackupStatus.setRateLimited(context, waiting));
        MediaUploader uploader = new MediaUploader(context, ledger, api, this::isStopped);
        Set<String> skip = new HashSet<>();
        boolean rescanned = false;
        int processed = 0;

        BackupStatus.setUploading(context, true);
        try {
            while (!isStopped() && prefs.isEnabled()) {
                BackupLedger.Item item = ledger.nextPending(skip);
                if (item == null) {
                    // Catch anything added while uploading before calling it done.
                    if (rescanned) {
                        break;
                    }
                    rescanned = true;
                    scan(context);
                    continue;
                }
                rescanned = false;

                final int index = processed + 1;
                final int total = processed + ledger.pendingCount();
                updateForeground(context, item.displayName, index, total, 0, item.size, true);
                MediaUploader.Outcome outcome = uploader.upload(item, (name, done, size) -> {
                    BackupStatus.setCurrent(context, name, done, size);
                    updateForeground(context, name, index, total, done, size, false);
                });
                processed++;
                if (outcome == MediaUploader.Outcome.FAILED) {
                    // Don't hammer a failing item in the same run; it stays queued (or parked as failed).
                    skip.add(item.key());
                }
                BackupStatus.clearCurrent(context);
            }
        } catch (ApiClient.AuthRequiredException e) {
            ledger.resetInterrupted();
            prefs.setNeedsSignIn(true);
            if (!prefs.signInNotified()) {
                BackupNotifications.showSignInNeeded(context);
                prefs.setSignInNotified(true);
            }
            return Result.success();
        } catch (ApiClient.RateLimitedException e) {
            ledger.resetInterrupted();
            // Replaces (and so ends) this run with one that starts after Retry-After.
            BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.REPLACE, e.retryAfterSeconds * 1000L);
            return Result.success();
        } catch (IOException e) {
            // Network loss, server errors after retries, or WorkManager stopped us.
            Log.w(TAG, "Upload run interrupted; will retry", e);
            ledger.resetInterrupted();
            return Result.retry();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unexpected backup failure", e);
            ledger.resetInterrupted();
            return Result.retry();
        } finally {
            BackupStatus.setUploading(context, false);
            BackupStatus.setRateLimited(context, false);
            BackupStatus.clearCurrent(context);
        }

        if (isStopped()) {
            ledger.resetInterrupted();
            return Result.retry();
        }
        return Result.success();
    }

    private static void scan(Context context) {
        BackupStatus.scanStarted(context);
        try {
            MediaScanner.scan(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Media scan failed", e);
        } finally {
            BackupStatus.scanFinished(context);
        }
    }

    /** Promotes the worker to a foreground service (retrying now and then) and refreshes its notification. */
    private void updateForeground(Context context, String name, int index, int total, long done, long size, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (foreground) {
            if (!force && now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) {
                return;
            }
            lastNotificationAt = now;
            // Updates reuse the running service; failures here are harmless.
            setForegroundAsync(foregroundInfo(context, name, index, total, done, size));
            return;
        }
        if (lastForegroundAttempt != 0 && now - lastForegroundAttempt < FOREGROUND_RETRY_MS) {
            return;
        }
        lastForegroundAttempt = now;
        lastNotificationAt = now;
        try {
            setForegroundAsync(foregroundInfo(context, name, index, total, done, size)).get(10, TimeUnit.SECONDS);
            foreground = true;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app in background and
            // not exempt from battery optimization) or similar: carry on as a regular worker.
            Log.i(TAG, "Running without a foreground service: " + e);
            foreground = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ForegroundInfo foregroundInfo(Context context, String name, int index, int total, long done, long size) {
        Notification notification = BackupNotifications.progress(context, index, total, name, done, size);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(BackupNotifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(BackupNotifications.ID_PROGRESS, notification);
    }
}
