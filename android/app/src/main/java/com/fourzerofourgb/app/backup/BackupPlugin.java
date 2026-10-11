package com.fourzerofourgb.app.backup;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.FileProvider;
import androidx.work.ExistingWorkPolicy;
import com.fourzerofourgb.app.R;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * JS bridge for native backup (web: lib/native/backup.ts). Backup itself runs
 * in WorkManager workers and keeps going with the app closed; this plugin
 * only configures it, reports status and handles save/share.
 */
@CapacitorPlugin(
    name = "Backup",
    permissions = {
        @Permission(alias = BackupPlugin.ALIAS_MEDIA, strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = BackupPlugin.ALIAS_MEDIA_SELECTED, strings = { Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }),
        @Permission(alias = BackupPlugin.ALIAS_STORAGE_READ, strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = BackupPlugin.ALIAS_STORAGE_WRITE, strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }),
        @Permission(alias = BackupPlugin.ALIAS_MEDIA_LOCATION, strings = { Manifest.permission.ACCESS_MEDIA_LOCATION }),
        @Permission(alias = BackupPlugin.ALIAS_NOTIFICATIONS, strings = { Manifest.permission.POST_NOTIFICATIONS }),
    }
)
public class BackupPlugin extends Plugin {

    static final String ALIAS_MEDIA = "media";
    static final String ALIAS_MEDIA_SELECTED = "mediaSelected";
    static final String ALIAS_STORAGE_READ = "storageRead";
    static final String ALIAS_STORAGE_WRITE = "storageWrite";
    static final String ALIAS_MEDIA_LOCATION = "mediaLocation";
    static final String ALIAS_NOTIFICATIONS = "notifications";

    private static final String TAG = "404gb.BackupPlugin";
    private static final String EVENT_STATUS = "statusChanged";
    private static final String EVENT_SAVE_PROGRESS = "saveProgress";
    private static final String EVENT_ITEMS_UPLOADED = "itemsUploaded";
    // Printable ASCII, no spaces: classic, fine-grained and OAuth tokens all fit, header injection can't.
    private static final Pattern TOKEN = Pattern.compile("^[\\x21-\\x7E]{1,1024}$");
    private static final Pattern REPO_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern BRANCH_CHARS = Pattern.compile("^[A-Za-z0-9._/+@-]{1,255}$");

    // Lets workers reach the plugin of the live bridge (if any) to emit events.
    private static volatile WeakReference<BackupPlugin> live = new WeakReference<>(null);

    private ExecutorService io;
    private boolean hadFullAccess;

    static BackupPlugin live() {
        return live.get();
    }

