package com.fourzerofourgb.app.photowatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.fourzerofourgb.app.R;

/**
 * Foreground service that watches the device's photo/video MediaStore
 * collections for new rows and hands each new asset off to whichever
 * {@link PhotoWatcherPlugin} instance is currently active, so it can be
 * queued for upload from JS via the existing UploadQueue.
 *
 * This is the "always watching" half of the feature:
 * {@link PhotoWatcherWatchdogWorker} is the resilience net that restarts
 * this service if OEM battery management kills it, and
 * {@link PhotoWatcherBootReceiver} restarts it after a reboot.
 */
public class PhotoWatcherService extends Service {

    private static final String TAG = "PhotoWatcherService";
    private static final String CHANNEL_ID = "photowatcher_channel";
    private static final int NOTIFICATION_ID = 4404;

    /** Purely informational intent action used when (re)starting this
     * service, so logs/adb make it obvious why it was started. The service
     * behaves the same regardless of the intent's action or extras — every
     * start (initial or repeat) triggers a rescan. */
    public static final String ACTION_RESCAN = "com.fourzerofourgb.app.photowatcher.action.RESCAN";

    /**
     * True only while this process has a live instance of this service.
     * Read by {@link PhotoWatcherWatchdogWorker} to decide whether to
     * restart the service. Checking this in-process static flag is cheaper
     * and more reliable for "is MY OWN service running" than
     * ActivityManager#getRunningServices, and it's automatically correct
     * across process death: a fresh process starts with this false until
     * onCreate() runs again.
     */
    static volatile boolean isRunning = false;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private ContentObserver imagesObserver;
    private ContentObserver videoObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;

        createNotificationChannel();
        // Must call startForeground() promptly — Android 14+ (API 34) kills
        // the app with ForegroundServiceDidNotStartInTimeException if a
        // service started via startForegroundService() doesn't call this
        // within a few seconds — so this happens before anything else,
        // including touching MediaStore/ContentObserver.
        startForeground(NOTIFICATION_ID, buildNotification());

        workerThread = new HandlerThread("PhotoWatcherWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        imagesObserver = new AssetObserver(workerHandler);
        videoObserver = new AssetObserver(workerHandler);
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imagesObserver);
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver);

        // First run ever: don't treat the user's entire existing library as
        // "new" — only photos/videos added from this point on are
        // auto-backed-up. A one-time backfill of the existing library is a
        // separate, explicit action (the manual multi-select uploader
        // already on the upload page), not this background watcher.
        if (PhotoWatcherPrefs.getLastWatermark(this) == 0L) {
            PhotoWatcherPrefs.setWatermark(this, System.currentTimeMillis() / 1000L);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every (re)start — the initial one from PhotoWatcherPlugin.start(),
        // a boot restart, or a watchdog restart — is also a good time to
        // make sure nothing was missed while this process was down.
        if (workerHandler != null) {
            workerHandler.post(this::scanForNewAssets);
        }
        // START_STICKY: ask the OS to recreate us (with a null intent) if it
        // kills this process under plain memory pressure. This does not
        // help against explicit OEM battery-optimization kills, which is
        // exactly what the WorkManager watchdog exists to recover from.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Android 15+ (API 35) calls this on a "dataSync"-type foreground
     * service when it hits its rolling execution-time limit, instead of
     * just killing the process out from under it. We must stop promptly
     * and cleanly here rather than let the system do it for us.
     * PhotoWatcherWatchdogWorker will start a fresh instance on its next
     * run (at most ~15 minutes later), so this is an expected, bounded
     * gap in coverage, not a crash.
     */
    public void onTimeout(int startId, int fgsType) {
        Log.i(TAG, "onTimeout: dataSync foreground service hit its time limit; stopping cleanly");
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (imagesObserver != null) {
            getContentResolver().unregisterContentObserver(imagesObserver);
        }
        if (videoObserver != null) {
            getContentResolver().unregisterContentObserver(videoObserver);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    private class AssetObserver extends ContentObserver {
        AssetObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            super.onChange(selfChange, uri);
            scanForNewAssets();
        }
    }

    /**
     * Queries both MediaStore collections for rows added after the stored
     * watermark, hands each one to the active plugin instance, then
     * advances the watermark. Always runs on {@link #workerHandler}'s
     * thread, never the main thread.
     */
    private void scanForNewAssets() {
        long watermark = PhotoWatcherPrefs.getLastWatermark(this);
        long newWatermark = watermark;
        newWatermark = Math.max(newWatermark, scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, watermark));
        newWatermark = Math.max(newWatermark, scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, watermark));
        if (newWatermark > watermark) {
            PhotoWatcherPrefs.setWatermark(this, newWatermark);
        }
    }

    /**
     * Queries one MediaStore collection for rows added after
     * {@code watermark} (DATE_ADDED, seconds since epoch, strictly
     * greater), hands each to the active plugin instance in ascending
     * date order, and returns the highest DATE_ADDED actually handed off
     * (or {@code watermark} unchanged if none were).
     *
     * Deliberately does NOT advance past a row it couldn't hand off (no
     * active plugin instance right now, e.g. the app hasn't been opened
     * yet this process) — it stops there instead, so that row is retried
     * on the next scan rather than silently skipped forever. One known,
     * accepted edge case: two assets sharing the exact same DATE_ADDED
     * second (e.g. burst-mode shots) are both still queued in the same
     * pass since selection is "> watermark", not ">=", but if the process
     * dies mid-batch between them, a resumed scan re-checks from the old
     * watermark and could hand the first one off a second time; the
     * upload queue already dedups identical bytes by hash, so a rare
     * duplicate hand-off costs a skipped re-upload, not a corrupt one.
     */
    private long scanCollection(Uri collection, long watermark) {
        long advancedTo = watermark;
        String[] projection = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        };
        String selection = MediaStore.MediaColumns.DATE_ADDED + " > ?";
        String[] selectionArgs = { String.valueOf(watermark) };
        String sortOrder = MediaStore.MediaColumns.DATE_ADDED + " ASC";

        try (
            Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, sortOrder)
        ) {
            if (cursor == null) return advancedTo;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);

            while (cursor.moveToNext()) {
                PhotoWatcherPlugin plugin = PhotoWatcherPlugin.getActiveInstance();
                if (plugin == null) {
                    Log.d(TAG, "No active PhotoWatcherPlugin instance right now; will retry remaining rows in " + collection + " on the next scan");
                    break;
                }

                long id = cursor.getLong(idCol);
                long dateAdded = cursor.getLong(dateCol);
                String displayName = cursor.getString(nameCol);
                String mimeType = cursor.getString(mimeCol);
                Uri contentUri = ContentUris.withAppendedId(collection, id);

                plugin.notifyNewAsset(contentUri.toString(), displayName, mimeType);
                advancedTo = dateAdded;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Missing media read permission while scanning " + collection, e);
        }
        return advancedTo;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Photo backup", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while 404GB is watching for new photos/videos to back up");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("404GB backup")
            .setContentText("Watching for new photos & videos to back up")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }
}
