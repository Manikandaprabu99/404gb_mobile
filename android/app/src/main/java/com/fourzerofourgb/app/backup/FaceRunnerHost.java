package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.FaceRunnerHost in the background People runner page (web: runner/faces.html,
 * docs/FACES.md "Background"). Synchronous, JSON strings in and out; WebView
 * calls it on its own bridge thread.
 *
 *  - getAuth(): the stored GitHub session, {token, login, owner, repo, branch}
 *    (branch "" = the repo's default), or "null" when signed out.
 *  - config(): {budgetPerHour, deadlineMs, localThumbnails}: thumbnail downloads
 *    from GitHub allowed per hour, when the run must have saved its results
 *    (epoch ms; the host asks the page to stop then), and whether
 *    /_local/thumb/&lt;mediaId&gt; can serve this phone's own copies.
 *  - progress({checked, total}): photos this run has checked so far, out of
 *    those it is working through (shown in the notification).
 *  - finished({checked, reason}): the page has committed what it found and is
 *    done; the host closes it right away. reason: "done" (nothing left to
 *    check), "deadline", "stopped" (after window.__404gbStopRunner()), or what
 *    cut the run short, which the app shows as the last run's error.
 */
public final class FaceRunnerHost {

    static final String NAME = "FaceRunnerHost";
    static final int BUDGET_PER_HOUR = 1500;
    static final String REASON_DONE = "done";
    static final String REASON_DEADLINE = "deadline";
    static final String REASON_STOPPED = "stopped";

    private static final int MAX_REASON_LENGTH = 300;

    interface Listener {
        /** Any call: the page is up and talking to the host. */
        void onCalled();

        void onProgress(int checked, int total);

        /** {@code checked} is -1 when the page didn't say (the last progress count stands). */
        void onFinished(int checked, String reason);
    }

    private final Context context;
    private final long deadlineMs;
    private final boolean localThumbnails;
    private final Listener listener;

    FaceRunnerHost(Context context, long deadlineMs, boolean localThumbnails, Listener listener) {
        this.context = context.getApplicationContext();
        this.deadlineMs = deadlineMs;
        this.localThumbnails = localThumbnails;
        this.listener = listener;
    }

    /** True for the reasons that mean the run ended as planned rather than early. */
    static boolean isPlannedEnd(String reason) {
        return REASON_DONE.equals(reason) || REASON_DEADLINE.equals(reason) || REASON_STOPPED.equals(reason);
    }

    @JavascriptInterface
    public String getAuth() {
        listener.onCalled();
        BackupPrefs.Auth auth = BackupPrefs.get(context).auth();
        if (auth == null || !auth.hasLibrary()) {
            return "null";
        }
        try {
            JSONObject session = new JSONObject();
            session.put("token", auth.token);
            session.put("login", auth.login);
            session.put("owner", auth.owner);
            session.put("repo", auth.repo);
            session.put("branch", auth.branch);
            return session.toString();
        } catch (JSONException e) {
            return "null";
        }
    }

    @JavascriptInterface
    public String config() {
        listener.onCalled();
        try {
            JSONObject config = new JSONObject();
            config.put("budgetPerHour", BUDGET_PER_HOUR);
            config.put("deadlineMs", deadlineMs);
            config.put("localThumbnails", localThumbnails);
            return config.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void progress(String json) {
        listener.onCalled();
        JSONObject data = parse(json);
        listener.onProgress(count(data, "checked"), count(data, "total"));
    }

    @JavascriptInterface
    public void finished(String json) {
        listener.onCalled();
        JSONObject data = parse(json);
        String reason = BackupUtil.optString(data, "reason");
        reason = reason == null ? REASON_DONE : reason.trim();
        if (reason.isEmpty()) {
            reason = REASON_DONE;
        } else if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        listener.onFinished(data != null && data.has("checked") ? count(data, "checked") : -1, reason);
    }

    private static JSONObject parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }

    private static int count(JSONObject data, String key) {
        long value = data == null ? 0L : data.optLong(key, 0L);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
