package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Gets one ledger item ready for the next commit: hash pass, duplicate checks
 * (this phone's ledger, then the library's index at the branch head), chunk
 * uploads straight to GitHub with every blob sha persisted as it arrives,
 * native thumbnails, and the normalized record. Files are streamed with a
 * single 4 MiB buffer and never loaded whole.
 */
final class MediaUploader {

    enum Outcome {
        /** Its bytes were already committed by this phone (an earlier answer was lost). */
        UPLOADED,
        DUPLICATE,
        FAILED,
        /** The blobs are on GitHub; the next commit adds it (see {@link Prepared#op}). */
        READY,
        /** In the library without a capture date; the next commit gives it this file's date. */
        NEEDS_DATE,
    }

    interface Progress {
        void onProgress(String name, long bytesDone, long bytesTotal);
    }

    /** The worker's batch of prepared items that aren't committed yet. */
    interface Batch {
        /** True when a waiting new item has this whole-file hash ("sha256:&lt;hex&gt;"). */
        boolean hasFile(String fileHash);

        /** Commits the waiting items now. */
        void commitNow() throws IOException;
    }

    static final class Prepared {

        final Outcome outcome;
        /** What the next commit must do, for READY and NEEDS_DATE; null otherwise. */
        final LibraryWriter.Op op;

        Prepared(Outcome outcome, LibraryWriter.Op op) {
            this.outcome = outcome;
            this.op = op;
        }
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
    private static final String FILE_HASH_PREFIX = "sha256:";
    /** Ledger key prefix for a saved thumbnail blob sha ("thumb:320"); chunk shas are keyed by chunk hash. */
    private static final String THUMB_KEY = "thumb:";
    private static final int[] THUMB_SIZES = { 320, 800, 1600 };

    private final Context context;
    private final ContentResolver resolver;
    private final BackupLedger ledger;
    private final BackupPrefs prefs;
    private final GitHubClient github;
    private final LibraryWriter library;
    private final GitHubClient.StopSignal stop;
    private final byte[] buffer = new byte[BackupUtil.CHUNK_SIZE];

    MediaUploader(Context context, BackupLedger ledger, GitHubClient github, LibraryWriter library, GitHubClient.StopSignal stop) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.ledger = ledger;
        this.prefs = BackupPrefs.get(this.context);
        this.github = github;
        this.library = library;
        this.stop = stop;
    }

    /**
     * Item-level problems are recorded in the ledger and reported as FAILED.
     * Sign-in, rate-limit, stop, network and server (5xx) problems are thrown
     * so the worker can stop and resume later; the item keeps its saved
     * progress and goes back to pending.
     */
    Prepared prepare(BackupLedger.Item item, Progress progress, Batch batch) throws IOException {
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
                return new Prepared(Outcome.DUPLICATE, null);
            }

            // Saved blob shas and the id are only valid for the exact same bytes.
            boolean sameContent = hashed.fileHash.equals(item.fileHash);
            Map<String, String> saved = sameContent ? BackupUtil.parseStringMap(item.chunkShasJson) : new HashMap<String, String>();
            keepUsable(saved, hashed);
            String mediaId = sameContent && BackupUtil.isMediaId(item.mediaId) ? item.mediaId : BackupUtil.newMediaId();
            ledger.saveIdentity(item, hashed.fileHash, mediaId, saved);

            String fileHash = FILE_HASH_PREFIX + hashed.fileHash;
            if (batch.hasFile(fileHash)) {
                // A copy of these bytes waits in the uncommitted batch: commit it, then this one is a duplicate.
                batch.commitNow();
            }
            LibraryIndex index = library.headIndex();
            int existing = index.indexOfHash(fileHash);
            if (existing >= 0) {
                return finishExisting(item, index.idAt(existing), index.isDated(existing), mediaId, hashed.fileHash);
            }

