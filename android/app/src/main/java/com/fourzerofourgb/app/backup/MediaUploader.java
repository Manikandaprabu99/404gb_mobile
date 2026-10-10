package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import com.getcapacitor.JSObject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uploads one ledger item: hash pass, init (dedup + missing chunks), chunk
 * uploads with every blob sha persisted as it arrives, native thumbnails,
 * then the commit. Files are streamed with a single 4 MiB buffer and never
 * loaded whole.
 */
final class MediaUploader {

    enum Outcome {
        UPLOADED,
        DUPLICATE,
        FAILED,
    }

    interface Progress {
        void onProgress(String name, long bytesDone, long bytesTotal);
    }

    /** A local problem with the file itself (not the network). */
    static final class MediaReadException extends Exception {

        /** The bytes differ from the hash pass, so any saved progress is void. */
        final boolean changed;

        MediaReadException(String message, boolean changed, Throwable cause) {
            super(message, cause);
            this.changed = changed;
        }
    }

    private static final class HashResult {

        final List<String> chunkHashes;
        final String fileHash;
        final long size;

        HashResult(List<String> chunkHashes, String fileHash, long size) {
            this.chunkHashes = chunkHashes;
            this.fileHash = fileHash;
            this.size = size;
        }

        int chunkLength(int index) {
            return (int) Math.min(BackupUtil.CHUNK_SIZE, size - (long) index * BackupUtil.CHUNK_SIZE);
        }
    }

    private static final String TAG = "404gb.MediaUploader";
    private static final Pattern MEDIA_MIME = Pattern.compile("^(?:image|video)/[a-z0-9.+-]+$");
    private static final int MAX_COMMIT_ROUNDS = 3;

    private final Context context;
    private final ContentResolver resolver;
    private final BackupLedger ledger;
    private final BackupPrefs prefs;
    private final ApiClient api;
    private final ApiClient.StopSignal stop;
    private final byte[] buffer = new byte[BackupUtil.CHUNK_SIZE];

