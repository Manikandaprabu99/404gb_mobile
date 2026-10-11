package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import androidx.annotation.RequiresApi;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Downloads an original from the library repo on GitHub with the stored token:
 * metadata/&lt;id&gt;.json, then its chunks (objects/…, each at most 4 MiB),
 * verifying every chunk hash and the whole-file hash before the file becomes
 * visible.
 */
final class MediaDownloader {

    interface Progress {
        void onProgress(long bytesDone, long bytesTotal);
    }

    static final class Shared {

        final File file;
        final String mimeType;
        final String fileName;

        Shared(File file, String mimeType, String fileName) {
            this.file = file;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }
    }

    private static final class Record {

        String id;
        String filename;
        String mimeType;
        String hashHex;
        long size;
        long takenAt;
        final List<String> chunkHashes = new ArrayList<>();
    }

    private static final String TAG = "404gb.MediaDownloader";
    private static final Pattern MEDIA_ID = Pattern.compile("^media_[A-Za-z0-9_-]{1,80}$");
    private static final String FOLDER = "404GB";
    private static final long SHARE_TTL_MS = 60 * 60 * 1000L;
    private static final int MAX_CHUNK_BYTES = BackupUtil.CHUNK_SIZE + 1024;
    private static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;

    private final Context context;
    private final GitHubClient github;
    /** The branch to read (the repo's default branch when empty). */
    private final String branch;
    private final Progress progress;

    /** Throws AuthRequiredException when no GitHub session (with a repo) is stored. */
    MediaDownloader(Context context, Progress progress) throws GitHubClient.AuthRequiredException {
        this.context = context.getApplicationContext();
        BackupPrefs.Auth auth = BackupPrefs.get(this.context).auth();
        if (auth == null || !auth.hasLibrary()) {
            throw new GitHubClient.AuthRequiredException(401, "not signed in");
        }
        this.github = new GitHubClient(this.context, auth, null);
        this.branch = auth.branch;
        this.progress = progress;
    }

    static boolean isValidMediaId(String mediaId) {
        return mediaId != null && MEDIA_ID.matcher(mediaId).matches();
    }

