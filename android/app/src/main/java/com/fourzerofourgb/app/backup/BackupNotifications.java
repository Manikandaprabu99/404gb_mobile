package com.fourzerofourgb.app.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.fourzerofourgb.app.MainActivity;
import com.fourzerofourgb.app.R;

final class BackupNotifications {

    static final String CHANNEL_PROGRESS = "backup_progress";
    static final String CHANNEL_ATTENTION = "backup_attention";
    static final int ID_PROGRESS = 4404;
    static final int ID_SIGN_IN = 4405;

    private static final String TAG = "404gb.Notifications";

    private BackupNotifications() {}

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel progress = new NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.backup_channel_progress),
            NotificationManager.IMPORTANCE_LOW
        );
        progress.setDescription(context.getString(R.string.backup_channel_progress_description));
        progress.setShowBadge(false);
        manager.createNotificationChannel(progress);

        NotificationChannel attention = new NotificationChannel(
            CHANNEL_ATTENTION,
            context.getString(R.string.backup_channel_attention),
            NotificationManager.IMPORTANCE_DEFAULT
        );
        attention.setDescription(context.getString(R.string.backup_channel_attention_description));
        manager.createNotificationChannel(attention);
    }

    /** "Backing up 3 of 12" with the file name and a progress bar. */
    static Notification progress(Context context, int index, int total, String fileName, long bytesDone, long bytesTotal) {
        ensureChannels(context);
        String title = total > 0
            ? context.getString(R.string.backup_progress_title, index, total)
            : context.getString(R.string.backup_progress_preparing);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_404gb)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context));
        if (fileName != null && !fileName.isEmpty()) {
            builder.setContentText(fileName);
        }
        if (bytesTotal > 0) {
            int permille = (int) Math.min(1000L, Math.max(0L, bytesDone) * 1000L / bytesTotal);
            builder.setProgress(1000, permille, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    static void showSignInNeeded(Context context) {
        if (!MediaAccess.canPostNotifications(context)) {
            return;
        }
        ensureChannels(context);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_404gb)
            .setContentTitle(context.getString(R.string.backup_sign_in_title))
            .setContentText(context.getString(R.string.backup_sign_in_text))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(R.string.backup_sign_in_text)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build();
        try {
            NotificationManagerCompat.from(context).notify(ID_SIGN_IN, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission was revoked", e);
        }
    }

    static void clearSignInNeeded(Context context) {
        NotificationManagerCompat.from(context).cancel(ID_SIGN_IN);
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) {
            intent = new Intent(context, MainActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
