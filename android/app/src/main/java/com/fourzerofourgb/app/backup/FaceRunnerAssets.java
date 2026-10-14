package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.WebResourceResponse;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the background People runner's WebView may load (see {@link FaceScanWorker}):
 *
 *  - Its own origin, https://appassets.androidplatform.net, through
 *    WebViewAssetLoader: the bundled web export (assets/public/, what
 *    `cap sync` copies; a path without an extension is tried as
 *    "&lt;path&gt;.html", then "&lt;path&gt;/index.html"), and
 *    /_local/thumb/&lt;mediaId&gt;: an 800 px WebP of that library photo made
 *    from this phone's own copy, so the runner needn't download it from
 *    GitHub (404 when the phone has no copy, or the file changed since).
 *  - GitHub's API, which the page calls directly with the stored token.
 *
 * Anything else is refused: the page holds the token and the host bridge, so
 * it never loads code or pages from elsewhere.
 */
final class FaceRunnerAssets {

    static final String DOMAIN = WebViewAssetLoader.DEFAULT_DOMAIN;
    static final String ORIGIN = "https://" + DOMAIN;
    static final String LOCAL_THUMB_PATH = "/_local/thumb/";
    static final int LOCAL_THUMB_SIZE = 800;

    private static final String TAG = "404gb.FaceRunnerAssets";
    private static final String ASSET_ROOT = "public/";
    private static final String WEBP_SUFFIX = ".webp";
    // Column name on every supported API level (MediaColumns.ORIENTATION is API 29+).
    private static final String COL_ORIENTATION = "orientation";
    private static final Map<String, String> NO_STORE = Collections.singletonMap("Cache-Control", "no-store");

    private final Context context;
    private final WebViewAssetLoader loader;
    private final AtomicInteger localServed = new AtomicInteger();
    private final AtomicInteger localMissed = new AtomicInteger();
    private final AtomicInteger bundledServed = new AtomicInteger();
    private final AtomicInteger bundledMissing = new AtomicInteger();
    private volatile String firstMissing;

    FaceRunnerAssets(Context context) {
        this.context = context.getApplicationContext();
        AssetManager assets = this.context.getAssets();
        // Tried in order: the thumbnail handler answers every path under its prefix itself.
        loader = new WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler(LOCAL_THUMB_PATH, this::localThumbnail)
            .addPathHandler("/", path -> counted(path, bundled(assets, path)))
            .build();
    }

