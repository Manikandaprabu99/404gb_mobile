package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.os.SystemClock;
import com.getcapacitor.CapConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal client for the 404GB web API. Authenticates with the WebView's
 * session cookie, writes renewed cookies back, retries transient failures with
 * exponential backoff, and turns 401/429 into typed exceptions.
 */
final class ApiClient {

    interface StopSignal {
        boolean isStopped();
    }

    interface WaitListener {
        void onWaiting(boolean waiting);
    }

    static class ApiException extends IOException {

        ApiException(String message) {
            super(message);
        }
    }

    /** 401: the session expired or the app was never signed in. */
    static final class AuthRequiredException extends ApiException {

        AuthRequiredException() {
            super("Sign-in required");
        }
    }

    /** 429 with a Retry-After longer than is worth sleeping through in place. */
    static final class RateLimitedException extends ApiException {

        final long retryAfterSeconds;

        RateLimitedException(long retryAfterSeconds) {
            super("Rate limited for " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** Any other non-2xx response (5xx only after the in-request retries ran out). */
    static final class HttpStatusException extends ApiException {

        final int status;
        /** The API's machine-readable error code ("missing_chunks", "unknown_blob", ...), or "". */
        final String code;
        /** Parsed JSON error body, or null when the body wasn't JSON. */
        final JSONObject body;

        HttpStatusException(int status, String message) {
            this(status, message, "", null);
        }

        HttpStatusException(int status, String message, String code, JSONObject body) {
            super("HTTP " + status + (message == null || message.isEmpty() ? "" : ": " + message));
            this.status = status;
            this.code = code == null ? "" : code;
            this.body = body;
        }

        boolean isServerError() {
            return status >= 500 || status == 408;
        }
    }

    /** The worker was stopped by WorkManager; give up quickly. */
    static final class StoppedException extends IOException {

        StoppedException() {
            super("Stopped");
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_ATTEMPTS = 4;
    private static final int MAX_RATE_LIMIT_WAITS = 5;
    private static final long MAX_INLINE_RETRY_AFTER_SECONDS = 60;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    private final Context context;
    private final String baseUrl;
    private final StopSignal stop;
    private final Random random = new Random();
    private WaitListener waitListener;
    private boolean authConfirmed;

    ApiClient(Context context, StopSignal stop) {
        this.context = context.getApplicationContext();
        this.stop = stop;
        String url = CapConfig.loadDefault(this.context).getServerUrl();
        while (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url == null || url.isEmpty() ? null : url;
    }

    void setWaitListener(WaitListener listener) {
        this.waitListener = listener;
    }

    JSONObject getJson(String path) throws IOException {
        return parseJson(send("GET", path, null, null, 0, MAX_JSON_BYTES));
    }

    JSONObject postJson(String path, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        return parseJson(send("POST", path, "application/json", bytes, bytes.length, MAX_JSON_BYTES));
    }

    /** Uploads one chunk (or thumbnail) as raw bytes and returns its git blob sha. */
    String uploadChunk(byte[] data, int length, String sha256Hex) throws IOException {
        JSONObject res = parseJson(
            send("POST", "/api/media/upload/chunk?hash=" + sha256Hex, "application/octet-stream", data, length, MAX_JSON_BYTES)
        );
        String sha = res.optString("sha", "");
        if (sha.isEmpty() || res.isNull("sha")) {
            throw new HttpStatusException(502, "chunk upload response had no sha");
        }
        return sha;
    }

    byte[] getBytes(String path, int maxBytes) throws IOException {
        return send("GET", path, null, null, 0, maxBytes).body;
    }

    private static final class Response {

        final int status;
        final byte[] body;
        final Map<String, List<String>> headers;

        Response(int status, byte[] body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    private Response send(String method, String path, String contentType, byte[] body, int bodyLength, int maxBytes)
        throws IOException {
        int attempt = 0;
        int rateLimitWaits = 0;
        while (true) {
            checkStopped();
            attempt++;
            Response response;
            try {
                response = sendOnce(method, path, contentType, body, bodyLength, maxBytes);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                sleep(backoffMillis(attempt));
                continue;
            }

            int status = response.status;
            if (status >= 200 && status < 300) {
                onAuthenticated();
                return response;
            }
            if (status == 401) {
                throw new AuthRequiredException();
            }
            // 429 = GitHub rate limit; 503 + Retry-After = library busy with another commit.
            if (status == 429 || (status == 503 && !headerValues(response.headers, "Retry-After").isEmpty())) {
                long wait = retryAfterSeconds(response.headers);
                if (wait <= MAX_INLINE_RETRY_AFTER_SECONDS && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                    rateLimitWaits++;
                    attempt--;
                    setWaiting(true);
                    try {
                        sleep(wait * 1000L);
                    } finally {
                        setWaiting(false);
                    }
                    continue;
                }
                throw new RateLimitedException(wait);
            }
            if ((status >= 500 || status == 408) && attempt < MAX_ATTEMPTS) {
                sleep(backoffMillis(attempt));
                continue;
            }
            JSONObject errorBody = jsonOrNull(response.body);
            throw new HttpStatusException(status, errorMessage(response.body), optString(errorBody, "code"), errorBody);
        }
    }

    /** String value of {@code key}, or null when absent, JSON null or empty. */
    static String optString(JSONObject json, String key) {
        if (json == null || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static JSONObject jsonOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return null;
        }
    }

    private Response sendOnce(String method, String path, String contentType, byte[] body, int bodyLength, int maxBytes)
        throws IOException {
        if (baseUrl == null) {
            throw new IOException("No server.url in capacitor.config.json");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        boolean ok = false;
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json, application/octet-stream");
            conn.setRequestProperty("X-Requested-With", "404gb-android");
            String cookie = WebCookies.cookieHeader(baseUrl);
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(bodyLength);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body, 0, bodyLength);
                }
            }
            int status = conn.getResponseCode();
            Map<String, List<String>> headers = conn.getHeaderFields();
            WebCookies.store(baseUrl, headerValues(headers, "Set-Cookie"));
            byte[] bytes = readBody(conn, status, maxBytes);
            ok = true;
            return new Response(status, bytes, headers);
        } finally {
            // On success the fully read stream is closed and the connection can be reused.
            if (!ok) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readBody(HttpURLConnection conn, int status, int maxBytes) throws IOException {
        boolean error = status >= 400;
        InputStream in;
        if (error) {
            in = conn.getErrorStream();
        } else {
            in = conn.getInputStream();
        }
        if (in == null) {
            return new byte[0];
        }
        int limit = error ? MAX_ERROR_BYTES : maxBytes;
        try {
            int expected = conn.getContentLength();
            ByteArrayOutputStream out = new ByteArrayOutputStream(expected > 0 && expected <= limit ? expected : 16 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (total + n > limit) {
                    if (error) {
                        out.write(buffer, 0, limit - total);
                        break;
                    }
                    throw new HttpStatusException(502, "response larger than " + limit + " bytes");
                }
                out.write(buffer, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            BackupUtil.closeQuietly(in);
        }
    }

    private static JSONObject parseJson(Response response) throws HttpStatusException {
        try {
            return new JSONObject(new String(response.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new HttpStatusException(502, "malformed JSON response");
        }
    }

    private static String errorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            JSONObject json = new JSONObject(text);
            String error = json.optString("error", "");
            if (!error.isEmpty() && !json.isNull("error")) {
                return error;
            }
        } catch (JSONException ignored) {
            // Not JSON (e.g. a proxy error page); fall through to the raw text.
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static List<String> headerValues(Map<String, List<String>> headers, String name) {
        List<String> values = new ArrayList<>();
        if (headers == null) {
            return values;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                values.addAll(entry.getValue());
            }
        }
        return values;
    }

    private static long retryAfterSeconds(Map<String, List<String>> headers) {
        List<String> values = headerValues(headers, "Retry-After");
        long seconds = 60;
        if (!values.isEmpty() && values.get(0) != null) {
            String value = values.get(0).trim();
            try {
                seconds = Long.parseLong(value);
            } catch (NumberFormatException notSeconds) {
                try {
                    SimpleDateFormat httpDate = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                    Date when = httpDate.parse(value);
                    if (when != null) {
                        seconds = (when.getTime() - System.currentTimeMillis()) / 1000L;
                    }
                } catch (ParseException ignored) {
                    // Keep the default.
                }
            }
        }
        return Math.max(1L, Math.min(seconds, 3600L));
    }

    private void onAuthenticated() {
        if (authConfirmed) {
            return;
        }
        authConfirmed = true;
        BackupPrefs prefs = BackupPrefs.get(context);
        if (prefs.needsSignIn() || prefs.signInNotified()) {
            prefs.setNeedsSignIn(false);
            prefs.setSignInNotified(false);
            BackupNotifications.clearSignInNeeded(context);
            BackupStatus.changed(context);
        }
    }

    private void setWaiting(boolean waiting) {
        WaitListener listener = waitListener;
        if (listener != null) {
            listener.onWaiting(waiting);
        }
    }

    private long backoffMillis(int attempt) {
        long base = Math.min(30_000L, 1000L << Math.min(attempt, 5));
        return base + random.nextInt(500);
    }

    private void checkStopped() throws StoppedException {
        if (stop != null && stop.isStopped()) {
            throw new StoppedException();
        }
    }

    private void sleep(long millis) throws StoppedException {
        long end = SystemClock.elapsedRealtime() + millis;
        while (true) {
            checkStopped();
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 500L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoppedException();
            }
        }
    }
}
