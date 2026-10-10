package com.fourzerofourgb.app.backup;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** Runtime permission checks shared by the plugin and the workers. */
final class MediaAccess {

    private MediaAccess() {}

    static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Full read access to every photo and video on the device. */
    static boolean hasFullAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO);
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** Android 14+ "Select photos and videos": only the picked items are readable. */
    static boolean hasPartialAccessOnly(Context context) {
        return (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasFullAccess(context) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        );
    }

    /** Whether originals (with GPS metadata) can be opened via MediaStore.setRequireOriginal. */
    static boolean canReadOriginals(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** Saving into the public Pictures/Movies folders needs a runtime grant before scoped storage. */
    static boolean needsLegacyWritePermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
}
