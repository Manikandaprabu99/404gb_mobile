package com.fourzerofourgb.app.backup;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.getcapacitor.JSArray;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains the ledger newest-first without needing the WebView, talking to
 * GitHub directly with the stored token. Prepared items are committed in small
 * batches: one tree, commit and branch update per batch instead of per item
 * stretches GitHub's ~500 content-creating requests an hour further. While
 * uploading it asks to run as a dataSync foreground service with a progress
 * notification; if Android refuses (background start limits) it keeps going
 * as a normal worker, and if it is stopped the ledger lets the next run resume
 * where this one left off.
 */
public final class UploadWorker extends Worker {

    private static final String TAG = "404gb.UploadWorker";
    private static final long NOTIFICATION_INTERVAL_MS = 1000;
    private static final long FOREGROUND_RETRY_MS = 30_000;
    private static final long RECENT_SCAN_MS = 60_000;
    private static final int MAX_BATCH_OPS = 10;
    private static final long MAX_BATCH_WAIT_MS = 45_000;
    // Big files are committed on their own right away, so an interruption never
    // means hashing a long video again just to commit it.
    private static final long LARGE_ITEM_BYTES = 32L * 1024 * 1024;

    private boolean foreground;
    private long lastForegroundAttempt;
    private long lastNotificationAt;

    private Context context;
    private BackupPrefs prefs;
    private BackupLedger ledger;
    private LibraryWriter library;
    private MediaUploader uploader;
    private final Set<String> skip = new HashSet<>();
    private final Set<String> rejectedOnce = new HashSet<>();
    private final List<LibraryWriter.Op> batch = new ArrayList<>();
    private long batchStartedAt;
    private boolean batchHasLargeItem;

