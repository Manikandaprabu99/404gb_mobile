package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The GitHub REST API for the signed-in user's library repo, called straight
 * from the phone (web repo: docs/STANDALONE.md). Requests carry the stored
 * token; network failures and 5xx are retried with backoff; GitHub's primary
 * and secondary rate limits become waits; everything the caller has to act on
 * is a typed exception. Content-creating requests are paced under GitHub's
 * per-minute limit, and blobs are base64-encoded as they stream out.
 */
final class GitHubClient {

    interface StopSignal {
        boolean isStopped();
    }

    interface WaitListener {
        void onWaiting(boolean waiting);
    }

    /** A GitHub answer the caller has to act on; the client never retries these itself. */
    static class GitHubException extends IOException {

        GitHubException(String message) {
            super(message);
        }

        GitHubException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 401, or a 403 that isn't a rate limit: the token was revoked or expired,
     * or it may not write to the repo. Signing in again fixes both.
     */
    static final class AuthRequiredException extends GitHubException {

        final int status;

        AuthRequiredException(int status, String message) {
            super("GitHub refused the sign-in (HTTP " + status + (message == null || message.isEmpty() ? "" : ": " + message) + ")");
            this.status = status;
        }
    }

    /** A rate limit longer than is worth sleeping through in place. */
    static final class RateLimitedException extends GitHubException {

        final long retryAfterSeconds;
        /** True when GitHub said how long to wait; false when the default minute is a guess. */
        final boolean explicit;

        RateLimitedException(long retryAfterSeconds, boolean explicit) {
            super("Rate limited for " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
            this.explicit = explicit;
        }
    }

    /** Any other unexpected status (5xx only after the in-request retries ran out). */
    static final class HttpStatusException extends GitHubException {

        final int status;

        HttpStatusException(int status, String message) {
            super("HTTP " + status + (message == null || message.isEmpty() ? "" : ": " + message));
            this.status = status;
        }

        boolean isServerError() {
            return status >= 500 || status == 408;
        }
    }

    /** The repo or branch is missing, empty or renamed, or this token can't write to it. */
    static final class LibraryUnavailableException extends GitHubException {

        LibraryUnavailableException(String message) {
            super(message);
        }
    }

    /** POST /git/trees answered 422/404, which usually means a referenced blob is unknown to GitHub. */
    static final class TreeRejectedException extends GitHubException {

        final int status;

        TreeRejectedException(int status, String message) {
            super("GitHub rejected the new tree (HTTP " + status + (message == null || message.isEmpty() ? "" : ": " + message) + ")");
            this.status = status;
        }
    }

    /** The stored session changed (signed out, another token, repo or branch) while this client worked. */
    static final class AuthChangedException extends GitHubException {

        AuthChangedException() {
            super("The GitHub sign-in changed");
        }
    }

    /** The worker was stopped by WorkManager; give up quickly. */
    static final class StoppedException extends IOException {

        StoppedException() {
            super("Stopped");
        }
    }

    /** This phone's HTTP stack refused the request method before anything was sent. */
    private static final class MethodUnsupportedException extends GitHubException {

        MethodUnsupportedException(String method, Throwable cause) {
            super(method + " is not supported by this HTTP stack", cause);
        }
    }

    private static final class GraphQlException extends GitHubException {

        GraphQlException(String message) {
            super(message);
        }
    }

    /** A raw file read: OK (bytes in memory, or written to the target file), NOT_MODIFIED or MISSING. */
    static final class RawFile {

        static final int OK = 0;
        static final int NOT_MODIFIED = 1;
        static final int MISSING = 2;

        final int kind;
        /** The content for in-memory OK reads; null otherwise. */
        final byte[] bytes;
        final String etag;

        RawFile(int kind, byte[] bytes, String etag) {
            this.kind = kind;
            this.bytes = bytes;
            this.etag = etag;
        }
    }

    /** The branch head commit, with the ETag to revalidate it by (null when unknown). */
    static final class Head {

        final String sha;
        final String etag;

        Head(String sha, String etag) {
            this.sha = sha;
            this.etag = etag;
        }
    }

