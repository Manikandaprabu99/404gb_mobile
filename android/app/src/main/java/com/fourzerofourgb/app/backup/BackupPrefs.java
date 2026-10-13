package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Backup settings, the GitHub session, and small bits of state that must
 * survive process death. App-private SharedPreferences: the manifest's backup
 * rules keep all of it off cloud backups and device transfers.
 */
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
    private static final String KEY_RESUME_AT = "resumeAt";
    private static final String KEY_RATE_LIMIT_STREAK = "rateLimitStreak";
    private static final String KEY_LIBRARY = "library";
    private static final String KEY_FOLDER_BACKFILL_DONE = "folderBackfillDone";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_AUTH_LOGIN = "authLogin";
    private static final String KEY_AUTH_OWNER = "authOwner";
    private static final String KEY_AUTH_REPO = "authRepo";
    private static final String KEY_AUTH_BRANCH = "authBranch";
    private static final String KEY_FACE_SCAN_ENABLED = "faceScanEnabled";
    private static final String KEY_FACE_SCAN_LAST_RUN_AT = "faceScanLastRunAt";
    private static final String KEY_FACE_SCAN_LAST_CHECKED = "faceScanLastChecked";
    private static final String KEY_FACE_SCAN_LAST_ERROR = "faceScanLastError";

    /** The GitHub session the app and the background worker share (web: lib/native/backup.ts BackupAuth). */
    static final class Auth {

        final String token;
        final String login;
        final String owner;
        final String repo;
        final String branch;

        Auth(String token, String login, String owner, String repo, String branch) {
            this.token = token;
            this.login = login == null ? "" : login;
            this.owner = owner == null ? "" : owner;
            this.repo = repo == null ? "" : repo;
            this.branch = branch == null ? "" : branch;
        }

        /** False until a library repo has been chosen. */
        boolean hasLibrary() {
            return !owner.isEmpty() && !repo.isEmpty();
        }

        /** "owner/repo": the library the ledger's uploaded rows belong to. */
        String library() {
            return owner + "/" + repo;
        }

        /** Same token, repo and branch: everything a worker's requests depend on. */
        boolean sameSession(Auth other) {
            return other != null && token.equals(other.token) && owner.equals(other.owner) && repo.equals(other.repo) && branch.equals(other.branch);
        }

        /** Same repo (GitHub names are case-insensitive) and branch. */
        boolean sameTarget(Auth other) {
            return other != null && owner.equalsIgnoreCase(other.owner) && repo.equalsIgnoreCase(other.repo) && branch.equals(other.branch);
        }

        Auth withBranch(String newBranch) {
            return new Auth(token, login, owner, repo, newBranch);
        }
    }

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

    /** GitHub answered 401 (or refused access) for the stored token. */
    boolean needsSignIn() {
        return prefs.getBoolean(KEY_NEEDS_SIGN_IN, false);
    }

    boolean signInNotified() {
        return prefs.getBoolean(KEY_SIGN_IN_NOTIFIED, false);
    }

    boolean batteryPrompted() {
        return prefs.getBoolean(KEY_BATTERY_PROMPTED, false);
    }

    /** When a GitHub rate-limit pause ends (epoch ms); 0 when not paused. */
    long resumeAt() {
        return prefs.getLong(KEY_RESUME_AT, 0L);
    }

    /** Rate-limit pauses in a row without a commit in between (grows the wait when GitHub gives none). */
    int rateLimitStreak() {
        return prefs.getInt(KEY_RATE_LIMIT_STREAK, 0);
    }

    /** "owner/repo" the ledger's uploaded rows belong to, or null before the first sign-in. */
    String library() {
        return prefs.getString(KEY_LIBRARY, null);
    }

    /**
     * Whether the folder backfill finished for this library (LibraryIndex.libraryKey,
     * "owner/repo@branch"). Only the last finished library is remembered: going
     * back to an earlier one runs the (idempotent) backfill there once more.
     */
    boolean folderBackfillDone(String library) {
        return library.equals(prefs.getString(KEY_FOLDER_BACKFILL_DONE, null));
    }

    /** The stored GitHub session, or null when no token is stored. */
    Auth auth() {
        String token = prefs.getString(KEY_AUTH_TOKEN, null);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return new Auth(
            token,
            prefs.getString(KEY_AUTH_LOGIN, ""),
            prefs.getString(KEY_AUTH_OWNER, ""),
            prefs.getString(KEY_AUTH_REPO, ""),
            prefs.getString(KEY_AUTH_BRANCH, "")
        );
    }

    /** A token is stored and a library repo chosen, so backup can talk to GitHub. */
    boolean canUpload() {
        Auth auth = auth();
        return auth != null && auth.hasLibrary();
    }

    /** True while {@code auth} is still the stored session (same token, repo and branch). */
    boolean isCurrent(Auth auth) {
        return auth != null && auth.sameSession(auth());
    }

    boolean isCurrentToken(String token) {
        return token != null && token.equals(prefs.getString(KEY_AUTH_TOKEN, null));
    }

    /** Background People matching (web: lib/native/backup.ts FaceScanStatus); on unless turned off. */
    boolean faceScanEnabled() {
        return prefs.getBoolean(KEY_FACE_SCAN_ENABLED, true);
    }

    /** When the last background People run ended (epoch ms); 0 before the first. */
    long faceScanLastRunAt() {
        return prefs.getLong(KEY_FACE_SCAN_LAST_RUN_AT, 0L);
    }

    /** Photos the last background People run checked. */
    int faceScanLastChecked() {
        return prefs.getInt(KEY_FACE_SCAN_LAST_CHECKED, 0);
    }

    /** Why the last background People run stopped early, or null. */
    String faceScanLastError() {
        return prefs.getString(KEY_FACE_SCAN_LAST_ERROR, null);
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

    /** Stores the session; written synchronously so a worker starting right after sees it. */
    void setAuth(Auth auth) {
        synchronized (BackupPrefs.class) {
            prefs
                .edit()
                .putString(KEY_AUTH_TOKEN, auth.token)
                .putString(KEY_AUTH_LOGIN, auth.login)
                .putString(KEY_AUTH_OWNER, auth.owner)
                .putString(KEY_AUTH_REPO, auth.repo)
                .putString(KEY_AUTH_BRANCH, auth.branch)
                .commit();
        }
    }

    /** Forgets the token only: signing in again returns to the same login and library. */
    void clearToken() {
        synchronized (BackupPrefs.class) {
            prefs.edit().remove(KEY_AUTH_TOKEN).commit();
        }
    }

    /**
     * Fills in the branch looked up for a session stored without one; null
     * (nothing written) when the session changed meanwhile.
     */
    Auth completeBranch(Auth auth, String branch) {
        synchronized (BackupPrefs.class) {
            if (!isCurrent(auth)) {
                return null;
            }
            prefs.edit().putString(KEY_AUTH_BRANCH, branch).commit();
            return auth.withBranch(branch);
        }
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

    void setResumeAt(long epochMillis) {
        prefs.edit().putLong(KEY_RESUME_AT, epochMillis).apply();
    }

    void setRateLimitStreak(int value) {
        prefs.edit().putInt(KEY_RATE_LIMIT_STREAK, value).apply();
    }

    void setLibrary(String library) {
        prefs.edit().putString(KEY_LIBRARY, library).apply();
    }

    void setFolderBackfillDone(String library) {
        prefs.edit().putString(KEY_FOLDER_BACKFILL_DONE, library).apply();
    }

    void setFaceScanEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_FACE_SCAN_ENABLED, value).apply();
    }

    /** Records how a background People run ended; {@code error} null when it wasn't cut short. */
    void setFaceScanResult(long endedAt, int checked, String error) {
        SharedPreferences.Editor editor = prefs
            .edit()
            .putLong(KEY_FACE_SCAN_LAST_RUN_AT, endedAt)
            .putInt(KEY_FACE_SCAN_LAST_CHECKED, Math.max(0, checked));
        if (error == null) {
            editor.remove(KEY_FACE_SCAN_LAST_ERROR);
        } else {
            editor.putString(KEY_FACE_SCAN_LAST_ERROR, error);
        }
        editor.apply();
    }

    /** Another library: the last run's numbers were about the previous one. */
    void clearFaceScanResult() {
        prefs.edit().remove(KEY_FACE_SCAN_LAST_RUN_AT).remove(KEY_FACE_SCAN_LAST_CHECKED).remove(KEY_FACE_SCAN_LAST_ERROR).apply();
    }
}
