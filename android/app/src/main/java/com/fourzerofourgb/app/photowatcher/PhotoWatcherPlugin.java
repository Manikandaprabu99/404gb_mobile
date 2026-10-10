package com.fourzerofourgb.app.photowatcher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JS-facing bridge for the native photo-watcher. Exposes start()/stop()/
 * getStatus()/readAsset() to the web app running in the Capacitor WebView,
 * and is the hand-off point {@link PhotoWatcherService} calls into
 * directly (see {@link #getActiveInstance()}) to deliver newly-detected
 * MediaStore rows as 'newAsset' events.
 */
@CapacitorPlugin(
    name = "PhotoWatcher",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }, alias = "media"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "mediaLegacy"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications"),
    }
)
public class PhotoWatcherPlugin extends Plugin {

    private static final String TAG = "PhotoWatcherPlugin";
    private static final String WATCHDOG_WORK_NAME = "404gb-photowatcher-watchdog";
    private static final long WATCHDOG_INTERVAL_MINUTES = 15; // WorkManager's platform floor for periodic work

    // content:// URIs this session has itself surfaced to JS via
    // notifyNewAsset(), and therefore the only ones readAsset() will open.
    // Without this allow-list, readAsset (a public @PluginMethod reachable
    // by any script running on the loaded page) would be an unrestricted
    // "read any MediaStore row" primitive, since the app holds collection-
    // wide READ_MEDIA_IMAGES/READ_MEDIA_VIDEO grants rather than per-item
    // ones. Bounded + LRU-ordered so a long-running watch session can't
    // grow this without limit; eviction just means a very old, presumably
    // already-read asset stops being independently readable a second time.
    private static final int MAX_TRACKED_URIS = 500;
    private static final Set<String> surfacedUris = Collections.synchronizedSet(
        Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(MAX_TRACKED_URIS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRACKED_URIS;
                }
            }
        )
    );

    // Set by load()/handleOnDestroy() of whichever instance is currently
    // wired to a live Bridge. The foreground service keeps this whole app
    // process (and therefore the WebView's JS engine) alive under normal
    // circumstances, so it's safe — and much simpler than a headless
    // second WebView — for the service to reach across to this instance
    // directly and call notifyListeners() on it.
    private static volatile PhotoWatcherPlugin activeInstance;

    static PhotoWatcherPlugin getActiveInstance() {
        return activeInstance;
    }

    @Override
    public void load() {
        activeInstance = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        List<String> aliasesNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33) {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!allGranted(perms)) {
                aliasesNeeded.add("media");
                aliasesNeeded.add("notifications");
            }
        } else {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (!allGranted(perms)) {
                aliasesNeeded.add("mediaLegacy");
            }
        }

        if (aliasesNeeded.isEmpty()) {
            beginWatching(call);
        } else {
            requestPermissionForAliases(aliasesNeeded.toArray(new String[0]), call, "onPermissionResult");
        }
    }

    @PermissionCallback
    private void onPermissionResult(PluginCall call) {
        boolean granted;
        if (Build.VERSION.SDK_INT >= 33) {
            // POST_NOTIFICATIONS is requested alongside the media
            // permissions but intentionally doesn't gate starting the
            // watch — it only controls whether the required foreground-
            // service notification is visible or silent, not whether
            // MediaStore can be read.
            granted =
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            granted =
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        if (granted) {
            beginWatching(call);
        } else {
            call.reject("Photo/media permission was not granted");
        }
    }

    private boolean allGranted(List<String> perms) {
        for (String perm : perms) {
            if (ContextCompat.checkSelfPermission(getContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void beginWatching(PluginCall call) {
        Context context = getContext();
        PhotoWatcherPrefs.setEnabled(context, true);

        Intent serviceIntent = new Intent(context, PhotoWatcherService.class);
        serviceIntent.setAction(PhotoWatcherService.ACTION_RESCAN);
        ContextCompat.startForegroundService(context, serviceIntent);

        scheduleWatchdog(context);
        requestIgnoreBatteryOptimizations();

        JSObject ret = new JSObject();
        ret.put("enabled", true);
        call.resolve(ret);
    }

    /**
     * Best-effort prompt for the one documented Android 12+ background-FGS-
     * start exemption that fits this feature: an app the user has excluded
     * from battery optimizations may call startForegroundService() from the
     * background (e.g. from {@link PhotoWatcherWatchdogWorker}'s periodic
     * job) without being refused. Not requesting/getting this exemption
     * doesn't break the feature — the watchdog's try/catch means a refusal
     * there is just a missed restart, recovered on the next tick or next
     * app open — so failures here (dialog declined, OEM disallows the
     * intent, no foreground Activity to launch it from, etc.) are logged
     * and otherwise ignored rather than surfaced to the caller.
     */
    private void requestIgnoreBatteryOptimizations() {
        Context context = getContext();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
            return;
        }

        Activity activity = getActivity();
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        try {
            if (activity != null) {
                activity.startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not request battery-optimization exemption (non-fatal)", e);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context context = getContext();
        PhotoWatcherPrefs.setEnabled(context, false);
        context.stopService(new Intent(context, PhotoWatcherService.class));
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK_NAME);

        JSObject ret = new JSObject();
        ret.put("enabled", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", PhotoWatcherPrefs.isEnabled(getContext()));
        call.resolve(ret);
    }

    /**
     * Opens a content:// URI (as handed to JS in a 'newAsset' event) and
     * resolves with its bytes base64-encoded plus its mime type — this is
     * how JS actually gets the bytes to build a File/Blob, since a WebView
     * can't read a content:// URI itself.
     */
    @PluginMethod
    public void readAsset(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null) {
            call.reject("Missing 'uri'");
            return;
        }

        // readAsset is a public @PluginMethod, reachable by any script
        // running on the loaded page — never trust a caller-supplied URI
        // on its own. Only open URIs this session has itself surfaced via
        // notifyNewAsset(); anything else (e.g. an attacker enumerating
        // content://media/external/images/media/<id>) is rejected here,
        // before ever touching the ContentResolver.
        if (!surfacedUris.contains(uriString)) {
            call.reject("Unrecognized asset: " + uriString);
            return;
        }

        Uri uri = Uri.parse(uriString);
        ContentResolver resolver = getContext().getContentResolver();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                call.reject("Could not open asset: " + uriString);
                return;
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }

            String mimeType = resolver.getType(uri);
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            JSObject ret = new JSObject();
            ret.put("data", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP));
            ret.put("mimeType", mimeType);
            call.resolve(ret);
        } catch (IOException | SecurityException e) {
            call.reject("Failed to read asset: " + e.getMessage(), e);
        }
    }

    /**
     * Called by {@link PhotoWatcherService} (on its own worker thread,
     * never the main thread) whenever MediaStore reports a new image/video
     * row past the stored watermark.
     */
    void notifyNewAsset(String contentUri, String displayName, String mimeType) {
        surfacedUris.add(contentUri);
        JSObject data = new JSObject();
        data.put("uri", contentUri);
        data.put("displayName", displayName);
        data.put("mimeType", mimeType == null ? "application/octet-stream" : mimeType);
        // retainUntilConsumed=true: if JS hasn't called addListener('newAsset', ...)
        // yet (e.g. this fired a moment before the page finished loading),
        // Capacitor holds the event and delivers it as soon as a listener
        // attaches, instead of dropping it silently.
        notifyListeners("newAsset", data, true);
    }

    private void scheduleWatchdog(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            PhotoWatcherWatchdogWorker.class,
            WATCHDOG_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).build();
        // KEEP: don't reset an already-scheduled watchdog's timer just
        // because start() was called again (e.g. the app was reopened).
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCHDOG_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