    private static final String TAG = "404gb.GitHubClient";
    private static final String API = "https://api.github.com";
    private static final String MEDIA_JSON = "application/vnd.github+json";
    private static final String MEDIA_RAW = "application/vnd.github.raw";
    private static final String API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "404GB-Android";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_ATTEMPTS = 4;
    // Short waits are slept through in place; longer ones end the run and let
    // WorkManager start a new one later, so the phone isn't kept awake waiting.
    private static final int MAX_RATE_LIMIT_WAITS = 3;
    private static final long MAX_INLINE_WAIT_SECONDS = 20;
    private static final long MIN_RETRY_SECONDS = 1;
    private static final long MAX_RETRY_SECONDS = 3600;
    // GitHub's guidance when a secondary limit gives no retry-after: wait at least a minute.
    private static final long DEFAULT_RETRY_SECONDS = 60;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    // At most ~75 a minute, under GitHub's secondary limit of about 80 content-creating requests a minute.
    private static final long MIN_CONTENT_INTERVAL_MS = 800;
    // A multiple of 3, so no partial base64 group is carried between writes.
    private static final int BASE64_SLICE = 48 * 1024;
    private static final byte[] BLOB_PREFIX = "{\"encoding\":\"base64\",\"content\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOB_SUFFIX = "\"}".getBytes(StandardCharsets.UTF_8);
    private static final Pattern GIT_SHA = Pattern.compile("^[0-9a-f]{40}(?:[0-9a-f]{24})?$");
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern RATE_LIMIT_MESSAGE = Pattern.compile("rate limit|abuse detection", Pattern.CASE_INSENSITIVE);

    private static final Object PACE_LOCK = new Object();
    private static long lastContentRequestAt;
    /** Set once PATCH was refused locally, so later branch updates go straight to GraphQL. */
    private static volatile boolean patchUnsupported;

    private final Context context;
    private final BackupPrefs prefs;
    private final BackupPrefs.Auth auth;
    private final StopSignal stop;
    private final String repoPath;
    private final Random random = new Random();
    private WaitListener waitListener;
    private boolean authConfirmed;
    private String refNodeId;

    GitHubClient(Context context, BackupPrefs.Auth auth, StopSignal stop) {
        this.context = context.getApplicationContext();
        this.prefs = BackupPrefs.get(this.context);
        this.auth = auth;
        this.stop = stop;
        this.repoPath = "/repos/" + encode(auth.owner) + "/" + encode(auth.repo);
    }

    void setWaitListener(WaitListener listener) {
        this.waitListener = listener;
    }

    BackupPrefs.Auth auth() {
        return auth;
    }

    // ---- reads ----

    /** GET /git/ref/heads/{branch}; with {@code known}'s ETag an unmoved branch costs a free 304. */
    Head head(Head known) throws IOException {
        Request request = Request.get(repoPath + "/git/ref/heads/" + encodePath(auth.branch)).passThrough(404, 409);
        if (known != null) {
            request.etag = known.etag;
        }
        Response response = send(request);
        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED && known != null) {
            return known;
        }
        if (response.status == 404 || response.status == 409) {
            throw new LibraryUnavailableException("Branch \"" + auth.branch + "\" of " + auth.library() + " doesn't exist, or the repo is empty");
        }
        return new Head(sha(response.json().optJSONObject("object"), "sha"), response.etag);
    }

    /** The root tree sha of a commit. */
    String commitTree(String commitSha) throws IOException {
        return sha(send(Request.get(repoPath + "/git/commits/" + commitSha)).json().optJSONObject("tree"), "sha");
    }

    /** The repo's default branch, for a session stored without one. */
    String defaultBranch() throws IOException {
        Response response = send(Request.get(repoPath).passThrough(404));
        if (response.status == 404) {
            throw new LibraryUnavailableException(auth.library() + " doesn't exist, or this sign-in can't see it");
        }
        String branch = BackupUtil.optString(response.json(), "default_branch");
        if (branch == null) {
            throw new HttpStatusException(502, "GitHub's answer had no default branch");
        }
        return branch;
    }

    /**
     * Raw bytes of {@code path} at {@code ref} (the default branch when empty)
     * via the contents API, which serves raw files up to 100 MB.
     */
    RawFile readRaw(String path, String ref, String etag, int maxBytes) throws IOException {
        Request request = rawRequest(path, ref, etag);
        request.maxBytes = maxBytes;
        return rawResult(send(request));
    }

