package com.fourzerofourgb.app.backup;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
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
import com.getcapacitor.JSObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Background People matching (web: docs/FACES.md "Background"). About once an
 * hour while the phone charges and 404GB is closed, runs the web app's own
 * face scanner headlessly: the bundled export's runner/faces.html in a private
 * WebView on its own origin ({@link FaceRunnerAssets}; no Capacitor, nothing
 * shared with the app's WebView), which gets the GitHub session and its limits
 * from {@link FaceRunnerHost} and commits what it finds to faces.json like the
 * app does.
 *
 * A run ends when the page says it has finished. Otherwise the page is asked
 * to stop, and gets up to 30 s to commit what it found, at the run's deadline,
 * when WorkManager stops the work (unplugged, turned off, signed out) or as
 * soon as the app comes to the foreground, whose own scanner takes over; a run
 * never starts while the app is on screen. It runs as a dataSync foreground
 * service with a quiet notification; when Android refuses one, it runs as a
 * plain job, which Android stops after 10 minutes, so its deadline comes
 * before that.
 */
public final class FaceScanWorker extends Worker {

    static final String RUNNER_PAGE = "runner/faces.html";

    private static final String TAG = "404gb.FaceScanWorker";
    private static final String RUNNER_URL = FaceRunnerAssets.ORIGIN + "/" + RUNNER_PAGE;
    private static final long RUN_MS = 20 * 60_000L;
    private static final long RUN_MS_AS_JOB = 9 * 60_000L;
    /** After asking the page to stop: how long it may take to commit what it found. */
    private static final long STOP_GRACE_MS = 30_000L;
    /** A page that hasn't called the host by then never will (a broken build). */
    private static final long START_TIMEOUT_MS = 2 * 60_000L;
    private static final long POLL_MS = 500L;
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
        NO_START,
    }

    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Counted down once: the page finished, failed to load or lost its renderer. */
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicBoolean endedOnce = new AtomicBoolean();
    private volatile boolean pageCalled;
    private volatile boolean foreground;
    private volatile int checked;
    /** finished()'s reason; null until the page calls it. */
    private volatile String reason;
    /** What went wrong on this side; null when nothing did. */
    private volatile String failure;

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
            showProgress(checkedSoFar, total);
        }

        @Override
        public void onFinished(int checkedInRun, String why) {
            if (endedOnce.compareAndSet(false, true)) {
                if (checkedInRun >= 0) {
                    checked = checkedInRun;
                }
                reason = why;
                ended.countDown();
            }
        }
    };

    public FaceScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** A background run is going right now (in this process; a new process has none). */
    static boolean isRunning() {
        return running;
    }

    /** FaceScanStatus for the web app (lib/native/backup.ts). */
    static JSObject status(Context context) {
        BackupPrefs prefs = BackupPrefs.get(context);
        JSObject status = new JSObject();
        status.put("enabled", prefs.faceScanEnabled());
        status.put("running", running);
        long lastRunAt = prefs.faceScanLastRunAt();
        if (lastRunAt > 0) {
            status.put("lastRunAt", lastRunAt);
        } else {
            status.put("lastRunAt", JSONObject.NULL);
        }
        status.put("lastChecked", prefs.faceScanLastChecked());
        String error = prefs.faceScanLastError();
        if (error != null) {
            status.put("lastError", error);
        } else {
            status.put("lastError", JSONObject.NULL);
        }
        return status;
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
        BackupPrefs.Auth auth = prefs.auth();
        // Signed out, no library yet, or GitHub refused the token: the app sorts that out first.
        if (!prefs.faceScanEnabled() || auth == null || !auth.hasLibrary() || prefs.needsSignIn()) {
            return Result.success();
        }
        // The app's own scanner is at work while it is open; the next period tries again.
        if (AppVisibility.isVisible()) {
            return Result.success();
        }
        if (!FaceRunnerAssets.hasPage(context, RUNNER_PAGE)) {
            prefs.setFaceScanResult(System.currentTimeMillis(), 0, "This app build can't find people in the background yet");
            return Result.success();
        }
        running = true;
        String error;
        try {
            error = run(context);
        } finally {
            running = false;
        }
        prefs.setFaceScanResult(System.currentTimeMillis(), checked, error);
        if (prefs.auth() == null) {
            // Signed out during the run: the page may have written after clearAuth cleared its storage.
            forgetPageData();
        }
        return Result.success();
    }

    /** One run, start to finish; returns why it stopped early, or null. */
    private String run(Context context) {
        foreground = promote(context);
        long runMs = foreground ? RUN_MS : RUN_MS_AS_JOB;
        long startedAt = SystemClock.elapsedRealtime();
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
            stop = awaitEnd(startedAt, startedAt + runMs);
            if (stop != null && stop != Stop.NO_START && pageCalled) {
                // The page calls finished() once it has committed what it found.
                requestStop();
                awaitEnded(STOP_GRACE_MS);
            }
        } finally {
            finishNotifications();
            close();
        }
        String stopDetail = stop == null ? "" : ", stopped by the host (" + stop + (stop == Stop.WORK ? stopReason() : "") + ")";
        Log.i(
            TAG,
            "Run ended: " +
            checked +
            " photos checked, " +
            (reason != null ? reason : failure != null ? failure : "no answer from the page") +
            stopDetail +
            "; local thumbnails: " +
            assets.localServed() +
            " served, " +
            assets.localMissed() +
            " not on this phone"
        );
        return error(stop);
    }

    private String error(Stop stop) {
        if (failure != null) {
            return failure;
        }
        if (reason != null) {
            return FaceRunnerHost.isPlannedEnd(reason) ? null : reason;
        }
        if (stop == Stop.NO_START) {
            return "The People page didn't start";
        }
        // Stopped before the page did anything: nothing was lost.
        return pageCalled ? "The People page didn't finish saving in time" : null;
    }

    /** Waits for the page to end the run; returns why the host has to end it instead, or null. */
    private Stop awaitEnd(long startedAt, long deadlineAt) {
        while (!awaitEnded(POLL_MS)) {
            long now = SystemClock.elapsedRealtime();
            if (isStopped()) {
                return Stop.WORK;
            }
            if (AppVisibility.isVisible()) {
                return Stop.APP;
            }
            if (now >= deadlineAt) {
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
            // Never attached to a window (WebView counts one that never was as visible while it
            // isn't paused); a phone-sized layout gives the page a normal viewport.
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            view.layout(0, 0, Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
            view.onResume();
            // Process-wide: undoes a pauseTimers() by another WebView (Capacitor does that only
            // with KeepRunning off), which would freeze this page.
            view.resumeTimers();
            view.loadUrl(RUNNER_URL);
        } catch (RuntimeException e) {
            // No Android System WebView, or it is being updated right now.
            Log.w(TAG, "Could not open the runner page", e);
            fail("Android System WebView is unavailable");
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
            if (request.isForMainFrame() && !closed) {
                fail("The People page couldn't load (HTTP " + response.getStatusCode() + ")");
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && !closed) {
                fail("The People page couldn't load: " + error.getDescription());
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