    /** Saves into Pictures/404GB or Movies/404GB and returns the new item's URI. */
    Uri saveToGallery(String mediaId) throws IOException {
        Record record = fetchRecord(mediaId);
        String mime = mediaMime(record);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveScoped(record, mime);
        }
        return saveLegacy(record, mime);
    }

    /** Downloads into the app cache for the share sheet, cleaning up old shares first. */
    Shared downloadForShare(String mediaId) throws IOException {
        Record record = fetchRecord(mediaId);
        File root = new File(context.getCacheDir(), "shared");
        cleanOldShares(root);
        File dir = new File(root, record.id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        File target = new File(dir, record.filename);
        File temp = new File(dir, record.filename + ".part");
        boolean ok = false;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                download(record, out);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Could not replace " + target);
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Could not finish " + target);
            }
            ok = true;
        } finally {
            if (!ok && temp.exists() && !temp.delete()) {
                Log.w(TAG, "Could not delete " + temp);
            }
        }
        return new Shared(target, mediaMime(record), record.filename);
    }

    private Record fetchRecord(String mediaId) throws IOException {
        if (!isValidMediaId(mediaId)) {
            throw new IOException("Invalid media id");
        }
        GitHubClient.RawFile raw = github.readRaw(LibraryWriter.metadataPath(mediaId), branch, null, MAX_RECORD_BYTES);
        if (raw.kind != GitHubClient.RawFile.OK || raw.bytes == null) {
            throw new IOException("This item is no longer in the library");
        }
        JSONObject media;
        try {
            media = new JSONObject(new String(raw.bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("The media record is unreadable", e);
        }
        Record record = new Record();
        String id = BackupUtil.optString(media, "id");
        record.id = isValidMediaId(id) ? id : mediaId;
        record.filename = sanitizeFileName(BackupUtil.optString(media, "filename"), record.id);
        String mime = BackupUtil.optString(media, "mimeType");
        record.mimeType = mime == null ? "application/octet-stream" : mime.toLowerCase(Locale.US);
        record.size = media.optLong("size", 0L);
        String hash = BackupUtil.optString(media, "hash");
        if (hash == null || !hash.toLowerCase(Locale.US).startsWith("sha256:")) {
            throw new IOException("The media record has no sha256 hash");
        }
        record.hashHex = hash.substring("sha256:".length()).toLowerCase(Locale.US);
        if (!BackupUtil.isSha256Hex(record.hashHex)) {
            throw new IOException("The media record has a malformed hash");
        }
        String taken = BackupUtil.optString(media, "takenAt");
        record.takenAt = parseIso(taken != null ? taken : BackupUtil.optString(media, "createdAt"));

        JSONArray chunks = media.optJSONArray("chunks");
        if (chunks == null) {
            throw new IOException("The media record lists no chunks");
        }
        List<long[]> order = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject chunk = chunks.optJSONObject(i);
            String chunkHash = chunk == null ? null : BackupUtil.optString(chunk, "hash");
            if (chunkHash == null || !BackupUtil.isSha256Hex(chunkHash.toLowerCase(Locale.US))) {
                throw new IOException("The media record has a malformed chunk");
            }
            order.add(new long[] { chunk.optLong("index", i), hashes.size() });
            hashes.add(chunkHash.toLowerCase(Locale.US));
        }
        Collections.sort(order, (a, b) -> Long.compare(a[0], b[0]));
        for (long[] entry : order) {
            record.chunkHashes.add(hashes.get((int) entry[1]));
        }
        return record;
    }

    private void download(Record record, OutputStream out) throws IOException {
        MessageDigest whole = BackupUtil.sha256();
        long done = 0;
        report(0, record.size);
        for (String chunkHash : record.chunkHashes) {
            GitHubClient.RawFile raw = github.readRaw(BackupUtil.objectPath(chunkHash), branch, null, MAX_CHUNK_BYTES);
            if (raw.kind != GitHubClient.RawFile.OK || raw.bytes == null) {
                throw new IOException("A part of this file is missing from the library");
            }
            byte[] bytes = raw.bytes;
            if (!BackupUtil.sha256Hex(bytes, 0, bytes.length).equals(chunkHash)) {
                throw new IOException("A downloaded part failed its integrity check");
            }
            out.write(bytes);
            whole.update(bytes);
            done += bytes.length;
            report(done, Math.max(done, record.size));
        }
        if (!BackupUtil.hex(whole.digest()).equals(record.hashHex)) {
            throw new IOException("The downloaded file does not match its recorded hash");
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private Uri saveScoped(Record record, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        boolean video = mime.startsWith("video/");
        Uri collection = video
            ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, record.filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (record.takenAt > 0) {
            values.put(MediaStore.MediaColumns.DATE_TAKEN, record.takenAt);
        }
        Uri item;
        try {
            item = resolver.insert(collection, values);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IOException("Could not create a gallery entry: " + e.getMessage(), e);
        }
        if (item == null) {
            throw new IOException("Could not create a gallery entry");
        }
        String ledgerCollection = video ? BackupLedger.COLLECTION_VIDEO : BackupLedger.COLLECTION_IMAGES;
        long id = ContentUris.parseId(item);
        BackupLedger ledger = BackupLedger.get(context);
        boolean ok = false;
        try {
            try (OutputStream out = resolver.openOutputStream(item, "w")) {
                if (out == null) {
                    throw new IOException("Could not open the gallery entry");
                }
                download(record, out);
            }
            // Recorded before the entry becomes visible, so a backup scan can never queue it.
            ledger.insertDownloaded(ledgerCollection, id, item.toString(), record.filename, mime, record.size, record.hashHex, record.id);
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, publish, null, null);
            ok = true;
            return item;
        } finally {
            if (!ok) {
                ledger.deleteRow(ledgerCollection, id);
                try {
                    resolver.delete(item, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not remove the incomplete gallery entry", e);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Uri saveLegacy(Record record, String mime) throws IOException {
        boolean video = mime.startsWith("video/");
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
            FOLDER
        );
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        File target = uniqueFile(dir, record.filename);
        // Dot-prefixed so the media scanner ignores it until it is verified and renamed.
        File temp = new File(dir, "." + target.getName() + ".part");
        boolean ok = false;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                download(record, out);
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Could not finish " + target);
            }
            ok = true;
        } finally {
            if (!ok && temp.exists() && !temp.delete()) {
                Log.w(TAG, "Could not delete " + temp);
            }
        }

        Uri uri = scanFile(target, mime);
        if (uri == null) {
            return Uri.fromFile(target);
        }
        try {
            String ledgerCollection = video ? BackupLedger.COLLECTION_VIDEO : BackupLedger.COLLECTION_IMAGES;
            BackupLedger.get(context)
                .insertDownloaded(ledgerCollection, ContentUris.parseId(uri), uri.toString(), target.getName(), mime, record.size, record.hashHex, record.id);
        } catch (RuntimeException e) {
            // The scanner skips files modified in the last few seconds, and the server
            // dedups by hash anyway, so a missing ledger row costs at most one check.
            Log.w(TAG, "Could not record the saved file in the ledger", e);
        }
        return uri;
    }

    private Uri scanFile(File file, String mime) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Uri> result = new AtomicReference<>();
        MediaScannerConnection.scanFile(context, new String[] { file.getAbsolutePath() }, new String[] { mime }, (path, uri) -> {
            result.set(uri);
            latch.countDown();
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private void report(long done, long total) {
        if (progress != null) {
            progress.onProgress(done, total);
        }
    }

    /** image/* or video/*; falls back to the file extension, then to a generic type. */
    private static String mediaMime(Record record) {
        String mime = record.mimeType;
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            return mime;
        }
        int dot = record.filename.lastIndexOf('.');
        if (dot >= 0 && dot < record.filename.length() - 1) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(record.filename.substring(dot + 1).toLowerCase(Locale.US));
            if (guess != null && (guess.startsWith("image/") || guess.startsWith("video/"))) {
                return guess;
            }
        }
        return "image/jpeg";
    }

    private static String sanitizeFileName(String name, String fallbackId) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 10 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 120 - ext.length()) + ext;
        }
        return cleaned.isEmpty() ? "404gb-" + fallbackId : cleaned;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static void cleanOldShares(File root) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SHARE_TTL_MS;
        for (File entry : entries) {
            if (entry.lastModified() < cutoff) {
                deleteRecursively(entry);
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }

    private static long parseIso(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String[] patterns = { "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'" };
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                java.util.Date date = format.parse(value);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
                // Try the next pattern.
            }
        }
        return 0L;
    }
}
