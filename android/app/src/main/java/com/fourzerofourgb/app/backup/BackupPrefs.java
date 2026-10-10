package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.content.SharedPreferences;

/** Backup settings and small bits of state that must survive process death. */
final class BackupPrefs {

    static final String FOLDERS_ALL = "all";
    static final String FOLDERS_CAMERA = "camera";

    private static final String NAME = "404gb_backup";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WIFI_ONLY = "wifiOnly";
    private static final String KEY_CHARGING_ONLY = "chargingOnly";
    private static final String KEY_FOLDERS = "folders";
    private static final String KEY_INCLUDE_EXISTING = "includeExisting";
    private static final String KEY_CUTOFF_SECONDS = "cutoffSeconds";
    private static final String KEY_LAST_BACKUP_AT = "lastBackupAt";
    private static final String KEY_NEEDS_SIGN_IN = "needsSignIn";
    private static final String KEY_SIGN_IN_NOTIFIED = "signInNotified";
    private static final String KEY_BATTERY_PROMPTED = "batteryPrompted";

    private final SharedPreferences prefs;

    private BackupPrefs(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static BackupPrefs get(Context context) {
        return new BackupPrefs(context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE));
    }

    static boolean isValidFolders(String folders) {
        return FOLDERS_ALL.equals(folders) || FOLDERS_CAMERA.equals(folders);
    }

    boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    boolean wifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, true);
    }

    boolean chargingOnly() {
        return prefs.getBoolean(KEY_CHARGING_ONLY, false);
    }

    String folders() {
        String value = prefs.getString(KEY_FOLDERS, FOLDERS_ALL);
        return isValidFolders(value) ? value : FOLDERS_ALL;
    }

    boolean includeExisting() {
        return prefs.getBoolean(KEY_INCLUDE_EXISTING, false);
    }

    /** MediaStore DATE_ADDED (seconds) below which items are ignored; 0 = whole library. */
    long cutoffSeconds() {
        return prefs.getLong(KEY_CUTOFF_SECONDS, 0L);
    }

    long lastBackupAt() {
        return prefs.getLong(KEY_LAST_BACKUP_AT, 0L);
    }

    boolean needsSignIn() {
        return prefs.getBoolean(KEY_NEEDS_SIGN_IN, false);
    }

    boolean signInNotified() {
        return prefs.getBoolean(KEY_SIGN_IN_NOTIFIED, false);
    }

    boolean batteryPrompted() {
        return prefs.getBoolean(KEY_BATTERY_PROMPTED, false);
    }

    void setOptions(boolean wifiOnly, boolean chargingOnly, String folders) {
        prefs
            .edit()
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .putBoolean(KEY_CHARGING_ONLY, chargingOnly)
            .putString(KEY_FOLDERS, isValidFolders(folders) ? folders : FOLDERS_ALL)
            .apply();
    }

    /**
     * Turns backup on. Re-enabling while already on never narrows what gets
     * backed up: items queued under the earlier settings stay queued.
     */
    void enable(boolean includeExisting) {
        boolean wasEnabled = isEnabled();
        long cutoff = cutoffSeconds();
        boolean include = includeExisting;
        if (includeExisting) {
            cutoff = 0L;
        } else if (!wasEnabled) {
            cutoff = System.currentTimeMillis() / 1000L;
        } else {
            include = includeExisting();
        }
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_INCLUDE_EXISTING, include)
            .putLong(KEY_CUTOFF_SECONDS, cutoff)
            .putBoolean(KEY_NEEDS_SIGN_IN, false)
            .apply();
    }

    void disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_NEEDS_SIGN_IN, false).apply();
    }

    void setLastBackupAt(long epochMillis) {
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, epochMillis).apply();
    }

    void setNeedsSignIn(boolean value) {
        prefs.edit().putBoolean(KEY_NEEDS_SIGN_IN, value).apply();
    }

    void setSignInNotified(boolean value) {
        prefs.edit().putBoolean(KEY_SIGN_IN_NOTIFIED, value).apply();
    }

    void setBatteryPrompted(boolean value) {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, value).apply();
    }
}
