package com.fourzerofourgb.app.photowatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/**
 * Restarts the photo-watcher foreground service after a reboot — but only
 * if the user had previously turned watching on. A reboot must never
 * silently turn a feature on that was never enabled.
 */
public class PhotoWatcherBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!PhotoWatcherPrefs.isEnabled(context)) {
            return;
        }

        // Starting a foreground service from a BOOT_COMPLETED receiver is
        // one of Android's documented exemptions to the background-start
        // restrictions introduced in Android 12 (API 31).
        Intent serviceIntent = new Intent(context, PhotoWatcherService.class);
        serviceIntent.setAction(PhotoWatcherService.ACTION_RESCAN);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