    /** Like {@link #readRaw}, but streams an OK body into {@code target} (for the big manifest). */
    RawFile readRawToFile(String path, String ref, String etag, File target) throws IOException {
        Request request = rawRequest(path, ref, etag);
        request.sink = target;
        return rawResult(send(request));
    }

    private Request rawRequest(String path, String ref, String etag) {
        String query = ref == null || ref.isEmpty() ? "" : "?ref=" + encode(ref);
        Request request = Request.get(repoPath + "/contents/" + encodePath(path) + query).passThrough(404);
        request.accept = MEDIA_RAW;
        request.etag = etag;
        return request;
    }

    private static RawFile rawResult(Response response) {
        if (response.status == 404) {
            return new RawFile(RawFile.MISSING, null, null);
        }
        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return new RawFile(RawFile.NOT_MODIFIED, null, response.etag);
        }
        return new RawFile(RawFile.OK, response.body, response.etag);
    }

    // ---- writes (Git data API) ----

    /** POST /git/blobs; returns the blob sha. */
    String createBlob(byte[] data, int offset, int length) throws IOException {
        Response response = send(Request.post(repoPath + "/git/blobs", new Base64Body(data, offset, length)).content().passThrough(404));
        if (response.status == 404) {
            throw new LibraryUnavailableException("This sign-in can't write to " + auth.library());
        }
        return sha(response.json(), "sha");
    }

    /** POST /git/trees on top of {@code baseTree}; returns the new tree sha. */
    String createTree(String baseTree, JSONArray items) throws IOException {
        JSObject body = new JSObject();
        body.put("base_tree", baseTree);
        body.put("tree", items);
        Response response = send(Request.post(repoPath + "/git/trees", BytesBody.of(body)).content().passThrough(404, 422));
        if (response.status == 404 || response.status == 422) {
            throw new TreeRejectedException(response.status, response.message);
        }
        return sha(response.json(), "sha");
    }

    /** POST /git/commits with one parent; returns the commit sha. */
    String createCommit(String message, String tree, String parent) throws IOException {
        JSObject body = new JSObject();
        body.put("message", message);
        body.put("tree", tree);
        JSArray parents = new JSArray();
        parents.put(parent);
        body.put("parents", parents);
        return sha(send(Request.post(repoPath + "/git/commits", BytesBody.of(body)).content()).json(), "sha");
    }

    /**
     * Moves the branch to {@code commitSha} only as a fast-forward (never a
     * force-push). False when GitHub refuses because the branch moved meanwhile.
     */
    boolean updateHead(String commitSha) throws IOException {
        if (!patchUnsupported) {
            JSObject body = new JSObject();
            body.put("sha", commitSha);
            body.put("force", false);
            try {
                Request request = Request.patch(repoPath + "/git/refs/heads/" + encodePath(auth.branch), BytesBody.of(body));
                // 422 "not a fast forward" (sometimes 409): someone else committed since we read the head.
                return send(request.content().passThrough(409, 422)).status < 300;
            } catch (MethodUnsupportedException e) {
                Log.w(TAG, "PATCH is unavailable here; moving the branch through GraphQL instead", e);
                patchUnsupported = true;
            }
        }
        return updateHeadByGraphQl(commitSha);
    }

    /** GraphQL updateRef(force: false), the fallback for an HTTP stack that can't send PATCH. */
    private boolean updateHeadByGraphQl(String commitSha) throws IOException {
        if (refNodeId == null) {
            JSObject variables = new JSObject();
            variables.put("owner", auth.owner);
            variables.put("name", auth.repo);
            variables.put("ref", "refs/heads/" + auth.branch);
            JSONObject data = graphQl(
                "query($owner: String!, $name: String!, $ref: String!) { repository(owner: $owner, name: $name) { ref(qualifiedName: $ref) { id } } }",
                variables,
                false
            );
            JSONObject repository = data == null ? null : data.optJSONObject("repository");
            refNodeId = BackupUtil.optString(repository == null ? null : repository.optJSONObject("ref"), "id");
            if (refNodeId == null) {
                throw new LibraryUnavailableException("Branch \"" + auth.branch + "\" of " + auth.library() + " doesn't exist");
            }
        }
        JSObject variables = new JSObject();
        variables.put("id", refNodeId);
        variables.put("oid", commitSha);
        try {
            graphQl(
                "mutation($id: ID!, $oid: GitObjectID!) { updateRef(input: {refId: $id, oid: $oid, force: false}) { clientMutationId } }",
                variables,
                true
            );
            return true;
        } catch (GraphQlException e) {
            // A branch that moved meanwhile comes back as an error; the transaction re-reads and retries.
            Log.i(TAG, "GraphQL updateRef was refused: " + e.getMessage());
            return false;
        }
    }

