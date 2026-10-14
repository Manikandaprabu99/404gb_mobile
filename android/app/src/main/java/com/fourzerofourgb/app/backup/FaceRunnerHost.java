package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.FaceRunnerHost for the background People runner page (contract: web docs/FACES.md
 * "Background"); synchronous JSON strings, called on WebView's bridge thread.
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

        /** -1 for {@code checked}/{@code left} the page didn't send; {@code resumeAt} 0 = no download wait. */
        void onFinished(int checked, String reason, int left, long resumeAt);
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
        int checked = data != null && data.has("checked") ? count(data, "checked") : -1;
        int left = data != null && data.has("left") ? count(data, "left") : -1;
        long resumeAt = data == null ? 0L : Math.max(0L, data.optLong("resumeAt", 0L));
        listener.onFinished(checked, reason, left, resumeAt);
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