    private final MediaUploader.Batch pendingBatch = new MediaUploader.Batch() {
        @Override
        public boolean hasFile(String fileHash) {
            for (LibraryWriter.Op op : batch) {
                if (op instanceof LibraryWriter.NewItem && op.fileHash.equals(fileHash)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void commitNow() throws IOException {
            commitBatch();
        }
    };

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        context = getApplicationContext();
        prefs = BackupPrefs.get(context);
        ledger = BackupLedger.get(context);
        if (!prefs.isEnabled()) {
            return Result.success();
        }
        BackupPrefs.Auth auth = prefs.auth();
        if (auth == null || !auth.hasLibrary()) {
            // Signed out (or no repo chosen yet): the status says needs_sign_in and setAuth starts a new run.
            notifySignInNeeded();
            BackupStatus.changed(context);
            return Result.success();
        }
        // GitHub refused this token before; a new sign-in, opening the app or backupNow clears it.
        if (prefs.needsSignIn()) {
            return Result.success();
        }
        if (!MediaAccess.hasFullAccess(context)) {
            BackupStatus.changed(context);
            return Result.success();
        }

        prefs.setResumeAt(0L);
        ledger.resetInterrupted();
        // A content-triggered scan usually ran just before this; don't repeat it.
        if (!MediaScanner.scannedWithin(RECENT_SCAN_MS)) {
            scan(context);
        }

        BackupStatus.setUploading(context, true);
        try {
            if (auth.branch.isEmpty()) {
                auth = resolveBranch(auth);
            }
            GitHubClient github = new GitHubClient(context, auth, this::isStopped);
            github.setWaitListener(waiting -> BackupStatus.setRateLimited(context, waiting));
            library = new LibraryWriter(context, github);
            uploader = new MediaUploader(context, ledger, github, library, this::isStopped);
            drain();
        } catch (GitHubClient.AuthRequiredException e) {
            Log.w(TAG, "GitHub refused the token", e);
            ledger.resetInterrupted();
            // A slow answer for an old token must not flag a fresh sign-in.
            if (prefs.isCurrentToken(auth.token)) {
                prefs.setNeedsSignIn(true);
                notifySignInNeeded();
            }
            return Result.success();
        } catch (GitHubClient.AuthChangedException e) {
            // Signed out, or into another account or repo: setAuth/clearAuth scheduled what comes next.
            ledger.resetInterrupted();
            return Result.success();
        } catch (GitHubClient.RateLimitedException e) {
            ledger.resetInterrupted();
            // Replaces (and so ends) this run with one that starts after the wait.
            BackupScheduler.resumeAfterRateLimit(context, rateLimitDelayMillis(e));
            return Result.success();
        } catch (IOException e) {
            // Network loss, server errors after retries, a conflict storm, a missing
            // repo or a broken manifest, or WorkManager stopped us.
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

    private void drain() throws IOException {
        boolean rescanned = false;
        int processed = 0;
        while (!isStopped() && prefs.isEnabled()) {
            BackupLedger.Item item = ledger.nextPending(skip);
            if (item == null) {
                if (!batch.isEmpty()) {
                    commitBatch();
                    continue;
                }
                // Catch anything added while uploading before calling it done.
                if (rescanned) {
                    break;
                }
                rescanned = true;
                scan(context);
                continue;
            }
            rescanned = false;
            if (!batch.isEmpty() && item.size >= LARGE_ITEM_BYTES) {
                commitBatch();
            }

            final int index = processed + 1;
            // Prepared items wait in the batch as "uploading" rows, which pendingCount includes.
            final int total = processed + Math.max(1, ledger.pendingCount() - batch.size());
            updateForeground(context, item.displayName, index, total, 0, item.size, true);
            MediaUploader.Prepared prepared = uploader.prepare(
                item,
                (name, done, size) -> {
                    BackupStatus.setCurrent(context, name, done, size);
                    updateForeground(context, name, index, total, done, size, false);
                },
                pendingBatch
            );
            processed++;
            BackupStatus.clearCurrent(context);
            if (prepared.outcome == MediaUploader.Outcome.FAILED) {
                // Don't hammer a failing item in the same run; it stays queued (or parked as failed).
                skip.add(item.key());
            }
            if (prepared.op != null) {
                if (batch.isEmpty()) {
                    batchStartedAt = SystemClock.elapsedRealtime();
                }
                batch.add(prepared.op);
                if (item.size >= LARGE_ITEM_BYTES) {
                    batchHasLargeItem = true;
                }
            }
            if (
                !batch.isEmpty() &&
                (batch.size() >= MAX_BATCH_OPS || batchHasLargeItem || SystemClock.elapsedRealtime() - batchStartedAt >= MAX_BATCH_WAIT_MS)
            ) {
                commitBatch();
            }
        }
        if (!batch.isEmpty() && !isStopped() && prefs.isEnabled()) {
            commitBatch();
        }
    }

    /**
     * Commits the waiting items. Problems that concern only this batch are
     * recorded per item; sign-in, rate limit, network and stop problems are
     * thrown and the items go back to the queue with their uploads saved.
     */
    private void commitBatch() throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        List<LibraryWriter.Op> ops = new ArrayList<>(batch);
        batch.clear();
        batchHasLargeItem = false;
        LibraryWriter.Result result;
        try {
            result = library.commit(ops);
        } catch (GitHubClient.TreeRejectedException e) {
            // Usually a saved blob GitHub has since dropped: upload these items again (once per run).
            Log.w(TAG, "GitHub rejected the new tree; uploading these items again", e);
            for (LibraryWriter.Op op : ops) {
                BackupLedger.Item item = (BackupLedger.Item) op.tag;
                if (rejectedOnce.add(item.key())) {
                    ledger.requeueForReupload(item);
                } else {
                    ledger.recordFailure(item, e.getMessage(), true);
                    skip.add(item.key());
                }
            }
            return;
        } catch (GitHubClient.HttpStatusException e) {
            if (e.isServerError()) {
                throw e;
            }
            for (LibraryWriter.Op op : ops) {
                BackupLedger.Item item = (BackupLedger.Item) op.tag;
                ledger.recordFailure(item, e.getMessage(), false);
                skip.add(item.key());
            }
            return;
        }
        uploader.finish(result);
        if (result.commitSha != null) {
            prefs.setRateLimitStreak(0);
        }
        List<Map<String, Object>> added = result.addedEntries();
        if (!added.isEmpty()) {
            emitItemsUploaded(added);
        }
        BackupStatus.changed(context);
    }

    /** Lets an open gallery show the new items at once instead of re-reading the whole index. */
    private static void emitItemsUploaded(List<Map<String, Object>> entries) {
        BackupPlugin plugin = BackupPlugin.live();
        if (plugin == null) {
            return;
        }
        JSArray array = new JSArray();
        for (Map<String, Object> entry : entries) {
            array.put(LibraryWriter.toJsObject(entry));
        }
        plugin.emitItemsUploaded(array);
    }

    /** A session stored without a branch: use (and remember) the repo's default branch. */
    private BackupPrefs.Auth resolveBranch(BackupPrefs.Auth auth) throws IOException {
        String branch = new GitHubClient(context, auth, this::isStopped).defaultBranch();
        BackupPrefs.Auth resolved = prefs.completeBranch(auth, branch);
        if (resolved == null) {
            throw new GitHubClient.AuthChangedException();
        }
        return resolved;
    }

    /** GitHub's own wait when it gave one; otherwise 1, 2, 4… minutes (at most an hour) while it keeps refusing. */
    private long rateLimitDelayMillis(GitHubClient.RateLimitedException e) {
        long seconds = e.retryAfterSeconds;
        if (!e.explicit) {
            int streak = prefs.rateLimitStreak();
            seconds = Math.min(3600L, seconds << Math.min(streak, 6));
            prefs.setRateLimitStreak(streak + 1);
        }
        return seconds * 1000L;
    }

    private void notifySignInNeeded() {
        if (!prefs.signInNotified()) {
            BackupNotifications.showSignInNeeded(context);
            prefs.setSignInNotified(true);
        }
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
