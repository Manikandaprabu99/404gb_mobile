package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether MainActivity is on screen (a count across onStart/onStop). Going to the background
 * queues a People run about 90 s later; coming back cancels it.
 */
public final class AppVisibility {

    private static final String TAG = "404gb.AppVisibility";
    private static final AtomicInteger started = new AtomicInteger();

    private AppVisibility() {}

    public static void onStart(Context context) {
        if (started.incrementAndGet() == 1) {
            try {
                BackupScheduler.cancelFaceScanSoon(context);
            } catch (RuntimeException e) {
                // WorkManager unavailable (being initialised or updated): the run steps aside on its own.
                Log.w(TAG, "Could not cancel the People run", e);
            }
        }
    }

    public static void onStop(Context context) {
        if (started.updateAndGet(n -> Math.max(0, n - 1)) == 0) {
            try {
                BackupScheduler.faceScanSoon(context, FaceScanWorker.TRIGGER_APP_CLOSED);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not queue a People run", e);
            }
        }
    }

    static boolean isVisible() {
        return started.get() > 0;
    }
}
