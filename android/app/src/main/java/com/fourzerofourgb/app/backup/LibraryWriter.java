package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.os.SystemClock;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Library writes as the web app's server made them (lib/github/transaction.ts,
 * lib/media/index.ts commitMediaUpload and setTakenAtIfMissing): one race-safe
 * transaction per batch. Read the branch head, build the new tree on top of
 * it, commit, and move the branch only as a fast-forward; when someone else
 * committed meanwhile, start over from the new head. Duplicate checks run again
 * inside every attempt, so two devices uploading the same file at once still
 * add one entry; so do the checks of date and folder fixes, which never
 * overwrite a value someone else set meanwhile.
 */
final class LibraryWriter {

    /** One thing a commit should do; {@code tag} is the caller's handle for its outcome. */
    abstract static class Op {

        final Object tag;
        /** Whole-file hash, "sha256:&lt;hex&gt;". */
        final String fileHash;

        Op(Object tag, String fileHash) {
            this.tag = tag;
            this.fileHash = fileHash;
        }
    }

    /** A new item whose chunk and thumbnail blobs are on GitHub already. */
    static final class NewItem extends Op {

        final Draft draft;
        /** The id to store it under, unless the library already uses it. */
        final String mediaId;
        /** Blob sha per chunk hash. */
        final Map<String, String> chunkBlobShas;
        /** Blob sha per thumbnail size (320, 800, 1600), ascending. */
        final SortedMap<Integer, String> thumbnailBlobShas;

        NewItem(Object tag, Draft draft, String mediaId, Map<String, String> chunkBlobShas, SortedMap<Integer, String> thumbnailBlobShas) {
            super(tag, draft.hash);
            this.draft = draft;
            this.mediaId = mediaId;
            this.chunkBlobShas = chunkBlobShas;
            this.thumbnailBlobShas = thumbnailBlobShas;
        }
    }

    /** A capture date for an indexed item that has none (setTakenAtIfMissing). */
    static final class DateFix extends Op {

        final String mediaId;
        final String takenAt;

        DateFix(Object tag, String fileHash, String mediaId, String takenAt) {
            super(tag, fileHash);
            this.mediaId = mediaId;
            this.takenAt = takenAt;
        }
    }

    /**
     * A device folder for an indexed item whose entry has none (the folder
     * backfill). Only the index entry changes; the metadata record is left as
     * it is, which saves reading every record (see FolderBackfill).
     */
    static final class FolderFix extends Op {

        final String mediaId;
        final String folder;

        /** {@code fileHash} must be the entry's hash: an entry with other bytes is left alone. */
        FolderFix(Object tag, String fileHash, String mediaId, String folder) {
            super(tag, fileHash);
            this.mediaId = mediaId;
            this.folder = folder;
        }
    }

    enum Kind {
        /** Stored as a new library item. */
        ADDED,
        /** Already in the library under this item's own id: an earlier commit whose answer was lost. */
        OWN,
        /** Already in the library (same bytes) under another id. */
        DUPLICATE,
        /** Its capture date was set. */
        DATE_SET,
        /** Its folder was set. */
        FOLDER_SET,
        /** Nothing to do: the item already has a date (or folder), is gone, or holds other bytes. */
        UNCHANGED,
    }

    static final class Outcome {

        final Op op;
        final Kind kind;
        /** The library id the op ended up as (null only for an indexed entry without an id). */
        final String mediaId;
        /** The index entry, for ADDED. */
        final Map<String, Object> entry;

        Outcome(Op op, Kind kind, String mediaId, Map<String, Object> entry) {
            this.op = op;
            this.kind = kind;
            this.mediaId = mediaId;
            this.entry = entry;
        }
    }

    static final class Result {

        /** The new commit, or null when nothing needed committing. */
        final String commitSha;
        final List<Outcome> outcomes;

        Result(String commitSha, List<Outcome> outcomes) {
            this.commitSha = commitSha;
            this.outcomes = outcomes;
        }

