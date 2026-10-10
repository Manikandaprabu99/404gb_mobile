package com.fourzerofourgb.app.photowatcher;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Resilience net, not the primary detection mechanism: the ContentObserver
 * registered in {@link PhotoWatcherService} is what actually notices new
 * photos/videos in near-real-time while the service is alive. This
 * periodic job (~15 minutes is WorkManager's platform floor for periodic
 * work, so this cannot be tightened further) only checks whether watching
 * is supposed to be enabled but the foreground service isn't currently
 * running in this process — most commonly because an OEM's aggressive
 * battery optimizer killed it — and restarts it if so.
 */
public class PhotoWatcherWatchdogWorker extends Worker {

    private static final String TAG = "PhotoWatcherWatchdog";

    public PhotoWatcherWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (PhotoWatcherPrefs.isEnabled(context) && !PhotoWatcherService.isRunning) {
            Intent serviceIntent = new Intent(context, PhotoWatcherService.class);
            serviceIntent.setAction(PhotoWatcherService.ACTION_RESCAN);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                // Android 12+ (API 31) can refuse this call with a
                // ForegroundServiceStartNotAllowedException when the app is
                // background-restricted at the moment this periodic job
                // runs, which is exactly the scenario this watchdog exists
                // to handle (app backgrounded, service already killed). A
                // plain periodic WorkManager job is not one of the
                // documented background-start exemptions, but a user who
                // has granted "ignore battery optimizations" for this app
                // (requested from PhotoWatcherPlugin.beginWatching()) is
                // exempt, so this call is expected to succeed in that case
                // and to (rarely) fail otherwise. Catch broadly rather than
                // just the API 31+ exception type so this also can't crash
                // this background thread on any other unexpected failure
                // (e.g. a transient AMS/service-start error) on any API
                // level. Either way, don't let it defeat the whole run:
                // log it and let the next ~15-minute watchdog tick (or the
                // user simply reopening the app) try again.
                Log.w(TAG, "Could not restart PhotoWatcherService from watchdog; will retry next tick", e);
            }
        }
        return Result.success();
    }
}
