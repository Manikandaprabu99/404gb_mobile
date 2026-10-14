package com.fourzerofourgb.app.backup;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Background People matching (docs/FACES.md "Background"): runs the export's runner/faces.html
 * in a headless WebView while 404GB is closed; when and how long: BackupScheduler, FaceScanPolicy.
 */
public final class FaceScanWorker extends Worker {

    static final String RUNNER_PAGE = "runner/faces.html";
    /** Input data: what queued the run (for the log). */
    static final String KEY_TRIGGER = "trigger";
    static final String TRIGGER_PERIODIC = "periodic";
    static final String TRIGGER_APP_CLOSED = "app-closed";
    static final String TRIGGER_UPLOADED = "uploaded";
    static final String TRIGGER_FOLLOW_UP = "follow-up";

    /** FaceScanStatus.lastReason for a run that couldn't go on (lastError says why). */
    static final String REASON_ERROR = "error";

    private static final String TAG = "404gb.FaceScanWorker";
    private static final String RUNNER_URL = FaceRunnerAssets.ORIGIN + "/" + RUNNER_PAGE;
    /** After asking the page to stop: how long it may take to commit what it found. */
    private static final long STOP_GRACE_MS = 30_000L;
    /** A page that hasn't called the host by then never will (a broken build). */
    private static final long START_TIMEOUT_MS = 2 * 60_000L;
    private static final long POLL_MS = 500L;
    /** How often a run looks at the charger, the battery and Battery Saver again. */
    private static final long POWER_CHECK_MS = 15_000L;
    private static final long NOTIFICATION_INTERVAL_MS = 1000L;
    private static final long CLOSE_TIMEOUT_MS = 5_000L;
    // "window.__404gbStopRunner && window.__404gbStopRunner()", also telling whether the page has one.
    private static final String STOP_SCRIPT =
        "(function(){var stop=window.__404gbStopRunner;if(typeof stop!=='function')return false;try{stop();}catch(e){}return true;})()";

    /** Why the host, rather than the page, ended a run. */
    private enum Stop {
        WORK,
        APP,
        DEADLINE,
        /** Unplugged, and the battery fell under 30 % or Battery Saver came on. */
        POWER,
        NO_START,
    }

    /** One run at a time in this process (the hourly and a one-time run can be due together). */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Photos the run going now has checked. */
    private static volatile int runChecked;
    /** When the phone's backup last committed new photos (elapsedRealtime), 0 = not in this process. */
    private static volatile long uploadedAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Counted down once: the page finished, failed to load or lost its renderer. */
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicBoolean endedOnce = new AtomicBoolean();
    private volatile boolean pageCalled;
    private volatile boolean foreground;
    private volatile int checked;
    /** finished()'s reason; null until the page calls it. */
    private volatile String reason;
    /** finished()'s photos left (-1: not said) and when downloads are allowed again (epoch ms, 0: no wait). */
    private volatile int left = -1;
    private volatile long resumeAt;
    /** What went wrong on this side; null when nothing did. */
    private volatile String failure;
    /** The FaceScanPolicy reason a Stop.POWER ended the run for. */
    private volatile String powerStop;

    private final Object notificationLock = new Object();
    // Guarded by notificationLock.
    private boolean notificationsDone;
    private long lastNotificationAt;
    private Future<Void> lastNotification;

    // Main thread only.
    private WebView webView;
    private boolean closed;
    private boolean rendererGone;

    private final FaceRunnerHost.Listener hostListener = new FaceRunnerHost.Listener() {
        @Override
        public void onCalled() {
            pageCalled = true;
        }

        @Override
        public void onProgress(int checkedSoFar, int total) {
            if (endedOnce.get()) {
                return;
            }
            checked = checkedSoFar;
            runChecked = checkedSoFar;
            showProgress(checkedSoFar, total);
        }

        @Override
        public void onFinished(int checkedInRun, String why, int photosLeft, long downloadsAt) {
            if (endedOnce.compareAndSet(false, true)) {
                if (checkedInRun >= 0) {
                    checked = checkedInRun;
                    runChecked = checkedInRun;
                }
                left = photosLeft;
                resumeAt = downloadsAt;
                reason = why;
                ended.countDown();
            }
        }
    };

    /** The charger, the battery level and Battery Saver, right now. */
    static final class Power {

        final boolean charging;
        /** 0–100, or -1 when unknown. */
        final int batteryPct;
        final boolean powerSave;

        Power(boolean charging, int batteryPct, boolean powerSave) {
            this.charging = charging;
            this.batteryPct = batteryPct;
            this.powerSave = powerSave;
        }