        /** Index entries of the newly added items, in manifest order (newest last). */
        List<Map<String, Object>> addedEntries() {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                if (outcome.kind == Kind.ADDED) {
                    entries.add(outcome.entry);
                }
            }
            return entries;
        }
    }

    /** The branch kept moving under concurrent writes; safe to retry later. */
    static final class ConflictException extends GitHubClient.GitHubException {

        ConflictException(String branch) {
            super(branch + " kept changing under concurrent writes");
        }
    }

    /**
     * A new item's MediaRecord minus its id, normalized the way
     * lib/media/validate.ts normalizeUploadRecord stores records.
     */
    static final class Draft {

        final String filename;
        final String mimeType;
        final long size;
        final String createdAt;
        /** ISO 8601, or null when unknown or implausible. */
        final String takenAt;
        /** 0 when unknown. */
        final int width;
        final int height;
        /** Seconds (videos only), or null. */
        final JsJson.Num duration;
        final String hash;
        final List<String> chunkHashes;
        /** Device folder (BackupUtil.normalizeFolder), or null when unknown. */
        final String folder;

        Draft(
            String filename,
            String mimeType,
            long size,
            String createdAt,
            String takenAt,
            int width,
            int height,
            JsJson.Num duration,
            String hash,
            List<String> chunkHashes,
            String folder
        ) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.size = size;
            this.createdAt = createdAt;
            this.takenAt = takenAt;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.hash = hash;
            this.chunkHashes = chunkHashes;
            this.folder = folder;
        }

        /**
         * The record with keys in normalizeUploadRecord's order (unset fields
         * left out, as JSON.stringify does); folder goes where MediaRecord
         * declares it, after source.
         */
        Map<String, Object> record(String id, Iterable<Integer> thumbnailSizes) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("filename", filename);
            record.put("mimeType", mimeType);
            record.put("size", size);
            record.put("createdAt", createdAt);
            if (takenAt != null) {
                record.put("takenAt", takenAt);
            }
            if (width > 0) {
                record.put("width", (long) width);
            }
            if (height > 0) {
                record.put("height", (long) height);
            }
            if (duration != null) {
                record.put("duration", duration);
            }
            record.put("hash", hash);
            record.put("chunkSize", (long) BackupUtil.CHUNK_SIZE);
            List<Object> chunks = new ArrayList<>(chunkHashes.size());
            for (int i = 0; i < chunkHashes.size(); i++) {
                String chunkHash = chunkHashes.get(i);
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("index", (long) i);
                chunk.put("hash", chunkHash);
                chunk.put("path", BackupUtil.objectPath(chunkHash));
                chunks.add(chunk);
            }
            record.put("chunks", chunks);
            Map<String, Object> thumbnails = new LinkedHashMap<>();
            for (Integer px : thumbnailSizes) {
                thumbnails.put(Integer.toString(px), thumbnailPath(id, px));
            }
            record.put("thumbnails", thumbnails);
            record.put("source", "android-backup");
            if (folder != null) {
                record.put("folder", folder);
            }
            record.put("deletedAt", JsJson.NULL);
            return record;
        }
    }

    private static final int MAX_ATTEMPTS = 6;
    private static final int INLINE_TEXT_MAX_BYTES = 512 * 1024;
    private static final int INLINE_TOTAL_MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final long HEAD_REUSE_MS = 10_000;
    /** Date.UTC(1900, 0, 1). */
    private static final long EARLIEST_CAPTURE_MS = -2208988800000L;
    private static final long FUTURE_TOLERANCE_MS = 2L * 24 * 60 * 60 * 1000;
    private static final int MAX_FILENAME_LENGTH = 255;
    // Backslash, slash, control characters and DEL, as normalizeUploadRecord replaces them.
    private static final Pattern FILENAME_JUNK = Pattern.compile("[\\\\/\\x00-\\x1f\\x7f]");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");

    // Shared by every writer in the process: the last head seen and the root
    // tree of recent commits (commits never change, so these never go stale).
    private static final Object HEAD_LOCK = new Object();
    private static String headLibrary;
    private static GitHubClient.Head lastHead;
    private static long lastHeadAt;
    private static final Map<String, String> TREES = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 32;
        }
    };

    private final Context context;
    private final GitHubClient github;
    private final String library;
    private final String branch;
    private final Random random = new Random();

    LibraryWriter(Context context, GitHubClient github) {
        this.context = context.getApplicationContext();
        this.github = github;
        this.library = LibraryIndex.libraryKey(github.auth());
        this.branch = github.auth().branch;
    }

    /** The index at the branch head, for duplicate checks; a head check from the last few seconds is reused. */
    LibraryIndex headIndex() throws IOException {
        return indexAt(head(true).sha);
    }

    /**
     * Applies {@code ops} in one commit (port of runTransaction). Throws
     * TreeRejectedException when GitHub refuses the tree (unknown blobs) and
     * ConflictException when the branch kept moving for all attempts.
     */
    Result commit(List<Op> ops) throws IOException {
        for (int attempt = 1; ; attempt++) {
            GitHubClient.Head head = head(false);
            String baseTree = treeOf(head.sha);
            Change change = new Change(indexAt(head.sha), head.sha, ops);
            for (Op op : ops) {
                if (op instanceof NewItem) {
                    change.add((NewItem) op);
                } else if (op instanceof DateFix) {
                    change.fixDate((DateFix) op);
                } else {
                    change.fixFolder((FolderFix) op);
                }
            }
            if (!change.changed) {
                return new Result(null, change.outcomes);
            }
            String tree = github.createTree(baseTree, change.treeItems());
            String commit = github.createCommit(change.message(), tree, head.sha);
            if (github.updateHead(commit)) {
                remember(commit, tree, change.entries.build(library, commit, null));
                return new Result(commit, change.outcomes);
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new ConflictException(branch);
            }
            // Someone else committed meanwhile: rebuild on the new head after a jittered ~200 ms * 2^n.
            long base = 200L << (attempt - 1);
            github.pause(base / 2 + (long) (random.nextDouble() * base));
        }
    }

    /** One attempt's changes, built against the index at {@code headSha}. */
    private final class Change {

        final LibraryIndex.Builder entries;
        final String headSha;
        final List<Outcome> outcomes = new ArrayList<>();
        final List<String[]> blobItems = new ArrayList<>();
        final List<String[]> textItems = new ArrayList<>();
        final List<String> addedNames = new ArrayList<>();
        final List<String> datedNames = new ArrayList<>();
        final List<String> folderNames = new ArrayList<>();
        /** Positions of the entries folder fixes target, found in one pass (added entries only go after them). */
        final Map<String, List<Integer>> folderTargets;
        boolean changed;

        Change(LibraryIndex index, String headSha, List<Op> ops) {
            this.entries = new LibraryIndex.Builder(index);
            this.headSha = headSha;
            Set<String> folderIds = new HashSet<>();
            for (Op op : ops) {
                if (op instanceof FolderFix) {
                    folderIds.add(((FolderFix) op).mediaId);
                }
            }
            this.folderTargets = folderIds.isEmpty() ? Collections.<String, List<Integer>>emptyMap() : entries.indexesOfIds(folderIds);
        }

        /** commitMediaUpload's step: skip a file the index has, avoid id clashes, add record and entry. */
        void add(NewItem item) throws IOException {
            int existing = entries.indexOfHash(item.fileHash);
            if (existing >= 0) {
                String existingId = entries.idAt(existing);
                boolean own = item.mediaId.equals(existingId);
                outcomes.add(new Outcome(item, own ? Kind.OWN : Kind.DUPLICATE, existingId, null));
                // The library copy lacks the capture date this copy knows.
                if (!own && item.draft.takenAt != null && !entries.isDated(existing)) {
                    setDate(existingId, item.draft.takenAt);
                }
                return;
            }
            String id = item.mediaId;
            while (entries.hasId(id)) {
                id = BackupUtil.newMediaId();
            }
            Map<String, Object> record = item.draft.record(id, item.thumbnailBlobShas.keySet());
            Map<String, Object> entry = toIndexEntry(record);
            for (Map.Entry<String, String> chunk : item.chunkBlobShas.entrySet()) {
                blobItems.add(new String[] { BackupUtil.objectPath(chunk.getKey()), chunk.getValue() });
            }
            for (Map.Entry<Integer, String> thumb : item.thumbnailBlobShas.entrySet()) {
                blobItems.add(new String[] { thumbnailPath(id, thumb.getKey()), thumb.getValue() });
            }
            textItems.add(new String[] { metadataPath(id), JsJson.stringifyPretty(record) });
            entries.add(entry);
            addedNames.add(item.draft.filename);
            changed = true;
            outcomes.add(new Outcome(item, Kind.ADDED, id, entry));
        }

        void fixDate(DateFix fix) throws IOException {
            boolean set = setDate(fix.mediaId, fix.takenAt);
            outcomes.add(new Outcome(fix, set ? Kind.DATE_SET : Kind.UNCHANGED, fix.mediaId, null));
        }

        /**
         * Adds the folder to the entries with this id, checked again in every
         * attempt: only an entry for the same bytes (hash) that has no folder
         * property yet, so a folder set by anyone else is never overwritten.
         */
        void fixFolder(FolderFix fix) {
            boolean set = false;
            Object filename = null;
            List<Integer> positions = folderTargets.get(fix.mediaId);
            if (positions != null) {
                for (int position : positions) {
                    if (entries.hasFolder(position)) {
                        continue;
                    }
                    Object entry = entries.entry(position);
                    if (!(entry instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) entry;
                    if (!fix.mediaId.equals(map.get("id")) || !fix.fileHash.equals(map.get("hash"))) {
                        continue;
                    }
                    entries.replace(position, withFolder(map, fix.folder));
                    filename = map.get("filename");
                    set = true;
                }
            }
            if (set) {
                folderNames.add(jsString(filename));
                changed = true;
            }
            outcomes.add(new Outcome(fix, set ? Kind.FOLDER_SET : Kind.UNCHANGED, fix.mediaId, null));
        }

        /** setTakenAtIfMissing within this attempt: index entry and metadata, never overwriting a date. */
        private boolean setDate(String id, String takenAt) throws IOException {
            if (!BackupUtil.isMediaId(id)) {
                return false;
            }
            List<Integer> positions = entries.indexesOfId(id);
            if (positions.isEmpty() || entries.isDated(positions.get(0))) {
                return false;
            }
            Object first = entries.entry(positions.get(0));
            if (!(first instanceof Map)) {
                return false;
            }
            // { ...entry, takenAt }: the key keeps its place if present, else goes last.
            Map<String, Object> updated = copyOf((Map<?, ?>) first);
            updated.put("takenAt", takenAt);
            for (int position : positions) {
                entries.replace(position, updated);
            }
            Object record = readJson(metadataPath(id));
            if (record instanceof Map && !JsJson.truthy(((Map<?, ?>) record).get("takenAt"))) {
                Map<String, Object> datedRecord = copyOf((Map<?, ?>) record);
                datedRecord.put("takenAt", takenAt);
                textItems.add(new String[] { metadataPath(id), JsJson.stringifyPretty(datedRecord) });
            }
            datedNames.add(jsString(updated.get("filename")));
            changed = true;
            return true;
        }

        /** A JSON file at this attempt's head; null when missing or unparseable (RepoReader.readJson). */
        private Object readJson(String path) throws IOException {
            GitHubClient.RawFile raw = github.readRaw(path, headSha, null, MAX_METADATA_BYTES);
            if (raw.kind != GitHubClient.RawFile.OK || raw.bytes == null) {
                return null;
            }
            try {
                return JsJson.parse(new String(raw.bytes, StandardCharsets.UTF_8));
            } catch (JsJson.SyntaxException e) {
                return null;
            }
        }

        String message() {
            if (!addedNames.isEmpty()) {
                return addedNames.size() == 1 ? "404gb: upload " + addedNames.get(0) : "404gb: upload " + addedNames.size() + " items";
            }
            if (!datedNames.isEmpty()) {
                return datedNames.size() == 1
                    ? "404gb: set capture date of " + datedNames.get(0)
                    : "404gb: set capture date of " + datedNames.size() + " items";
            }
            return folderNames.size() == 1
                ? "404gb: set folder of " + folderNames.get(0)
                : "404gb: set folder of " + folderNames.size() + " items";
        }

        /**
         * The tree items: blobs by sha, texts inline when each is at most 512 KiB
         * and all together at most 4 MiB, else created as blobs first (a big
         * manifest). The manifest comes last, exactly as serializeJsonArray writes it.
         */
        JSONArray treeItems() throws IOException {
            JSArray items = new JSArray();
            for (String[] blob : blobItems) {
                items.put(treeItem(blob[0]).put("sha", blob[1]));
            }
            List<String[]> texts = new ArrayList<>(textItems);
            texts.add(new String[] { LibraryIndex.PATH, entries.text() });
            long budget = INLINE_TOTAL_MAX_BYTES;
            for (String[] text : texts) {
                byte[] bytes = text[1].getBytes(StandardCharsets.UTF_8);
                JSObject item = treeItem(text[0]);
                if (bytes.length <= INLINE_TEXT_MAX_BYTES && bytes.length <= budget) {
                    budget -= bytes.length;
                    item.put("content", text[1]);
                } else {
                    item.put("sha", github.createBlob(bytes, 0, bytes.length));
                }
                items.put(item);
            }
            return items;
        }
    }

    private GitHubClient.Head head(boolean allowRecent) throws IOException {
        GitHubClient.Head known;
        synchronized (HEAD_LOCK) {
            known = library.equals(headLibrary) ? lastHead : null;
            if (allowRecent && known != null && SystemClock.elapsedRealtime() - lastHeadAt < HEAD_REUSE_MS) {
                return known;
            }
        }
        GitHubClient.Head head = github.head(known);
        synchronized (HEAD_LOCK) {
            headLibrary = library;
            lastHead = head;
            lastHeadAt = SystemClock.elapsedRealtime();
        }
        return head;
    }

    private String treeOf(String commitSha) throws IOException {
        synchronized (TREES) {
            String tree = TREES.get(commitSha);
            if (tree != null) {
                return tree;
            }
        }
        String tree = github.commitTree(commitSha);
        synchronized (TREES) {
            TREES.put(commitSha, tree);
        }
        return tree;
    }

    /** The manifest at a commit: cached, revalidated by ETag, or downloaded and parsed. */
    private LibraryIndex indexAt(String commitSha) throws IOException {
        LibraryIndex cached = LibraryIndex.cached(context, library, commitSha);
        if (cached != null) {
            return cached;
        }
        LibraryIndex newest = LibraryIndex.newest(context, library);
        File download = LibraryIndex.downloadFile(context, library);
        boolean moved = false;
        try {
            GitHubClient.RawFile raw = github.readRawToFile(LibraryIndex.PATH, commitSha, newest == null ? null : newest.etag, download);
            LibraryIndex index;
            if (raw.kind == GitHubClient.RawFile.NOT_MODIFIED) {
                if (newest == null) {
                    throw new GitHubClient.HttpStatusException(502, "GitHub answered 304 to an unconditional read");
                }
                index = newest.atCommit(commitSha);
                LibraryIndex.remember(context, index, null);
            } else if (raw.kind == GitHubClient.RawFile.MISSING) {
                // parseJsonArray: a missing manifest is an empty library.
                index = LibraryIndex.empty(library, commitSha);
                LibraryIndex.remember(context, index, null);
            } else {
                index = LibraryIndex.parse(download, library, commitSha, raw.etag);
                LibraryIndex.remember(context, index, download);
                moved = true;
            }
            return index;
        } finally {
            if (!moved) {
                LibraryIndex.deleteQuietly(download);
            }
        }
    }

    private void remember(String commitSha, String treeSha, LibraryIndex index) {
        synchronized (TREES) {
            TREES.put(commitSha, treeSha);
        }
        synchronized (HEAD_LOCK) {
            headLibrary = library;
            // The ref's ETag for this head is unknown, so the next check is a plain GET.
            lastHead = new GitHubClient.Head(commitSha, null);
            lastHeadAt = SystemClock.elapsedRealtime();
        }
        LibraryIndex.remember(context, index, null);
    }

    private static JSObject treeItem(String path) throws IOException {
        for (String segment : path.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches() || ".".equals(segment) || "..".equals(segment)) {
                throw new GitHubClient.HttpStatusException(400, "Refusing to write unsafe repo path: " + path);
            }
        }
        JSObject item = new JSObject();
        item.put("path", path);
        item.put("mode", "100644");
        item.put("type", "blob");
        return item;
    }

    // ---- record helpers (lib/media/types.ts, lib/media/validate.ts) ----

    static String metadataPath(String id) {
        return "metadata/" + id + ".json";
    }

    static String thumbnailPath(String id, int size) {
        return "thumbnails/" + id + "/" + size + ".webp";
    }

    /** toIndexEntry: the manifest entry for a record, unset fields left out. */
    static Map<String, Object> toIndexEntry(Map<String, Object> record) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", record.get("id"));
        entry.put("filename", record.get("filename"));
        entry.put("hash", record.get("hash"));
        entry.put("mimeType", record.get("mimeType"));
        entry.put("createdAt", record.get("createdAt"));
        putIfSet(entry, "takenAt", record.get("takenAt"));
        Object thumbnails = record.get("thumbnails");
        if (thumbnails instanceof Map) {
            putIfSet(entry, "thumb", ((Map<?, ?>) thumbnails).get("320"));
        }
        putIfSet(entry, "width", record.get("width"));
        putIfSet(entry, "height", record.get("height"));
        if (record.get("duration") != null) {
            entry.put("duration", record.get("duration"));
        }
        putIfSet(entry, "size", record.get("size"));
        if (JsJson.truthy(record.get("favorite"))) {
            entry.put("favorite", Boolean.TRUE);
        }
        putIfSet(entry, "editedFrom", record.get("editedFrom"));
        putIfSet(entry, "folder", record.get("folder"));
        putIfSet(entry, "trashedAt", record.get("trashedAt"));
        return entry;
    }

    /**
     * An existing entry plus {@code folder}, every other property kept as it
     * is. The folder goes where toIndexEntry puts it: before trashedAt (which
     * comes last) when the entry has one, else at the end.
     */
    static Map<String, Object> withFolder(Map<?, ?> entry, String folder) {
        Map<String, Object> updated = new LinkedHashMap<>();
        boolean placed = false;
        for (Map.Entry<?, ?> property : entry.entrySet()) {
            if (!placed && "trashedAt".equals(property.getKey())) {
                updated.put("folder", folder);
                placed = true;
            }
            updated.put((String) property.getKey(), property.getValue());
        }
        if (!placed) {
            updated.put("folder", folder);
        }
        return updated;
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (JsJson.truthy(value)) {
            map.put(key, value);
        }
    }

    /** normalizeDate for an epoch time: ISO 8601 between 1900 and two days from now, else null. */
    static String normalizeDate(long millis) {
        if (millis < EARLIEST_CAPTURE_MS || millis > System.currentTimeMillis() + FUTURE_TOLERANCE_MS) {
            return null;
        }
        return BackupUtil.isoUtc(millis);
    }

    /** sanitizeFilename: separators and control characters become "_", trimmed, at most 255 chars. */
    static String sanitizeFilename(String value) {
        String name = value == null ? "" : JsJson.jsTrim(FILENAME_JUNK.matcher(value).replaceAll("_"));
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(0, MAX_FILENAME_LENGTH);
        }
        return name.isEmpty() ? "untitled" : name;
    }

    /** An index entry or record as the JSObject the web app receives over the bridge. */
    static JSObject toJsObject(Map<?, ?> map) {
        JSObject out = new JSObject();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                out.put((String) entry.getKey(), toJsValue(entry.getValue()));
            }
        }
        return out;
    }

    private static Object toJsValue(Object value) {
        if (value == JsJson.NULL) {
            return JSONObject.NULL;
        }
        if (value instanceof JsJson.Num) {
            JsJson.Num number = (JsJson.Num) value;
            try {
                return Long.parseLong(number.text);
            } catch (NumberFormatException notWhole) {
                return number.doubleValue();
            }
        }
        if (value instanceof Map) {
            return toJsObject((Map<?, ?>) value);
        }
        if (value instanceof List) {
            JSArray array = new JSArray();
            for (Object item : (List<?>) value) {
                array.put(toJsValue(item));
            }
            return array;
        }
        return value;
    }

    private static Map<String, Object> copyOf(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put((String) entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /** How a JavaScript template literal shows a value (for commit messages). */
    private static String jsString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        return value == null ? "undefined" : JsJson.stringify(value);
    }
}