    private JSONObject graphQl(String query, JSObject variables, boolean mutation) throws IOException {
        JSObject body = new JSObject();
        body.put("query", query);
        body.put("variables", variables);
        Request request = Request.post("/graphql", BytesBody.of(body));
        if (mutation) {
            request.content();
        }
        JSONObject response = send(request).json();
        JSONArray errors = response.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            String message = BackupUtil.optString(errors.optJSONObject(0), "message");
            throw new GraphQlException(message == null ? "GraphQL error" : message);
        }
        return response.optJSONObject("data");
    }

    /** A stop-aware sleep for callers' own backoff. */
    void pause(long millis) throws StoppedException {
        sleep(millis);
    }

    // ---- transport ----

    private interface StopCheck {
        void check() throws IOException;
    }

    private interface Body {
        long length();

        void writeTo(OutputStream out, StopCheck stop) throws IOException;
    }

    private static final class Request {

        final String method;
        final String path;
        final Body body;
        String accept = MEDIA_JSON;
        String etag;
        boolean content;
        int maxBytes = MAX_JSON_BYTES;
        File sink;
        private int[] passThrough = new int[0];

        private Request(String method, String path, Body body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        static Request get(String path) {
            return new Request("GET", path, null);
        }

        static Request post(String path, Body body) {
            return new Request("POST", path, body);
        }

        static Request patch(String path, Body body) {
            return new Request("PATCH", path, body);
        }

        /** A content-creating request: paced, since GitHub's secondary limits count these. */
        Request content() {
            content = true;
            return this;
        }

        /** Statuses the caller handles itself instead of getting an HttpStatusException. */
        Request passThrough(int... statuses) {
            passThrough = statuses;
            return this;
        }

        boolean passes(int status) {
            for (int allowed : passThrough) {
                if (allowed == status) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Response {

        final int status;
        final String etag;
        byte[] body;
        String message = "";
        String retryAfter;
        String rateLimitRemaining;
        String rateLimitReset;

        Response(int status, String etag) {
            this.status = status;
            this.etag = etag;
        }

        JSONObject json() throws HttpStatusException {
            try {
                return new JSONObject(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new HttpStatusException(502, "GitHub sent malformed JSON");
            }
        }
    }

    private static final class RateLimit {

        final long seconds;
        final boolean explicit;

        RateLimit(long seconds, boolean explicit) {
            this.seconds = seconds;
            this.explicit = explicit;
        }
    }

    private Response send(Request request) throws IOException {
        int attempt = 0;
        int rateLimitWaits = 0;
        while (true) {
            checkUsable();
            attempt++;
            if (request.content) {
                pace();
            }
            Response response;
            try {
                response = sendOnce(request);
            } catch (GitHubException | StoppedException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                Log.i(TAG, request.method + " " + request.path + " failed, retrying: " + e);
                sleep(backoffMillis(attempt));
                continue;
            }

            int status = response.status;
            if (status == 401) {
                throw new AuthRequiredException(status, response.message);
            }
            if (status == 403 || status == 429) {
                RateLimit limit = rateLimit(response);
                if (limit != null) {
                    if (limit.seconds <= MAX_INLINE_WAIT_SECONDS && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                        rateLimitWaits++;
                        attempt--;
                        setWaiting(true);
                        try {
                            sleep(limit.seconds * 1000L);
                        } finally {
                            setWaiting(false);
                        }
                        continue;
                    }
                    throw new RateLimitedException(limit.seconds, limit.explicit);
                }
                if (status == 403) {
                    throw new AuthRequiredException(status, response.message);
                }
            }
            if (status < 500) {
                onAuthenticated();
            }
            if ((status >= 200 && status < 300) || status == HttpURLConnection.HTTP_NOT_MODIFIED || request.passes(status)) {
                return response;
            }
            if ((status >= 500 || status == 408) && attempt < MAX_ATTEMPTS) {
                sleep(backoffMillis(attempt));
                continue;
            }
            if (status >= 300 && status < 400) {
                throw new LibraryUnavailableException("GitHub redirected " + request.path + "; was the repo renamed or moved?");
            }
            throw new HttpStatusException(status, response.message);
        }
    }

    private Response sendOnce(Request request) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API + request.path).openConnection();
        boolean ok = false;
        try {
            try {
                // Android's HttpURLConnection is backed by OkHttp, whose allowed methods
                // include PATCH (on every Android version this app supports); only a
                // replaced HTTP stack could refuse it.
                conn.setRequestMethod(request.method);
            } catch (ProtocolException e) {
                throw new MethodUnsupportedException(request.method, e);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // GitHub asks clients to follow redirects, but a redirected write would turn into a GET.
            conn.setInstanceFollowRedirects("GET".equals(request.method));
            conn.setUseCaches(false);
            conn.setRequestProperty("Authorization", "Bearer " + auth.token);
            conn.setRequestProperty("Accept", request.accept);
            conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (request.etag != null) {
                conn.setRequestProperty("If-None-Match", request.etag);
            }
            if (request.body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(request.body.length());
                try (OutputStream out = conn.getOutputStream()) {
                    request.body.writeTo(out, this::checkStopped);
                }
            }
            int status = conn.getResponseCode();
            Response response = new Response(status, conn.getHeaderField("ETag"));
            response.retryAfter = conn.getHeaderField("Retry-After");
            response.rateLimitRemaining = conn.getHeaderField("X-RateLimit-Remaining");
            response.rateLimitReset = conn.getHeaderField("X-RateLimit-Reset");
            if (status >= 400) {
                response.body = readAll(conn.getErrorStream(), MAX_ERROR_BYTES, true, -1);
                response.message = errorMessage(response.body);
            } else if (status == HttpURLConnection.HTTP_NOT_MODIFIED || status == HttpURLConnection.HTTP_NO_CONTENT) {
                try {
                    BackupUtil.closeQuietly(conn.getInputStream());
                } catch (IOException ignored) {
                    // No body to read.
                }
                response.body = new byte[0];
            } else if (request.sink != null && status == HttpURLConnection.HTTP_OK) {
                copyToFile(conn.getInputStream(), request.sink);
            } else {
                response.body = readAll(conn.getInputStream(), request.maxBytes, false, conn.getContentLength());
            }
            ok = true;
            return response;
        } finally {
            // On success the fully read stream is closed and the connection can be reused.
            if (!ok) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int limit, boolean truncate, int expected) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(expected > 0 && expected <= limit ? expected : 16 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (total + n > limit) {
                    if (truncate) {
                        out.write(buffer, 0, limit - total);
                        break;
                    }
                    throw new HttpStatusException(502, "GitHub's answer is larger than " + limit + " bytes");
                }
                out.write(buffer, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            BackupUtil.closeQuietly(in);
        }
    }

    private void copyToFile(InputStream in, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                checkStopped();
                out.write(buffer, 0, n);
            }
        } finally {
            BackupUtil.closeQuietly(in);
        }
    }

    /** {"encoding":"base64","content":"…"}, encoded while it streams so no multi-MB String is built. */
    private static final class Base64Body implements Body {

        private final byte[] data;
        private final int offset;
        private final int count;

        Base64Body(byte[] data, int offset, int count) {
            this.data = data;
            this.offset = offset;
            this.count = count;
        }

        @Override
        public long length() {
            // NO_WRAP base64 with padding: 4 characters per started group of 3 bytes.
            return BLOB_PREFIX.length + 4L * ((count + 2) / 3) + BLOB_SUFFIX.length;
        }

        @Override
        public void writeTo(OutputStream out, StopCheck stop) throws IOException {
            out.write(BLOB_PREFIX);
            // NO_CLOSE: closing the encoder writes its last group without closing the request stream.
            Base64OutputStream base64 = new Base64OutputStream(out, Base64.NO_WRAP | Base64.NO_CLOSE);
            for (int done = 0; done < count; done += BASE64_SLICE) {
                stop.check();
                base64.write(data, offset + done, Math.min(BASE64_SLICE, count - done));
            }
            base64.close();
            out.write(BLOB_SUFFIX);
        }
    }

    private static final class BytesBody implements Body {

        private final byte[] bytes;

        private BytesBody(byte[] bytes) {
            this.bytes = bytes;
        }

        static BytesBody of(JSONObject json) {
            return new BytesBody(json.toString().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public void writeTo(OutputStream out, StopCheck stop) throws IOException {
            stop.check();
            out.write(bytes);
        }
    }

    /** Port of lib/github/errors.ts githubRetryAfterSeconds for a 403/429; null when it isn't a rate limit. */
    private static RateLimit rateLimit(Response response) {
        long retryAfter = parseRetryAfter(response.retryAfter);
        if (retryAfter > 0) {
            return new RateLimit(retryAfter, true);
        }
        if (response.rateLimitRemaining != null && "0".equals(response.rateLimitRemaining.trim())) {
            long reset = parseLong(response.rateLimitReset);
            if (reset > 0) {
                return new RateLimit(clampSeconds(reset - System.currentTimeMillis() / 1000.0 + 1), true);
            }
            return new RateLimit(DEFAULT_RETRY_SECONDS, false);
        }
        if (response.status == 429 || RATE_LIMIT_MESSAGE.matcher(response.message).find()) {
            return new RateLimit(DEFAULT_RETRY_SECONDS, false);
        }
        return null;
    }

    /** A Retry-After value (delta seconds or an HTTP date) in seconds, clamped to [1, 3600]; -1 when absent or unreadable. */
    private static long parseRetryAfter(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        String trimmed = value.trim();
        if (RETRY_AFTER_SECONDS.matcher(trimmed).matches()) {
            return clampSeconds(Double.parseDouble(trimmed));
        }
        try {
            Date when = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(trimmed);
            if (when != null) {
                return clampSeconds((when.getTime() - System.currentTimeMillis()) / 1000.0);
            }
        } catch (ParseException ignored) {
            // Not a date either.
        }
        return -1;
    }

    private static long clampSeconds(double seconds) {
        return (long) Math.min(MAX_RETRY_SECONDS, Math.max(MIN_RETRY_SECONDS, Math.ceil(seconds)));
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** GitHub's "message" from an error body, else the start of the raw text. */
    private static String errorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            String message = BackupUtil.optString(new JSONObject(text), "message");
            if (message != null) {
                return message;
            }
        } catch (JSONException ignored) {
            // Not JSON (e.g. a proxy error page); fall through to the raw text.
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static String sha(JSONObject json, String key) throws HttpStatusException {
        String value = BackupUtil.optString(json, key);
        if (value == null || !GIT_SHA.matcher(value).matches()) {
            throw new HttpStatusException(502, "GitHub's answer had no valid " + key);
        }
        return value;
    }

    private static String encode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    /** A repo path or branch name with each "/"-separated part URL-encoded. */
    private static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encode(parts[i]));
        }
        return out.toString();
    }

    /** The first successful answer proves the token works: lift a stale sign-in prompt. */
    private void onAuthenticated() {
        if (authConfirmed) {
            return;
        }
        authConfirmed = true;
        if ((prefs.needsSignIn() || prefs.signInNotified()) && prefs.isCurrentToken(auth.token)) {
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

    /** Spaces content-creating requests (blobs, trees, commits, ref updates) at least 800 ms apart. */
    private void pace() throws StoppedException {
        long wait;
        synchronized (PACE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            long at = Math.max(now, lastContentRequestAt + MIN_CONTENT_INTERVAL_MS);
            lastContentRequestAt = at;
            wait = at - now;
        }
        if (wait > 0) {
            sleep(wait);
        }
    }

    private long backoffMillis(int attempt) {
        long base = Math.min(30_000L, 1000L << Math.min(attempt, 5));
        return base + random.nextInt(500);
    }

    /** Stopped, or the stored session is no longer the one this client was made for. */
    private void checkUsable() throws IOException {
        checkStopped();
        if (!prefs.isCurrent(auth)) {
            throw new AuthChangedException();
        }
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
