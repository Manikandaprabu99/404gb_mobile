package com.fourzerofourgb.app.photowatcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small helper around the one SharedPreferences file the photo-watcher
 * feature uses to persist (a) whether the user has turned watching on and
 * (b) how far it has scanned MediaStore so far. Shared by
 * {@link PhotoWatcherPlugin}, {@link PhotoWatcherService},
 * {@link PhotoWatcherBootReceiver} and {@link PhotoWatcherWatchdogWorker} so
 * they all agree on the same file name and keys.
 */
final class PhotoWatcherPrefs {

    static final String PREFS_NAME = "404gb_photowatcher";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_WATERMARK = "lastWatermark";

    private PhotoWatcherPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Whether the user has turned the watcher on (survives reboots/process death). */
    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * How far MediaStore has been scanned, in seconds since epoch — the
     * same unit as {@code MediaStore.MediaColumns.DATE_ADDED}. 0 means
     * "never scanned yet".
     */
    static long getLastWatermark(Context context) {
        return prefs(context).getLong(KEY_LAST_WATERMARK, 0L);
    }

    static void setWatermark(Context context, long dateAddedSeconds) {
        prefs(context).edit().putLong(KEY_LAST_WATERMARK, dateAddedSeconds).apply();
    }
}