            uploadChunks(item, source, hashed, saved, name, progress);
            SortedMap<Integer, String> thumbnails = uploadThumbnails(item, uri, saved);
            Map<String, String> chunkBlobShas = new LinkedHashMap<>();
            for (String hash : hashed.chunkHashes) {
                chunkBlobShas.put(hash, saved.get(hash));
            }
            LibraryWriter.Draft draft = draft(item, uri, name, mime, hashed);
            return new Prepared(Outcome.READY, new LibraryWriter.NewItem(item, draft, mediaId, chunkBlobShas, thumbnails));
        } catch (MediaReadException e) {
            // A vanished file also lands here; the next scan drops its row once MediaStore forgets it.
            ledger.recordFailure(item, e.getMessage(), e.changed);
            return new Prepared(Outcome.FAILED, null);
        } catch (GitHubClient.HttpStatusException e) {
            ledger.recordFailure(item, e.getMessage(), false);
            if (e.isServerError()) {
                throw e;
            }
            return new Prepared(Outcome.FAILED, null);
        } catch (SecurityException e) {
            ledger.recordFailure(item, "No permission to read this file", false);
            return new Prepared(Outcome.FAILED, null);
        }
    }

    /** Records a commit's outcomes in the ledger. */
    void finish(LibraryWriter.Result result) {
        boolean uploaded = false;
        for (LibraryWriter.Outcome outcome : result.outcomes) {
            BackupLedger.Item item = (BackupLedger.Item) outcome.op.tag;
            String fileHash = outcome.op.fileHash.substring(FILE_HASH_PREFIX.length());
            if (outcome.kind == LibraryWriter.Kind.ADDED || outcome.kind == LibraryWriter.Kind.OWN) {
                ledger.markDone(item, outcome.mediaId, fileHash);
                uploaded = true;
            } else {
                ledger.markDuplicate(item, outcome.mediaId, fileHash);
            }
        }
        if (uploaded) {
            prefs.setLastBackupAt(System.currentTimeMillis());
        }
    }

    private Prepared finishExisting(BackupLedger.Item item, String existingId, boolean dated, String mediaId, String fileHash) {
        // Our own earlier commit whose answer was lost still counts as uploaded by this phone.
        if (mediaId.equals(existingId)) {
            ledger.markDone(item, existingId, fileHash);
            prefs.setLastBackupAt(System.currentTimeMillis());
            return new Prepared(Outcome.UPLOADED, null);
        }
        // Best effort: the library copy (an older web upload) has no capture date, so it
        // sorts under its upload day; the next commit gives it this file's date.
        String takenAt = takenAt(item);
        if (!dated && takenAt != null && BackupUtil.isMediaId(existingId)) {
            return new Prepared(Outcome.NEEDS_DATE, new LibraryWriter.DateFix(item, FILE_HASH_PREFIX + fileHash, existingId, takenAt));
        }
        ledger.markDuplicate(item, existingId, fileHash);
        return new Prepared(Outcome.DUPLICATE, null);
    }

    /** MediaStore DATE_TAKEN (ms), else DATE_MODIFIED (s); 0 when neither is known. */
    private static long takenMillis(BackupLedger.Item item) {
        return item.dateTaken > 0 ? item.dateTaken : item.dateModified * 1000L;
    }

    /** The capture date as the record stores it, or null when unknown or implausible. */
    private static String takenAt(BackupLedger.Item item) {
        long taken = takenMillis(item);
        return taken > 0 ? LibraryWriter.normalizeDate(taken) : null;
    }

    /** Drops saved shas that don't belong to these bytes: keeps this file's chunk hashes and "thumb:" keys. */
    private static void keepUsable(Map<String, String> saved, HashResult hashed) {
        Set<String> chunks = new HashSet<>(hashed.chunkHashes);
        Iterator<Map.Entry<String, String>> entries = saved.entrySet().iterator();
        while (entries.hasNext()) {
            String key = entries.next().getKey();
            if (!chunks.contains(key) && !isThumbKey(key)) {
                entries.remove();
            }
        }
    }

    private static boolean isThumbKey(String key) {
        for (int size : THUMB_SIZES) {
            if (key.equals(THUMB_KEY + size)) {
                return true;
            }
        }
        return false;
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

    /** Pass 2: re-reads the file and uploads every chunk without a saved blob sha, one at a time. */
    private void uploadChunks(BackupLedger.Item item, Uri source, HashResult hashed, Map<String, String> saved, String name, Progress progress)
        throws IOException, MediaReadException {
        Map<String, Integer> occurrences = new HashMap<>();
        Set<String> remaining = new HashSet<>();
        for (String hash : hashed.chunkHashes) {
            Integer count = occurrences.get(hash);
            occurrences.put(hash, count == null ? 1 : count + 1);
            if (!saved.containsKey(hash)) {
                remaining.add(hash);
            }
        }
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
                String sha = github.createBlob(buffer, 0, length);
                saved.put(expected, sha);
                ledger.saveChunkShas(item, saved);
                remaining.remove(expected);
                Integer count = occurrences.get(expected);
                done += (long) length * (count == null ? 1 : count);
                progress.onProgress(name, Math.min(done, hashed.size), hashed.size);
            }
        } finally {
            BackupUtil.closeQuietly(in);
        }
    }

    /**
     * Best effort: blob shas of the thumbnails that made it, by size. A webp an
     * earlier attempt uploaded already (same git blob sha), or identical to
     * another size's, isn't sent again.
     */
    private SortedMap<Integer, String> uploadThumbnails(BackupLedger.Item item, Uri uri, Map<String, String> saved) throws IOException {
        SortedMap<Integer, String> shas = new TreeMap<>();
        List<Thumbnails.Thumb> thumbs = Thumbnails.generate(context, uri, item.isVideo(), item.orientation);
        if (thumbs.isEmpty()) {
            // Not decodable this time: thumbnails an earlier attempt uploaded are still good.
            for (int size : THUMB_SIZES) {
                String sha = saved.get(THUMB_KEY + size);
                if (sha != null) {
                    shas.put(size, sha);
                }
            }
            return shas;
        }
        Map<String, String> sent = new HashMap<>();
        for (Thumbnails.Thumb thumb : thumbs) {
            int size = Integer.parseInt(thumb.size);
            String gitSha = BackupUtil.gitBlobSha(thumb.webp);
            String sha = sent.get(gitSha);
            if (sha == null && gitSha.equals(saved.get(THUMB_KEY + size))) {
                sha = gitSha;
            }
            if (sha == null) {
                try {
                    sha = github.createBlob(thumb.webp, 0, thumb.webp.length);
                } catch (GitHubClient.HttpStatusException e) {
                    Log.w(TAG, "Thumbnail upload failed; committing without the rest", e);
                    break;
                }
                saved.put(THUMB_KEY + size, sha);
                ledger.saveChunkShas(item, saved);
            }
            sent.put(gitSha, sha);
            shas.put(size, sha);
        }
        return shas;
    }

    /** Everything the record needs, normalized like normalizeUploadRecord would. */
    private LibraryWriter.Draft draft(BackupLedger.Item item, Uri uri, String name, String mime, HashResult hashed) {
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
        int recordWidth = 0;
        int recordHeight = 0;
        if (width > 0 && height > 0) {
            int normalized = ((rotation % 360) + 360) % 360;
            boolean swap = normalized == 90 || normalized == 270;
            recordWidth = swap ? height : width;
            recordHeight = swap ? width : height;
        }
        JsJson.Num duration = item.isVideo() && durationMs > 0 ? JsJson.Num.secondsFromMillis(durationMs) : null;
        return new LibraryWriter.Draft(
            LibraryWriter.sanitizeFilename(name),
            mime,
            hashed.size,
            BackupUtil.isoUtc(System.currentTimeMillis()),
            takenAt(item),
            recordWidth,
            recordHeight,
            duration,
            FILE_HASH_PREFIX + hashed.fileHash,
            hashed.chunkHashes
        );
    }

    private static String displayName(BackupLedger.Item item) {
        if (item.displayName != null && !item.displayName.trim().isEmpty()) {
            return item.displayName.trim();
        }
        String ext = item.mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime);
        return (item.isVideo() ? "VID_" : "IMG_") + item.mediaStoreId + (ext == null ? "" : "." + ext);
    }

    /** The library only accepts image/* and video/* types. */
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

    private void checkStopped() throws GitHubClient.StoppedException {
        if (stop.isStopped()) {
            throw new GitHubClient.StoppedException();
        }
    }
}