    @Override
    public void load() {
        io = Executors.newSingleThreadExecutor();
        live = new WeakReference<>(this);
        BackupStatus.resetLastEmitted();
        Context context = getContext();
        BackupScheduler.cancelLegacyWork(context);
        hadFullAccess = MediaAccess.hasFullAccess(context);
        BackupPrefs prefs = BackupPrefs.get(context);
        if (prefs.isEnabled()) {
            BackupNotifications.ensureChannels(context);
            // Opening the app is the signal to try again after a lapsed sign-in.
            if (prefs.needsSignIn()) {
                prefs.setNeedsSignIn(false);
            }
            BackupScheduler.ensureScheduled(context);
            BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.KEEP, 0);
        }
        BackupStatus.changed(context);
    }

    @Override
    protected void handleOnResume() {
        Context context = getContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        boolean fullAccess = MediaAccess.hasFullAccess(context);
        boolean accessRestored = fullAccess && !hadFullAccess;
        hadFullAccess = fullAccess;
        if (prefs.isEnabled() && (accessRestored || prefs.needsSignIn())) {
            prefs.setNeedsSignIn(false);
            BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.KEEP, 0);
        }
        // Battery or permission settings may have changed while the app was away.
        BackupStatus.changed(context);
    }

    @Override
    protected void handleOnDestroy() {
        if (live.get() == this) {
            live = new WeakReference<>(null);
        }
        if (io != null) {
            io.shutdown();
        }
    }

    /** Called from {@link BackupStatus} (any thread). */
    void emitStatusChanged(JSObject status) {
        getBridge().execute(() -> notifyListeners(EVENT_STATUS, status));
    }

    /** Called from the upload worker after a commit: {entries: MediaIndexEntry[]}, newest last. */
    void emitItemsUploaded(JSArray entries) {
        JSObject data = new JSObject();
        data.put("entries", entries);
        getBridge().execute(() -> notifyListeners(EVENT_ITEMS_UPLOADED, data));
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        call.resolve(BackupStatus.build(getContext()));
    }

    @PluginMethod
    public void enable(PluginCall call) {
        String folders = call.getString("folders", BackupPrefs.FOLDERS_ALL);
        if (!BackupPrefs.isValidFolders(folders)) {
            call.reject("folders must be \"all\" or \"camera\"", "INVALID_ARGUMENT");
            return;
        }
        List<String> aliases = missingPermissionAliases();
        if (aliases.isEmpty()) {
            finishEnable(call);
            return;
        }
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "onEnablePermissions");
    }

    @PermissionCallback
    private void onEnablePermissions(PluginCall call) {
        Context context = getContext();
        if (!MediaAccess.hasFullAccess(context) && !MediaAccess.hasPartialAccessOnly(context)) {
            call.reject("Photo and video access was denied", "PERMISSION_DENIED");
            return;
        }
        // Partial (Android 14 "selected photos") access still enables backup; status
        // reports needs_permission until full access is granted.
        execute(() -> finishEnable(call));
    }

    private List<String> missingPermissionAliases() {
        Context context = getContext();
        List<String> aliases = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!MediaAccess.hasFullAccess(context)) {
                aliases.add(ALIAS_MEDIA);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    aliases.add(ALIAS_MEDIA_SELECTED);
                }
            }
            if (!MediaAccess.granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                aliases.add(ALIAS_NOTIFICATIONS);
            }
        } else if (!MediaAccess.hasFullAccess(context)) {
            aliases.add(ALIAS_STORAGE_READ);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !MediaAccess.granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            aliases.add(ALIAS_MEDIA_LOCATION);
        }
        return aliases;
    }

    private void finishEnable(PluginCall call) {
        Context context = getContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        boolean wasEnabled = prefs.isEnabled();
        boolean oldWifi = prefs.wifiOnly();
        boolean oldCharging = prefs.chargingOnly();
        boolean wifiOnly = bool(call, "wifiOnly", oldWifi);
        boolean chargingOnly = bool(call, "chargingOnly", oldCharging);
        String folders = call.getString("folders", prefs.folders());
        if (!BackupPrefs.isValidFolders(folders)) {
            folders = BackupPrefs.FOLDERS_ALL;
        }
        prefs.setOptions(wifiOnly, chargingOnly, folders);
        prefs.enable(bool(call, "includeExisting", false));

        BackupNotifications.ensureChannels(context);
        BackupNotifications.clearSignInNeeded(context);
        BackupScheduler.ensureScheduled(context);
        if (!wasEnabled || oldWifi != wifiOnly || oldCharging != chargingOnly) {
            BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.REPLACE, 0);
        } else {
            BackupScheduler.kickUpload(context);
        }
        hadFullAccess = MediaAccess.hasFullAccess(context);
        scanSoon();

        call.resolve(BackupStatus.build(context));
        BackupStatus.changed(context);

        if (!prefs.batteryPrompted()) {
            prefs.setBatteryPrompted(true);
            if (!isIgnoringBatteryOptimizations()) {
                getBridge()
                    .executeOnMainThread(() -> {
                        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.getPackageName()));
                        if (!tryStart(request)) {
                            Log.w(TAG, "Battery optimization prompt is unavailable on this device");
                        }
                    });
            }
        }
    }

    @PluginMethod
    public void disable(PluginCall call) {
        Context context = getContext();
        BackupPrefs.get(context).disable();
        BackupScheduler.cancelAll(context);
        BackupNotifications.clearSignInNeeded(context);
        call.resolve(BackupStatus.build(context));
        BackupStatus.changed(context);
    }

    @PluginMethod
    public void setOptions(PluginCall call) {
        Context context = getContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        boolean oldWifi = prefs.wifiOnly();
        boolean oldCharging = prefs.chargingOnly();
        String oldFolders = prefs.folders();
        boolean wifiOnly = call.hasOption("wifiOnly") ? bool(call, "wifiOnly", oldWifi) : oldWifi;
        boolean chargingOnly = call.hasOption("chargingOnly") ? bool(call, "chargingOnly", oldCharging) : oldCharging;
        String folders = call.hasOption("folders") ? call.getString("folders", oldFolders) : oldFolders;
        if (!BackupPrefs.isValidFolders(folders)) {
            call.reject("folders must be \"all\" or \"camera\"", "INVALID_ARGUMENT");
            return;
        }
        prefs.setOptions(wifiOnly, chargingOnly, folders);
        if (prefs.isEnabled()) {
            if (wifiOnly != oldWifi || chargingOnly != oldCharging) {
                // New constraints only take effect on a new request.
                BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.REPLACE, 0);
            } else if (!folders.equals(oldFolders)) {
                BackupScheduler.kickUpload(context);
            }
            if (!folders.equals(oldFolders)) {
                scanSoon();
            }
        }
        call.resolve(BackupStatus.build(context));
        BackupStatus.changed(context);
    }

    @PluginMethod
    public void backupNow(PluginCall call) {
        Context context = getContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        if (prefs.isEnabled()) {
            prefs.setNeedsSignIn(false);
            BackupScheduler.ensureScheduled(context);
            BackupScheduler.kickUpload(context);
            scanSoon();
        }
        call.resolve();
        BackupStatus.changed(context);
    }

    /**
     * Stores the GitHub session the worker uploads with (web: docs/STANDALONE.md).
     * A different owner/repo than the one the phone was backing up into queues
     * everything again for the new repo. Starts (or restarts) backup when enabled.
     */
    @PluginMethod
    public void setAuth(PluginCall call) {
        String token = call.getString("token");
        String login = call.getString("login", "");
        String owner = trimmed(call.getString("owner", ""));
        String repo = trimmed(call.getString("repo", ""));
        String branch = trimmed(call.getString("branch", ""));
        if (token == null || !TOKEN.matcher(token).matches()) {
            call.reject("token must be a GitHub token", "INVALID_ARGUMENT");
            return;
        }
        if (owner.isEmpty() != repo.isEmpty() || (!owner.isEmpty() && (!isRepoName(owner) || !isRepoName(repo)))) {
            call.reject("owner and repo must both be GitHub names (or both empty)", "INVALID_ARGUMENT");
            return;
        }
        if (!branch.isEmpty() && !isBranchName(branch)) {
            call.reject("branch is not a valid branch name", "INVALID_ARGUMENT");
            return;
        }
        BackupPrefs.Auth auth = new BackupPrefs.Auth(
            token,
            login == null ? "" : trimmed(login),
            owner,
            repo,
            owner.isEmpty() ? "" : branch
        );
        Context context = getContext();
        runInBackground(call, () -> {
            BackupPrefs prefs = BackupPrefs.get(context);
            BackupPrefs.Auth previous = prefs.auth();
            boolean libraryChanged;
            // Together, so a worker starting in between never sees the new library with the old session.
            synchronized (BackupLedger.class) {
                libraryChanged = auth.hasLibrary() && BackupLedger.get(context).adoptLibrary(prefs, auth.library());
                prefs.setAuth(auth);
            }
            boolean sessionChanged = previous == null || !previous.token.equals(auth.token) || !previous.sameTarget(auth);
            boolean wasBlocked = prefs.needsSignIn();
            prefs.setNeedsSignIn(false);
            prefs.setSignInNotified(false);
            BackupNotifications.clearSignInNeeded(context);
            if (prefs.isEnabled() && auth.hasLibrary()) {
                if (sessionChanged) {
                    // A run still using the old token or repo is replaced (it stops at its next request).
                    BackupScheduler.restartUpload(context);
                } else if (wasBlocked) {
                    BackupScheduler.kickUpload(context);
                } else {
                    BackupScheduler.enqueueUpload(context, ExistingWorkPolicy.KEEP, 0);
                }
                if (libraryChanged) {
                    scanSoon();
                }
            }
            call.resolve(BackupStatus.build(context));
            BackupStatus.changed(context);
        });
    }

    /** The stored session, or {auth: null} when signed out. */
    @PluginMethod
    public void getAuth(PluginCall call) {
        BackupPrefs.Auth auth = BackupPrefs.get(getContext()).auth();
        JSObject result = new JSObject();
        if (auth == null) {
            result.put("auth", JSONObject.NULL);
        } else {
            JSObject session = new JSObject();
            session.put("token", auth.token);
            session.put("login", auth.login);
            session.put("owner", auth.owner);
            session.put("repo", auth.repo);
            session.put("branch", auth.branch);
            result.put("auth", session);
        }
        call.resolve(result);
    }

    /**
     * Forgets the token on this phone (a Device Flow token can't be revoked
     * without the client secret). Backup pauses as needs_sign_in; the ledger,
     * login and repo are kept so signing in again carries on where it was.
     */
    @PluginMethod
    public void clearAuth(PluginCall call) {
        Context context = getContext();
        runInBackground(call, () -> {
            BackupPrefs prefs = BackupPrefs.get(context);
            prefs.clearToken();
            prefs.setNeedsSignIn(false);
            // Signing out on purpose isn't something to be reminded about.
            prefs.setSignInNotified(true);
            BackupNotifications.clearSignInNeeded(context);
            // Stop using the forgotten token right away; setAuth schedules uploads again.
            BackupScheduler.cancelUpload(context);
            LibraryIndex.clear(context);
            call.resolve(BackupStatus.build(context));
            BackupStatus.changed(context);
        });
    }

    @PluginMethod
    public void retryFailed(PluginCall call) {
        Context context = getContext();
        BackupLedger.get(context).retryFailed();
        BackupPrefs prefs = BackupPrefs.get(context);
        if (prefs.isEnabled()) {
            prefs.setNeedsSignIn(false);
            BackupScheduler.kickUpload(context);
        }
        call.resolve();
        BackupStatus.changed(context);
    }

    @PluginMethod
    public void saveToDevice(PluginCall call) {
        String mediaId = call.getString("mediaId");
        if (!MediaDownloader.isValidMediaId(mediaId)) {
            call.reject("A valid mediaId is required", "INVALID_ARGUMENT");
            return;
        }
        if (MediaAccess.needsLegacyWritePermission(getContext())) {
            requestPermissionForAlias(ALIAS_STORAGE_WRITE, call, "onSavePermission");
            return;
        }
        runSave(call, mediaId);
    }

    @PermissionCallback
    private void onSavePermission(PluginCall call) {
        if (MediaAccess.needsLegacyWritePermission(getContext())) {
            call.reject("Storage access was denied", "PERMISSION_DENIED");
            return;
        }
        runSave(call, call.getString("mediaId"));
    }

    private void runSave(PluginCall call, String mediaId) {
        Context context = getContext();
        runInBackground(call, () -> {
            try {
                Uri uri = new MediaDownloader(context, (done, total) -> emitSaveProgress(mediaId, done, total)).saveToGallery(mediaId);
                JSObject result = new JSObject();
                result.put("uri", uri.toString());
                call.resolve(result);
            } catch (GitHubClient.AuthRequiredException | GitHubClient.AuthChangedException e) {
                call.reject("Sign in again to download this item", "NEEDS_SIGN_IN", e);
            } catch (Exception e) {
                call.reject("Could not save to device: " + e.getMessage(), "SAVE_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void shareMedia(PluginCall call) {
        String mediaId = call.getString("mediaId");
        if (!MediaDownloader.isValidMediaId(mediaId)) {
            call.reject("A valid mediaId is required", "INVALID_ARGUMENT");
            return;
        }
        Context context = getContext();
        runInBackground(call, () -> {
            try {
                MediaDownloader.Shared shared = new MediaDownloader(context, (done, total) -> emitSaveProgress(mediaId, done, total))
                    .downloadForShare(mediaId);
                Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", shared.file);
                getBridge()
                    .executeOnMainThread(() -> {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(shared.mimeType);
                        send.putExtra(Intent.EXTRA_STREAM, contentUri);
                        send.setClipData(ClipData.newRawUri(shared.fileName, contentUri));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(send, context.getString(R.string.share_chooser_title));
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (tryStart(chooser)) {
                            call.resolve();
                        } else {
                            call.reject("No app can share this item", "SHARE_FAILED");
                        }
                    });
            } catch (GitHubClient.AuthRequiredException | GitHubClient.AuthChangedException e) {
                call.reject("Sign in again to share this item", "NEEDS_SIGN_IN", e);
            } catch (Exception e) {
                call.reject("Could not share: " + e.getMessage(), "SHARE_FAILED", e);
            }
        });
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        Context context = getContext();
        String pkg = context.getPackageName();
        boolean exempt = isIgnoringBatteryOptimizations();
        getBridge()
            .executeOnMainThread(() -> {
                boolean opened = false;
                if (!exempt) {
                    opened = tryStart(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                }
                if (!opened) {
                    opened = tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
                if (!opened) {
                    opened = tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                }
                if (opened) {
                    call.resolve();
                } else {
                    call.reject("Battery settings are unavailable on this device", "UNAVAILABLE");
                }
            });
    }

    private void emitSaveProgress(String mediaId, long done, long total) {
        JSObject data = new JSObject();
        data.put("mediaId", mediaId);
        data.put("bytesDone", done);
        data.put("bytesTotal", total);
        getBridge().execute(() -> notifyListeners(EVENT_SAVE_PROGRESS, data));
    }

    /** Refreshes the pending count right away instead of waiting for the upload worker. */
    private void scanSoon() {
        Context context = getContext();
        try {
            io.execute(() -> {
                BackupStatus.scanStarted(context);
                try {
                    MediaScanner.scan(context);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Scan failed", e);
                } finally {
                    BackupStatus.scanFinished(context);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Scan skipped; plugin is shutting down", e);
        }
    }

    private void runInBackground(PluginCall call, Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            call.reject("The app is closing", "UNAVAILABLE", e);
        }
    }

    private boolean tryStart(Intent intent) {
        try {
            Activity activity = getActivity();
            if (activity != null) {
                activity.startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        Context context = getContext();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean bool(PluginCall call, String key, boolean fallback) {
        Boolean value = call.getBoolean(key, fallback);
        return value != null ? value : fallback;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isRepoName(String name) {
        return REPO_NAME.matcher(name).matches() && !".".equals(name) && !"..".equals(name);
    }

    /** Git's ref-name rules, limited to characters that are safe in API paths. */
    private static boolean isBranchName(String branch) {
        if (!BRANCH_CHARS.matcher(branch).matches()) {
            return false;
        }
        if (branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")) {
            return false;
        }
        return !branch.contains("..") && !branch.contains("//") && !branch.contains("/.") && !branch.startsWith(".");
    }
}