        static Power read(Context context) {
            boolean charging = false;
            int pct = -1;
            boolean known = false;
            try {
                // The sticky broadcast: no receiver is registered.
                Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    known = true;
                    // Plugged in, like WorkManager's charging constraint (a full battery on the charger counts).
                    charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    pct = FaceScanPolicy.batteryPercent(battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not read the battery state", e);
            }
            if (!known) {
                BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (manager != null) {
                    charging = manager.isCharging();
                    int capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    pct = capacity >= 0 && capacity <= 100 ? capacity : -1;
                }
            }
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return new Power(charging, pct, power != null && power.isPowerSaveMode());
        }

        String skipReason(boolean chargingOnly) {
            return FaceScanPolicy.skipReason(charging, batteryPct, powerSave, chargingOnly);
        }

        @Override
        public String toString() {
            return (charging ? "charging" : "on battery") + ", battery " + (batteryPct >= 0 ? batteryPct + "%" : "unknown") + ", Battery Saver " + (powerSave ? "on" : "off");
        }
    }

    public FaceScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** A background run is going right now (in this process; a new process has none). */
    static boolean isRunning() {
        return RUNNING.get();
    }

    /** The phone's backup committed new photos (any thread): a run going now looks again when it's done. */
    static void noteUploaded() {
        uploadedAt = SystemClock.elapsedRealtime();
    }

    /** FaceScanStatus for the web app (lib/native/backup.ts). Reads WorkManager: not on the main thread. */
    static JSObject status(Context context) {
        BackupPrefs prefs = BackupPrefs.get(context);
        boolean running = RUNNING.get();
        JSObject status = new JSObject();
        status.put("enabled", prefs.faceScanEnabled());
        status.put("chargingOnly", prefs.faceScanChargingOnly());
        status.put("running", running);
        status.put("checkedNow", running ? runChecked : 0);
        status.put("pending", prefs.faceScanEnabled() && BackupScheduler.isFaceScanPending(context));
        long lastRunAt = prefs.faceScanLastRunAt();
        if (lastRunAt > 0) {
            status.put("lastRunAt", lastRunAt);
        } else {
            status.put("lastRunAt", JSONObject.NULL);
        }
        status.put("lastChecked", prefs.faceScanLastChecked());
        putOrNull(status, "lastError", prefs.faceScanLastError());
        putOrNull(status, "lastReason", prefs.faceScanLastReason());
        return status;
    }

    private static void putOrNull(JSObject object, String key, String value) {
        if (value != null) {
            object.put(key, value);
        } else {
            object.put(key, JSONObject.NULL);
        }
    }