    /** Whether the bundled export has {@code page} (an older web build has no runner page). */
    static boolean hasPage(Context context, String page) {
        try (InputStream ignored = context.getAssets().open(ASSET_ROOT + page)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether /_local/thumb/ can find anything: media access and an image known to be a library item. */
    static boolean canServeLocal(Context context) {
        if (!MediaAccess.hasFullAccess(context) && !MediaAccess.hasPartialAccessOnly(context)) {
            return false;
        }
        try {
            return BackupLedger.get(context).hasLocalImages();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read the ledger", e);
            return false;
        }
    }

    /** A page of the runner's origin: the only place its WebView may navigate to. */
    static boolean isRunnerPage(Uri url) {
        return "https".equalsIgnoreCase(url.getScheme()) && DOMAIN.equalsIgnoreCase(url.getHost());
    }

    /** GitHub's REST API, plus the hosts it may hand raw file bytes out from. */
    static boolean isGitHubApi(String host) {
        return host.equals("api.github.com") || host.endsWith(".githubusercontent.com");
    }

    /**
     * The answer to a request of the page (any thread): the runner origin's
     * files, null (the network) for GitHub's API, a refusal for anything else.
     * Other schemes (data:, blob:) never leave the page and are left alone.
     */
    WebResourceResponse intercept(Uri url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return null;
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        if (host.equals(DOMAIN)) {
            WebResourceResponse response = scheme.equals("https") ? loader.shouldInterceptRequest(url) : null;
            return response != null ? response : notFound();
        }
        if (scheme.equals("https") && isGitHubApi(host)) {
            return null;
        }
        Log.w(TAG, "Refused a request to " + host);
        return status(403, "Forbidden");
    }

    int localServed() {
        return localServed.get();
    }

    int localMissed() {
        return localMissed.get();
    }

    int bundledServed() {
        return bundledServed.get();
    }

    int bundledMissing() {
        return bundledMissing.get();
    }

    /** The first bundled path that wasn't in the export, for the run's log. */
    String firstMissing() {
        return firstMissing;
    }

    private WebResourceResponse counted(String path, WebResourceResponse response) {
        // A file response made without a status reports 0, not 200; misses are notFound()'s 404.
        if (response.getStatusCode() != 404) {
            bundledServed.incrementAndGet();
        } else if (bundledMissing.getAndIncrement() == 0) {
            firstMissing = path;
        }
        return response;
    }

    private static WebResourceResponse bundled(AssetManager assets, String path) {
        for (String candidate : candidates(path)) {
            InputStream in;
            try {
                in = assets.open(ASSET_ROOT + candidate, AssetManager.ACCESS_STREAMING);
            } catch (IOException e) {
                continue;
            }
            String mime = mimeType(candidate);
            return new WebResourceResponse(mime, isText(mime) ? "utf-8" : null, in);
        }
        return notFound();
    }

    private WebResourceResponse localThumbnail(String path) {
        String mediaId = path.endsWith(WEBP_SUFFIX) ? path.substring(0, path.length() - WEBP_SUFFIX.length()) : path;
        byte[] webp = BackupUtil.isMediaId(mediaId) ? localWebp(mediaId) : null;
        if (webp == null) {
            localMissed.incrementAndGet();
            return notFound();
        }
        localServed.incrementAndGet();
        return new WebResourceResponse("image/webp", null, 200, "OK", NO_STORE, new ByteArrayInputStream(webp));
    }

    private byte[] localWebp(String mediaId) {
        List<BackupLedger.LocalCopy> copies;
        try {
            copies = BackupLedger.get(context).localImages(mediaId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read the ledger", e);
            return null;
        }
        for (BackupLedger.LocalCopy copy : copies) {
            Uri uri = Uri.parse(copy.uri);
            if (isUnchanged(uri, copy)) {
                byte[] webp = Thumbnails.single(context, uri, false, copy.orientation, LOCAL_THUMB_SIZE);
                if (webp != null) {
                    return webp;
                }
            }
        }
        return null;
    }

    /**
     * Whether the phone's file still has the bytes that went into the library:
     * the same size and, for a backed-up file, the same modification time and
     * rotation (an edit or a rotation since would move the faces).
     */
    private boolean isUnchanged(Uri uri, BackupLedger.LocalCopy copy) {
        String[] columns = { MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, COL_ORIENTATION };
        try (Cursor c = context.getContentResolver().query(uri, columns, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0) || c.getLong(0) != copy.size) {
                return false;
            }
            if (copy.downloaded) {
                return true;
            }
            long modified = c.isNull(1) ? -1L : c.getLong(1);
            int orientation = c.isNull(2) ? 0 : c.getInt(2);
            return modified == copy.dateModified && orientation == copy.orientation;
        } catch (RuntimeException e) {
            // Media access was taken away, or a MediaStore build refuses the query.
            return false;
        }
    }

    /**
     * Files under assets/public/ to try for a request path ("runner/faces",
     * "_next/static/…"); empty when the path isn't a plain relative one.
     */
    static List<String> candidates(String path) {
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return Collections.singletonList("index.html");
        }
        boolean directory = p.endsWith("/");
        String trimmed = directory ? p.substring(0, p.length() - 1) : p;
        String[] segments = trimmed.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
                return Collections.emptyList();
            }
        }
        if (directory) {
            return Collections.singletonList(trimmed + "/index.html");
        }
        if (segments[segments.length - 1].indexOf('.') >= 0) {
            return Collections.singletonList(trimmed);
        }
        return Arrays.asList(trimmed + ".html", trimmed + "/index.html");
    }

    /** Content type by extension; WebAssembly must be application/wasm for streaming compilation. */
    static String mimeType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot > path.lastIndexOf('/') ? path.substring(dot + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "html":
            case "htm":
                return "text/html";
            case "js":
            case "mjs":
                return "text/javascript";
            case "css":
                return "text/css";
            case "json":
            case "map":
                return "application/json";
            case "webmanifest":
                return "application/manifest+json";
            case "txt":
                return "text/plain";
            case "xml":
                return "application/xml";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "avif":
                return "image/avif";
            case "ico":
                return "image/x-icon";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "wasm":
                return "application/wasm";
            default:
                return "application/octet-stream";
        }
    }

    static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("xml");
    }

    private static WebResourceResponse notFound() {
        return status(404, "Not Found");
    }

    /** For a request that couldn't be answered because of a bug here. */
    static WebResourceResponse failed() {
        return status(500, "Internal Server Error");
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, NO_STORE, new ByteArrayInputStream(new byte[0]));
    }
}