    MediaUploader(Context context, BackupLedger ledger, ApiClient api, ApiClient.StopSignal stop) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.ledger = ledger;
        this.prefs = BackupPrefs.get(this.context);
        this.api = api;
        this.stop = stop;
    }

    /**
     * Item-level problems are recorded in the ledger and reported as FAILED.
     * Sign-in, rate-limit, stop, network and server (5xx) problems are thrown
     * so the worker can stop and resume later; the item keeps its saved
     * progress and goes back to pending.
     */
    Outcome upload(BackupLedger.Item item, Progress progress) throws IOException {
        ledger.markUploading(item);
        Uri uri = Uri.parse(item.uri);
        String name = displayName(item);
        String mime = mimeType(item, uri, name);
        progress.onProgress(name, 0, item.size);
        try {
            Uri source = chooseSource(uri);
            HashResult hashed = hashFile(source);
            progress.onProgress(name, 0, hashed.size);

            String alreadyBackedUp = ledger.findBackedUpMediaId(hashed.fileHash, item);
            if (alreadyBackedUp != null) {
                ledger.markDuplicate(item, alreadyBackedUp, hashed.fileHash);
                return Outcome.DUPLICATE;
            }

            // Saved blob shas and the id are only valid for the exact same bytes.
            boolean sameContent = hashed.fileHash.equals(item.fileHash);
            Map<String, String> shas = sameContent ? BackupUtil.parseStringMap(item.chunkShasJson) : new HashMap<>();
            shas.keySet().retainAll(new HashSet<>(hashed.chunkHashes));
            String mediaId = sameContent && item.mediaId != null ? item.mediaId : BackupUtil.newMediaId();
            ledger.saveIdentity(item, hashed.fileHash, mediaId, shas);

            JSObject initBody = new JSObject();
            initBody.put("chunkHashes", new JSONArray(hashed.chunkHashes));
            initBody.put("fileHash", hashed.fileHash);
            JSONObject init = api.postJson("/api/media/upload/init", initBody);
            String duplicateOf = ApiClient.optString(init, "duplicateOf");
            if (duplicateOf != null) {
                return finishExisting(item, duplicateOf, mediaId, hashed.fileHash);
            }
            Set<String> toUpload = knownHashes(init.optJSONArray("missing"), hashed);
            toUpload.removeAll(shas.keySet());
            uploadChunks(item, source, hashed, toUpload, shas, name, progress);

            Map<String, String> thumbnailShas = uploadThumbnails(item, uri);

            for (int round = 1; ; round++) {
                JSObject body = new JSObject();
                body.put("record", buildRecord(item, uri, name, mime, hashed, mediaId, thumbnailShas));
                body.put("chunkBlobShas", toJsObject(shas));
                if (!thumbnailShas.isEmpty()) {
                    body.put("thumbnailBlobShas", toJsObject(thumbnailShas));
                }
                JSONObject result;
                try {
                    result = api.postJson("/api/media/upload/commit", body);
                } catch (ApiClient.HttpStatusException e) {
                    if (round >= MAX_COMMIT_ROUNDS) {
                        throw e;
                    }
                    Set<String> again = chunksToResend(e, hashed, shas);
                    if (again.isEmpty()) {
                        throw e;
                    }
                    shas.keySet().removeAll(again);
                    ledger.saveChunkShas(item, shas);
                    uploadChunks(item, source, hashed, again, shas, name, progress);
                    continue;
                }
                // The server may assign a different id (e.g. on an id clash); its answer is authoritative.
                String id = ApiClient.optString(result, "id");
                if (id == null) {
                    id = mediaId;
                }
                if (result.optBoolean("duplicate", false) && !id.equals(mediaId)) {
                    ledger.markDuplicate(item, id, hashed.fileHash);
                    return Outcome.DUPLICATE;
                }
                ledger.markDone(item, id, hashed.fileHash);
                prefs.setLastBackupAt(System.currentTimeMillis());
                return Outcome.UPLOADED;
            }
        } catch (MediaReadException e) {
            // A vanished file also lands here; the next scan drops its row once MediaStore forgets it.
            ledger.recordFailure(item, e.getMessage(), e.changed);
            return Outcome.FAILED;
        } catch (ApiClient.HttpStatusException e) {
            ledger.recordFailure(item, e.getMessage(), "unknown_blob".equals(e.code));
            if (e.isServerError()) {
                throw e;
            }
            return Outcome.FAILED;
        } catch (SecurityException e) {
            ledger.recordFailure(item, "No permission to read this file", false);
            return Outcome.FAILED;
        }
    }

    private Outcome finishExisting(BackupLedger.Item item, String existingId, String mediaId, String fileHash) {
        // Our own earlier commit whose response was lost still counts as uploaded by this phone.
        if (existingId.equals(mediaId)) {
            ledger.markDone(item, existingId, fileHash);
            prefs.setLastBackupAt(System.currentTimeMillis());
            return Outcome.UPLOADED;
        }
        ledger.markDuplicate(item, existingId, fileHash);
        return Outcome.DUPLICATE;
    }

    /** Chunks a failed commit says must be uploaded again; empty when the error isn't recoverable that way. */
    private static Set<String> chunksToResend(ApiClient.HttpStatusException e, HashResult hashed, Map<String, String> shas) {
        if (e.status == 409 && "missing_chunks".equals(e.code)) {
            return knownHashes(e.body == null ? null : e.body.optJSONArray("missing"), hashed);
        }
        if (e.status == 422 && "unknown_blob".equals(e.code)) {
            // GitHub no longer knows the blob shas we sent: upload all of them again.
            return new HashSet<>(shas.keySet());
        }
        return new HashSet<>();
    }

    /** The subset of {@code array} that are chunk hashes of this file. */
    private static Set<String> knownHashes(JSONArray array, HashResult hashed) {
        Set<String> ours = new HashSet<>(hashed.chunkHashes);
        Set<String> out = new HashSet<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            String hash = array.optString(i, "").toLowerCase(Locale.US);
            if (ours.contains(hash)) {
                out.add(hash);
            }
        }
        return out;
    }

    private Uri chooseSource(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && MediaAccess.canReadOriginals(context)) {
            try {
                Uri original = MediaStore.setRequireOriginal(uri);
                InputStream probe = resolver.openInputStream(original);
                if (probe != null) {
                    BackupUtil.closeQuietly(probe);
                    return original;
                }
            } catch (SecurityException | UnsupportedOperationException | IOException e) {
                Log.w(TAG, "Original unavailable for " + uri + "; uploading the redacted copy", e);
            }
        }
        return uri;
    }

    /** Pass 1: per-chunk SHA-256 and whole-file SHA-256 in one sequential read. */
    private HashResult hashFile(Uri source) throws IOException, MediaReadException {
        MessageDigest whole = BackupUtil.sha256();
        List<String> chunkHashes = new ArrayList<>();
        long size = 0;
        InputStream in = open(source);
        try {
            while (true) {
                checkStopped();
                int n = readUpTo(in, buffer, BackupUtil.CHUNK_SIZE);
                if (n <= 0) {
                    break;
                }
                whole.update(buffer, 0, n);
                chunkHashes.add(BackupUtil.sha256Hex(buffer, 0, n));
                size += n;
                if (n < BackupUtil.CHUNK_SIZE) {
                    break;
                }
            }
        } finally {
            BackupUtil.closeQuietly(in);
        }
        if (size == 0) {
            throw new MediaReadException("The file is empty", false, null);
        }
        return new HashResult(chunkHashes, BackupUtil.hex(whole.digest()), size);
    }

    /** Pass 2: re-reads the file and uploads the chunks in {@code needed}, one at a time. */
    private void uploadChunks(
        BackupLedger.Item item,
        Uri source,
        HashResult hashed,
        Set<String> needed,
        Map<String, String> shas,
        String name,
        Progress progress
    ) throws IOException, MediaReadException {
        Map<String, Integer> occurrences = new HashMap<>();
        for (String hash : hashed.chunkHashes) {
            Integer count = occurrences.get(hash);
            occurrences.put(hash, count == null ? 1 : count + 1);
        }
        Set<String> remaining = new HashSet<>(needed);
        long done = hashed.size;
        for (int i = 0; i < hashed.chunkHashes.size(); i++) {
            if (remaining.contains(hashed.chunkHashes.get(i))) {
                done -= hashed.chunkLength(i);
            }
        }
        progress.onProgress(name, done, hashed.size);
        if (remaining.isEmpty()) {
            return;
        }

        InputStream in = open(source);
        try {
            for (int i = 0; i < hashed.chunkHashes.size() && !remaining.isEmpty(); i++) {
                checkStopped();
                String expected = hashed.chunkHashes.get(i);
                int length = hashed.chunkLength(i);
                if (!remaining.contains(expected)) {
                    skipFully(in, length);
                    continue;
                }
                if (readUpTo(in, buffer, length) != length) {
                    throw new MediaReadException("The file got shorter while uploading", true, null);
                }
                if (!BackupUtil.sha256Hex(buffer, 0, length).equals(expected)) {
                    throw new MediaReadException("The file changed while uploading", true, null);
                }
                String sha = api.uploadChunk(buffer, length, expected);
                shas.put(expected, sha);
                ledger.saveChunkShas(item, shas);
                remaining.remove(expected);
                Integer count = occurrences.get(expected);
                done += (long) length * (count == null ? 1 : count);
                progress.onProgress(name, Math.min(done, hashed.size), hashed.size);
            }
        } finally {
            BackupUtil.closeQuietly(in);
        }
    }

    /** Best effort: returns blob shas of the thumbnails that made it, keyed by size. */
    private Map<String, String> uploadThumbnails(BackupLedger.Item item, Uri uri) throws IOException {
        Map<String, String> shas = new LinkedHashMap<>();
        List<Thumbnails.Thumb> thumbs = Thumbnails.generate(context, uri, item.isVideo(), item.orientation);
        for (Thumbnails.Thumb thumb : thumbs) {
            try {
                String hash = BackupUtil.sha256Hex(thumb.webp, 0, thumb.webp.length);
                shas.put(thumb.size, api.uploadChunk(thumb.webp, thumb.webp.length, hash));
            } catch (ApiClient.HttpStatusException e) {
                Log.w(TAG, "Thumbnail upload failed; committing without the rest", e);
                break;
            }
        }
        return shas;
    }

    private JSObject buildRecord(
        BackupLedger.Item item,
        Uri uri,
        String name,
        String mime,
        HashResult hashed,
        String mediaId,
        Map<String, String> thumbnailShas
    ) {
        JSObject record = new JSObject();
        record.put("id", mediaId);
        record.put("filename", name);
        record.put("mimeType", mime);
        record.put("size", hashed.size);
        record.put("createdAt", BackupUtil.isoUtc(System.currentTimeMillis()));
        long taken = item.dateTaken > 0 ? item.dateTaken : item.dateModified * 1000L;
        if (taken > 0) {
            record.put("takenAt", BackupUtil.isoUtc(taken));
        }

        int width = item.width;
        int height = item.height;
        int rotation = item.orientation;
        long durationMs = item.durationMs;
        if (item.isVideo()) {
            // Video rotation only appears in MediaStore from Android 11.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || width <= 0 || height <= 0 || durationMs <= 0) {
                long[] probe = Thumbnails.probeVideo(context, uri);
                if (width <= 0 || height <= 0) {
                    width = (int) probe[0];
                    height = (int) probe[1];
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    rotation = (int) probe[2];
                }
                if (durationMs <= 0) {
                    durationMs = probe[3];
                }
            }
        } else if (width <= 0 || height <= 0) {
            int[] size = Thumbnails.probeImage(resolver, uri);
            width = size[0];
            height = size[1];
        }
        if (width > 0 && height > 0) {
            int normalized = ((rotation % 360) + 360) % 360;
            boolean swap = normalized == 90 || normalized == 270;
            record.put("width", swap ? height : width);
            record.put("height", swap ? width : height);
        }
        if (item.isVideo() && durationMs > 0) {
            record.put("duration", durationMs / 1000.0);
        }

        record.put("hash", "sha256:" + hashed.fileHash);
        record.put("chunkSize", BackupUtil.CHUNK_SIZE);
        JSONArray chunks = new JSONArray();
        for (int i = 0; i < hashed.chunkHashes.size(); i++) {
            String hash = hashed.chunkHashes.get(i);
            JSObject chunk = new JSObject();
            chunk.put("index", i);
            chunk.put("hash", hash);
            chunk.put("path", BackupUtil.objectPath(hash));
            chunks.put(chunk);
        }
        record.put("chunks", chunks);
        JSObject thumbnails = new JSObject();
        for (String size : thumbnailShas.keySet()) {
            thumbnails.put(size, "thumbnails/" + mediaId + "/" + size + ".webp");
        }
        record.put("thumbnails", thumbnails);
        record.put("deletedAt", JSONObject.NULL);
        record.put("source", "android-backup");
        return record;
    }

    private static JSObject toJsObject(Map<String, String> map) {
        JSObject obj = new JSObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            obj.put(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    private static String displayName(BackupLedger.Item item) {
        if (item.displayName != null && !item.displayName.trim().isEmpty()) {
            return item.displayName.trim();
        }
        String ext = item.mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime);
        return (item.isVideo() ? "VID_" : "IMG_") + item.mediaStoreId + (ext == null ? "" : "." + ext);
    }

    /** The server only accepts image/* and video/* types. */
    private String mimeType(BackupLedger.Item item, Uri uri, String name) {
        String mime = normalizeMime(item.mime);
        if (mime == null) {
            try {
                mime = normalizeMime(resolver.getType(uri));
            } catch (RuntimeException ignored) {
                mime = null;
            }
        }
        if (mime == null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                mime = normalizeMime(MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
            }
        }
        if (mime == null) {
            mime = item.isVideo() ? "video/mp4" : "image/jpeg";
        }
        return mime;
    }

    private static String normalizeMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.US);
        return MEDIA_MIME.matcher(lower).matches() ? lower : null;
    }

    private InputStream open(Uri uri) throws MediaReadException {
        try {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) {
                throw new MediaReadException("Could not open the file", false, null);
            }
            return in;
        } catch (FileNotFoundException e) {
            throw new MediaReadException("The file no longer exists", false, e);
        } catch (SecurityException e) {
            throw new MediaReadException("No permission to read the file", false, e);
        }
    }

    /** Reads until {@code length} bytes or end of file; returns the count read. */
    private static int readUpTo(InputStream in, byte[] target, int length) throws MediaReadException {
        int total = 0;
        try {
            while (total < length) {
                int n = in.read(target, total, length - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            throw new MediaReadException("Could not read the file: " + e.getMessage(), false, e);
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws MediaReadException {
        long left = count;
        try {
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped > 0) {
                    left -= skipped;
                } else if (in.read() >= 0) {
                    left--;
                } else {
                    throw new MediaReadException("The file got shorter while uploading", true, null);
                }
            }
        } catch (IOException e) {
            throw new MediaReadException("Could not read the file: " + e.getMessage(), false, e);
        }
    }

    private void checkStopped() throws ApiClient.StoppedException {
        if (stop.isStopped()) {
            throw new ApiClient.StoppedException();
        }
    }
}