    /**
     * After sign-out: forgets everything runner pages stored on their origin
     * (their faces cache and any results not committed yet). The app's own
     * origin and WebView are not touched.
     */
    static void forgetPageData() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebStorage.getInstance().deleteOrigin(FaceRunnerAssets.ORIGIN);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not clear the runner page's storage", e);
            }
        });
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        BackupPrefs prefs = BackupPrefs.get(context);
        String trigger = getInputData().getString(KEY_TRIGGER);
        if (trigger == null) {
            trigger = TRIGGER_PERIODIC;
        }
        BackupPrefs.Auth auth = prefs.auth();
        // Signed out, no library yet, or GitHub refused the token: the app sorts that out first.
        if (!prefs.faceScanEnabled() || auth == null || !auth.hasLibrary() || prefs.needsSignIn()) {
            Log.i(TAG, "Skipped (" + trigger + "): " + (!prefs.faceScanEnabled() ? "turned off" : "no GitHub session with a library"));
            return Result.success();
        }
        // The app's own scanner is at work while it is open; closing it queues the next run.
        if (AppVisibility.isVisible()) {
            Log.i(TAG, "Skipped (" + trigger + "): the app is on screen");
            return Result.success();
        }
        if (!FaceRunnerAssets.hasPage(context, RUNNER_PAGE)) {
            Log.w(TAG, "Skipped (" + trigger + "): " + RUNNER_PAGE + " is not in this build's assets");
            prefs.setFaceScanResult(System.currentTimeMillis(), 0, "This app build can't find people in the background yet", REASON_ERROR);
            return Result.success();
        }
        boolean chargingOnly = prefs.faceScanChargingOnly();
        Power power = Power.read(context);
        String skip = power.skipReason(chargingOnly);
        if (skip != null) {
            // Not an error: the hourly run (or closing the app again) tries again.
            Log.i(TAG, "Skipped (" + trigger + "): " + skip + " (" + power + (chargingOnly ? ", only while charging" : "") + ")");
            prefs.setFaceScanSkipped(skip);
            return Result.success();
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "Skipped (" + trigger + "): another run is going");
            return Result.success();
        }
        runChecked = 0;
        long startedAt = SystemClock.elapsedRealtime();
        Stop stop;
        try {
            stop = run(context, power, chargingOnly, trigger, startedAt);
        } finally {
            RUNNING.set(false);
            runChecked = 0;
        }
        String error = error(stop);
        prefs.setFaceScanResult(System.currentTimeMillis(), checked, error, lastReason(stop, error));
        if (prefs.auth() == null) {
            // Signed out during the run: the page may have written after clearAuth cleared its storage.
            forgetPageData();
            return Result.success();
        }
        followUp(context, stop, error, startedAt);
        return Result.success();
    }

    /** How the run ended, for FaceScanStatus.lastReason. */
    private String lastReason(Stop stop, String error) {
        if (error != null) {
            return REASON_ERROR;
        }
        if (stop == Stop.POWER && powerStop != null) {
            return powerStop;
        }
        String planned = plannedReason(stop);
        return planned != null ? planned : FaceRunnerHost.REASON_STOPPED;
    }

    /** The page's planned end; a host deadline the page answered with "stopped" counts as "deadline". */
    private String plannedReason(Stop stop) {
        String why = reason;
        if (why == null || !FaceRunnerHost.isPlannedEnd(why)) {
            return null;
        }
        if (stop == Stop.DEADLINE && FaceRunnerHost.REASON_STOPPED.equals(why)) {
            return FaceRunnerHost.REASON_DEADLINE;
        }
        return why;
    }

    /** Queues the next run: out of time with photos left, or done while backup added photos. */
    private void followUp(Context context, Stop stop, String error, long startedAt) {
        if (error != null || isStopped() || AppVisibility.isVisible() || stop == Stop.APP || stop == Stop.WORK || stop == Stop.POWER) {
            return;
        }
        String planned = plannedReason(stop);
        boolean charging = Power.read(context).charging;
        long delay;
        if (FaceScanPolicy.shouldFollowUp(planned, left)) {
            delay = FaceScanPolicy.followUpDelayMillis(charging, resumeAt, System.currentTimeMillis());
        } else if (FaceRunnerHost.REASON_DONE.equals(planned) && uploadedAt > startedAt) {
            delay = FaceScanPolicy.SOON_DELAY_MS;
        } else {
            return;
        }
        if (delay < 0) {
            Log.i(TAG, "No follow-up: GitHub downloads are allowed again only after the next hourly run");
            return;
        }
        try {
            BackupScheduler.faceScanFollowUp(context, delay);
            Log.i(TAG, "Follow-up run queued in " + (delay / 1000) + " s (" + (charging ? "charging" : "on battery") + ", " + (left >= 0 ? left + " photos left" : "photos left") + ")");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not queue a follow-up run", e);
        }
    }

    /** One run, start to finish; returns why the host had to end it, or null when the page did. */
    private Stop run(Context context, Power power, boolean chargingOnly, String trigger, long startedAt) {
        foreground = promote(context);
        long runMs = FaceScanPolicy.runMillis(power.charging, foreground);
        Log.i(
            TAG,
            "Run starting (" + trigger + "): " + power + "; up to " + (runMs / 60_000L) + " min" + (foreground ? " as a foreground service" : " as a plain job")
        );
        try {
            BackupLedger.get(context).ensureMediaIdIndex();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not index the ledger by library id", e);
        }
        FaceRunnerAssets assets = new FaceRunnerAssets(context);
        FaceRunnerHost host = new FaceRunnerHost(context, System.currentTimeMillis() + runMs, FaceRunnerAssets.canServeLocal(context), hostListener);
        Stop stop = null;
        try {
            main.post(() -> open(context, assets, host));
            stop = awaitEnd(context, chargingOnly, startedAt, startedAt + runMs);
            if (stop != null && stop != Stop.NO_START && pageCalled) {
                // The page calls finished() once it has committed what it found.
                requestStop();
                awaitEnded(STOP_GRACE_MS);
            }
        } finally {
            finishNotifications();
            close();
        }
        String stopDetail = stop == null ? "" : ", stopped by the host (" + stop + (stop == Stop.WORK ? stopReason() : stop == Stop.POWER ? ", " + powerStop : "") + ")";
        Log.i(
            TAG,
            "Run ended after " +
            ((SystemClock.elapsedRealtime() - startedAt) / 1000L) +
            " s: " +
            checked +
            " photos checked, " +
            (reason != null ? reason : failure != null ? failure : "no answer from the page") +
            (left >= 0 ? ", " + left + " left" : "") +
            stopDetail +
            "; bundled files: " +
            assets.bundledServed() +
            " served, " +
            assets.bundledMissing() +
            " missing" +
            (assets.firstMissing() != null ? " (first: " + assets.firstMissing() + ")" : "") +
            "; local thumbnails: " +
            assets.localServed() +
            " served, " +
            assets.localMissed() +
            " not on this phone"
        );
        return stop;
    }

    private String error(Stop stop) {
        if (failure != null) {
            return failure;
        }
        if (reason != null) {
            return FaceRunnerHost.isPlannedEnd(reason) ? null : reason;
        }
        if (stop == Stop.NO_START) {
            return "The People page didn't start within 2 minutes";
        }
        // Stopped before the page did anything: nothing was lost.
        return pageCalled ? "The People page didn't finish saving within 30 seconds" : null;
    }

    /** Waits for the page to end the run; returns why the host has to end it instead, or null. */
    private Stop awaitEnd(Context context, boolean chargingOnly, long startedAt, long deadlineAt) {
        long deadline = deadlineAt;
        long powerCheckedAt = startedAt;
        while (!awaitEnded(POLL_MS)) {
            long now = SystemClock.elapsedRealtime();
            if (isStopped()) {
                return Stop.WORK;
            }
            if (AppVisibility.isVisible()) {
                return Stop.APP;
            }
            if (now - powerCheckedAt >= POWER_CHECK_MS) {
                powerCheckedAt = now;
                Power power = Power.read(context);
                String skip = power.skipReason(chargingOnly);
                if (skip != null) {
                    Log.i(TAG, "Stopping: " + skip + " (" + power + ")");
                    powerStop = skip;
                    return Stop.POWER;
                }
                long shortened = FaceScanPolicy.deadlineAfterPowerChange(startedAt, deadline, power.charging);
                if (shortened < deadline) {
                    Log.i(TAG, "Unplugged: this run ends " + ((shortened - startedAt) / 60_000L) + " min in");
                    deadline = shortened;
                }
            }
            if (now >= deadline) {
                return Stop.DEADLINE;
            }
            if (!pageCalled && now - startedAt >= START_TIMEOUT_MS) {
                return Stop.NO_START;
            }
        }
        return null;
    }

    /** True once the run has ended, or when the thread is interrupted (no more waiting then). */
    private boolean awaitEnded(long millis) {
        try {
            return ended.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private String stopReason() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? ", reason " + getStopReason() : "";
    }

    private void fail(String message) {
        if (endedOnce.compareAndSet(false, true)) {
            failure = message;
            ended.countDown();
        }
    }

    /** Shows the notification as a foreground service; false when Android doesn't allow one now. */
    private boolean promote(Context context) {
        try {
            setForegroundAsync(foregroundInfo(context, 0, 0)).get(10, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app not exempt from
            // battery optimization) or similar: carry on as a plain job.
            Log.i(TAG, "Running without a foreground service: " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void showProgress(int checkedSoFar, int total) {
        if (!foreground) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (notificationLock) {
            if (notificationsDone || now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) {
                return;
            }
            lastNotificationAt = now;
            try {
                // Updates reuse the running service; failures here are harmless.
                lastNotification = setForegroundAsync(foregroundInfo(getApplicationContext(), checkedSoFar, total));
            } catch (RuntimeException e) {
                // Called from the page's bridge thread, where it must not throw.
                Log.w(TAG, "Could not update the notification", e);
            }
        }
    }

    /** No more updates, and none still in flight: one landing after the run would leave the notification behind. */
    private void finishNotifications() {
        Future<Void> pending;
        synchronized (notificationLock) {
            notificationsDone = true;
            pending = lastNotification;
        }
        if (pending == null) {
            return;
        }
        try {
            pending.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // The update didn't happen; nothing to undo.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ForegroundInfo foregroundInfo(Context context, int checked, int total) {
        Notification notification = BackupNotifications.faces(context, checked, total);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(BackupNotifications.ID_FACES, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(BackupNotifications.ID_FACES, notification);
    }

    // ---- the page (main thread) ----

    @SuppressLint({ "SetJavaScriptEnabled", "JavascriptInterface" })
    private void open(Context context, FaceRunnerAssets assets, FaceRunnerHost host) {
        if (closed) {
            return;
        }
        // Everything on the main thread is caught: an exception here would end the whole app.
        try {
            WebView view = new WebView(context);
            webView = view;
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setGeolocationEnabled(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            view.setWebViewClient(new RunnerClient(assets));
            view.setWebChromeClient(new RunnerChromeClient());
            view.addJavascriptInterface(host, FaceRunnerHost.NAME);
            routeWorkerRequests(assets);
            // Never attached to a window; a phone-sized layout gives the page a normal viewport.
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            view.layout(0, 0, Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
            view.onResume();
            // Process-wide: undoes another WebView's pauseTimers(), which would freeze this page.
            view.resumeTimers();
            view.loadUrl(RUNNER_URL);
        } catch (RuntimeException e) {
            // No Android System WebView, or it is being updated right now.
            Log.w(TAG, "Could not open the runner page", e);
            fail("Android System WebView is unavailable");
        }
    }

    /** Worker requests may go to the process-wide ServiceWorkerClient: answer the runner's origin there too. */
    private static void routeWorkerRequests(FaceRunnerAssets assets) {
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new RunnerServiceWorkerClient(assets));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not route service worker requests", e);
        }
    }

    /** Asks the page to stop and commit; a page without the hook can't, so the run ends at once. */
    private void requestStop() {
        main.post(() -> {
            WebView view = webView;
            if (view == null || closed || rendererGone) {
                return;
            }
            try {
                view.evaluateJavascript(STOP_SCRIPT, value -> {
                    if (!"true".equals(value)) {
                        fail("The People page couldn't be stopped");
                    }
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not ask the runner page to stop", e);
                fail("The People page couldn't be stopped");
            }
        });
    }

    /** Destroys the WebView on the main thread, wherever the page is, and waits briefly for that. */
    private void close() {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            closed = true;
            WebView view = webView;
            webView = null;
            if (view != null) {
                try {
                    if (!rendererGone) {
                        view.stopLoading();
                        view.removeJavascriptInterface(FaceRunnerHost.NAME);
                    }
                    view.destroy();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not close the runner page", e);
                }
            }
            done.countDown();
        });
        try {
            done.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class RunnerClient extends WebViewClient {

        private final FaceRunnerAssets assets;

        RunnerClient(FaceRunnerAssets assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                return assets.intercept(request.getUrl());
            } catch (RuntimeException e) {
                // Thrown back into WebView it would end the app; the page sees a failed request.
                Log.w(TAG, "Could not answer a request of the runner page", e);
                return FaceRunnerAssets.failed();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // The host bridge goes wherever the WebView goes, so it never leaves the runner's origin.
            return !FaceRunnerAssets.isRunnerPage(request.getUrl());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (closed) {
                return;
            }
            String path = request.getUrl().getPath();
            if (request.isForMainFrame()) {
                fail("The People page couldn't load (HTTP " + response.getStatusCode() + ")");
            } else if (path != null && !path.startsWith(FaceRunnerAssets.LOCAL_THUMB_PATH)) {
                // A 404 here usually means a file missing from the bundled export.
                Log.w(TAG, "HTTP " + response.getStatusCode() + " for " + request.getUrl().getHost() + path);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (closed) {
                return;
            }
            if (request.isForMainFrame()) {
                fail("The People page couldn't load: " + error.getDescription());
            } else {
                Log.w(TAG, "Request failed: " + request.getUrl().getHost() + request.getUrl().getPath() + ": " + error.getDescription());
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Handled here, or Android would end the whole app; the view is only good for destroy() now.
            rendererGone = true;
            fail(detail.didCrash() ? "The People page crashed" : "Android stopped the People page to free memory");
            return true;
        }
    }

    /** The runner's origin for service worker (and possibly dedicated worker) requests; the rest as Capacitor does. */
    private static final class RunnerServiceWorkerClient extends ServiceWorkerClient {

        private final FaceRunnerAssets assets;

        RunnerServiceWorkerClient(FaceRunnerAssets assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
            try {
                Uri url = request.getUrl();
                if (FaceRunnerAssets.DOMAIN.equalsIgnoreCase(url.getHost())) {
                    return assets.intercept(url);
                }
                BackupPlugin plugin = BackupPlugin.live();
                Bridge bridge = plugin == null ? null : plugin.getBridge();
                if (bridge != null && bridge.getConfig().isResolveServiceWorkerRequests() && bridge.getLocalServer() != null) {
                    return bridge.getLocalServer().shouldInterceptRequest(request);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not answer a service worker request", e);
            }
            return null;
        }
    }

    /** The page's console goes to logcat; dialogs are dismissed, nobody is there to answer them. */
    private static final class RunnerChromeClient extends WebChromeClient {

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            ConsoleMessage.MessageLevel level = message.messageLevel();
            String line = "Page: " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
            if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(TAG, line);
            } else {
                Log.d(TAG, line);
            }
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            result.cancel();
            return true;
        }
    }
}
