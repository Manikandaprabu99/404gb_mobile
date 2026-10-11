package com.fourzerofourgb.app.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable record of every photo and video the backup has seen, keyed by
 * MediaStore collection + id. A row leaves "pending" only after its commit
 * landed on GitHub (or the library turned out to have the file already), so
 * no watermark can ever skip an item, and the stored blob shas (chunks and
 * thumbnails) let an interrupted upload resume without re-sending bytes.
 *
 * The upload worker's per-item writes only apply while the row is still
 * "uploading": if the user switched to another repo meanwhile (which resets
 * every row), a late answer from the old repo can't mark anything done.
 */
final class BackupLedger extends SQLiteOpenHelper {

    static final String COLLECTION_IMAGES = "images";
    static final String COLLECTION_VIDEO = "video";

    static final String STATE_PENDING = "pending";
    static final String STATE_UPLOADING = "uploading";
    static final String STATE_DONE = "done";
    static final String STATE_DUPLICATE = "duplicate";
    static final String STATE_FAILED = "failed";

    static final String ORIGIN_BACKUP = "backup";
    static final String ORIGIN_DOWNLOAD = "download";

    /** Item-level failures (bad response, unreadable file) before an item is parked as failed. */
    static final int MAX_ATTEMPTS = 5;

    static final String COL_COLLECTION = "collection";
    static final String COL_MEDIA_STORE_ID = "media_store_id";
    static final String COL_URI = "uri";
    static final String COL_DISPLAY_NAME = "display_name";
    static final String COL_MIME = "mime";
    static final String COL_SIZE = "size";
    static final String COL_DATE_TAKEN = "date_taken";
    static final String COL_DATE_MODIFIED = "date_modified";
    static final String COL_DATE_ADDED = "date_added";
    static final String COL_WIDTH = "width";
    static final String COL_HEIGHT = "height";
    static final String COL_ORIENTATION = "orientation";
    static final String COL_DURATION = "duration";
    static final String COL_STATE = "state";
    static final String COL_ATTEMPTS = "attempts";
    static final String COL_FILE_HASH = "file_hash";
    static final String COL_MEDIA_ID = "media_id";
    static final String COL_CHUNK_SHAS = "chunk_shas";
    static final String COL_LAST_ERROR = "last_error";
    static final String COL_ORIGIN = "origin";
    static final String COL_UPDATED_AT = "updated_at";

    private static final String DB_NAME = "404gb_backup.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "items";
    private static final String KEY_WHERE = COL_COLLECTION + "=? AND " + COL_MEDIA_STORE_ID + "=?";
    private static final String UPLOADING_WHERE = KEY_WHERE + " AND " + COL_STATE + "='" + STATE_UPLOADING + "'";
    private static final String NOT_UPLOADED = COL_STATE + " IN ('" + STATE_PENDING + "','" + STATE_FAILED + "')";
    private static final int MAX_ERROR_LENGTH = 500;

    private static volatile BackupLedger instance;

    static BackupLedger get(Context context) {
        BackupLedger local = instance;
        if (local == null) {
            synchronized (BackupLedger.class) {
                local = instance;
                if (local == null) {
                    local = new BackupLedger(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private BackupLedger(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " +
            TABLE +
            " (" +
            COL_COLLECTION +
            " TEXT NOT NULL, " +
            COL_MEDIA_STORE_ID +
            " INTEGER NOT NULL, " +
            COL_URI +
            " TEXT NOT NULL, " +
            COL_DISPLAY_NAME +
            " TEXT, " +
            COL_MIME +
            " TEXT, " +
            COL_SIZE +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_DATE_TAKEN +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_DATE_MODIFIED +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_DATE_ADDED +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_WIDTH +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_HEIGHT +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_ORIENTATION +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_DURATION +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_STATE +
            " TEXT NOT NULL, " +
            COL_ATTEMPTS +
            " INTEGER NOT NULL DEFAULT 0, " +
            COL_FILE_HASH +
            " TEXT, " +
            COL_MEDIA_ID +
            " TEXT, " +
            COL_CHUNK_SHAS +
            " TEXT, " +
            COL_LAST_ERROR +
            " TEXT, " +
            COL_ORIGIN +
            " TEXT NOT NULL DEFAULT '" +
            ORIGIN_BACKUP +
            "', " +
            COL_UPDATED_AT +
            " INTEGER NOT NULL, " +
            "PRIMARY KEY (" +
            COL_COLLECTION +
            ", " +
            COL_MEDIA_STORE_ID +
            "))"
        );
        db.execSQL("CREATE INDEX items_state_added ON " + TABLE + " (" + COL_STATE + ", " + COL_DATE_ADDED + ")");
        db.execSQL("CREATE INDEX items_file_hash ON " + TABLE + " (" + COL_FILE_HASH + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the only schema so far.
    }

    /** One ledger row. */
    static final class Item {

        String collection;
        long mediaStoreId;
        String uri;
        String displayName;
        String mime;
        long size;
        /** Epoch millis, 0 when MediaStore has none. */
        long dateTaken;
        /** Epoch seconds, as MediaStore stores it. */
        long dateModified;
        /** Epoch seconds, as MediaStore stores it. */
        long dateAdded;
        int width;
        int height;
        int orientation;
        long durationMs;
        String state;
        int attempts;
        String fileHash;
        String mediaId;
        String chunkShasJson;
        String lastError;

        String key() {
            return collection + ":" + mediaStoreId;
        }

        boolean isVideo() {
            return COLLECTION_VIDEO.equals(collection);
        }
    }

    /** The fields a scan compares to decide whether a row changed. */
    static final class Snapshot {

        final String state;
        final long size;
        final long dateModified;

        Snapshot(String state, long size, long dateModified) {
            this.state = state;
            this.size = size;
            this.dateModified = dateModified;
        }

        boolean isUploaded() {
            return STATE_DONE.equals(state) || STATE_DUPLICATE.equals(state);
        }

        boolean isWaiting() {
            return STATE_PENDING.equals(state) || STATE_FAILED.equals(state);
        }
    }

    /** A MediaStore row prepared by the scanner. */
    static final class ScanRow {

        final long mediaStoreId;
        final ContentValues values;

        ScanRow(long mediaStoreId, ContentValues values) {
            this.mediaStoreId = mediaStoreId;
            this.values = values;
        }
    }

    static final class Counts {

        int pending;
        int uploaded;
        int duplicates;
        int failed;
    }

    // ---- scanning ----

    Map<Long, Snapshot> snapshot(String collection) {
        Map<Long, Snapshot> out = new HashMap<>();
        String[] columns = { COL_MEDIA_STORE_ID, COL_STATE, COL_SIZE, COL_DATE_MODIFIED };
        try (
            Cursor c = getReadableDatabase().query(TABLE, columns, COL_COLLECTION + "=?", new String[] { collection }, null, null, null)
        ) {
            while (c.moveToNext()) {
                out.put(c.getLong(0), new Snapshot(c.getString(1), c.getLong(2), c.getLong(3)));
            }
        }
        return out;
    }

    /**
     * Inserts newly discovered rows as pending and refreshes rows whose file
     * changed before it was uploaded. Updates are conditional on the row still
     * not being uploaded, so a scan racing the upload worker can't undo a commit.
     *
     * @return how many rows were newly queued
     */
    int applyScan(String collection, List<ScanRow> inserts, List<ScanRow> resets) {
        if (inserts.isEmpty() && resets.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (ScanRow row : inserts) {
                if (db.insertWithOnConflict(TABLE, null, row.values, SQLiteDatabase.CONFLICT_IGNORE) != -1) {
                    inserted++;
                }
            }
            for (ScanRow row : resets) {
                db.update(TABLE, row.values, KEY_WHERE + " AND " + NOT_UPLOADED, keyArgs(collection, row.mediaStoreId));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    /** Drops queued rows whose MediaStore item is gone or no longer matches the folder setting. */
    void deleteUnseen(String collection, List<Long> mediaStoreIds) {
        if (mediaStoreIds.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : mediaStoreIds) {
                db.delete(TABLE, KEY_WHERE + " AND " + NOT_UPLOADED, keyArgs(collection, id));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- uploading ----

    /** Newest pending item that isn't in {@code skip}, or null. */
    Item nextPending(Set<String> skip) {
        String limit = Integer.toString(skip.size() + 1);
        try (
            Cursor c = getReadableDatabase()
                .query(
                    TABLE,
                    null,
                    COL_STATE + "=?",
                    new String[] { STATE_PENDING },
                    null,
                    null,
                    COL_DATE_ADDED + " DESC, " + COL_MEDIA_STORE_ID + " DESC",
                    limit
                )
        ) {
            while (c.moveToNext()) {
                Item item = read(c);
                if (!skip.contains(item.key())) {
                    return item;
                }
            }
        }
        return null;
    }

    int pendingCount() {
        return (int) DatabaseUtils.queryNumEntries(
            getReadableDatabase(),
            TABLE,
            COL_STATE + " IN (?,?)",
            new String[] { STATE_PENDING, STATE_UPLOADING }
        );
    }

    /** Rows left "uploading" by a worker that was stopped or killed go back to the queue. */
    void resetInterrupted() {
        ContentValues values = new ContentValues();
        values.put(COL_STATE, STATE_PENDING);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, COL_STATE + "=?", new String[] { STATE_UPLOADING });
    }

    void markUploading(Item item) {
        ContentValues values = new ContentValues();
        values.put(COL_STATE, STATE_UPLOADING);
        update(item, values);
    }

    /**
     * Persists the hash and id an upload is working under, plus the blob shas
     * so far (chunk hash -> sha, and "thumb:&lt;size&gt;" -> sha).
     */
    void saveIdentity(Item item, String fileHash, String mediaId, Map<String, String> blobShas) {
        ContentValues values = new ContentValues();
        values.put(COL_FILE_HASH, fileHash);
        values.put(COL_MEDIA_ID, mediaId);
        values.put(COL_CHUNK_SHAS, BackupUtil.toJson(blobShas));
        updateWhileUploading(item, values);
    }

    void saveChunkShas(Item item, Map<String, String> blobShas) {
        ContentValues values = new ContentValues();
        values.put(COL_CHUNK_SHAS, BackupUtil.toJson(blobShas));
        updateWhileUploading(item, values);
    }

    void markDone(Item item, String mediaId, String fileHash) {
        finish(item, STATE_DONE, mediaId, fileHash);
    }

    void markDuplicate(Item item, String mediaId, String fileHash) {
        finish(item, STATE_DUPLICATE, mediaId, fileHash);
    }

    private void finish(Item item, String state, String mediaId, String fileHash) {
        ContentValues values = new ContentValues();
        values.put(COL_STATE, state);
        values.put(COL_MEDIA_ID, mediaId);
        values.put(COL_FILE_HASH, fileHash);
        values.putNull(COL_CHUNK_SHAS);
        values.putNull(COL_LAST_ERROR);
        updateWhileUploading(item, values);
    }

    /** Counts one failed attempt; the item is parked as failed after {@link #MAX_ATTEMPTS}. */
    void recordFailure(Item item, String error, boolean clearChunkShas) {
        String message = error == null ? "Unknown error" : error;
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        // SQLite evaluates every SET expression against the old row, so both
        // CASE and the increment see the same pre-update attempts value.
        getWritableDatabase()
            .execSQL(
                "UPDATE " +
                TABLE +
                " SET " +
                COL_ATTEMPTS +
                " = " +
                COL_ATTEMPTS +
                " + 1, " +
                COL_STATE +
                " = CASE WHEN " +
                COL_ATTEMPTS +
                " + 1 >= ? THEN '" +
                STATE_FAILED +
                "' ELSE '" +
                STATE_PENDING +
                "' END, " +
                COL_LAST_ERROR +
                " = ?, " +
                COL_UPDATED_AT +
                " = ?" +
                (clearChunkShas ? ", " + COL_CHUNK_SHAS + " = NULL" : "") +
                " WHERE " +
                UPLOADING_WHERE,
                new Object[] { MAX_ATTEMPTS, message, System.currentTimeMillis(), item.collection, item.mediaStoreId }
            );
    }

    /**
     * Back to the queue without counting an attempt, forgetting the saved blob
     * shas (GitHub no longer knows one of them) but keeping hash and id.
     */
    void requeueForReupload(Item item) {
        ContentValues values = new ContentValues();
        values.put(COL_STATE, STATE_PENDING);
        values.putNull(COL_CHUNK_SHAS);
        updateWhileUploading(item, values);
    }

    void delete(Item item) {
        getWritableDatabase().delete(TABLE, KEY_WHERE, keyArgs(item.collection, item.mediaStoreId));
    }

    /**
     * Records which library ("owner/repo") uploads go to. Returns true when it
     * changed from a known one, in which case the ledger has been reset.
     */
    boolean adoptLibrary(BackupPrefs prefs, String library) {
        synchronized (BackupLedger.class) {
            String known = prefs.library();
            if (known != null && known.equalsIgnoreCase(library)) {
                return false;
            }
            prefs.setLibrary(library);
            if (known == null) {
                return false;
            }
            resetForNewLibrary();
            return true;
        }
    }

    /**
     * The phone now backs up into a different repo: what it uploaded or skipped
     * belongs to the old one, so every backup row goes back in the queue and
     * saved blob shas (unknown to the new repo) are dropped. Rows for files
     * saved from the old library are deleted so the next scan queues those
     * files like any other. Rows being uploaded right now are reset too; the
     * worker's own later writes to them no longer apply (see updateWhileUploading).
     */
    private void resetForNewLibrary() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(COL_STATE, STATE_PENDING);
            values.put(COL_ATTEMPTS, 0);
            values.putNull(COL_MEDIA_ID);
            values.putNull(COL_CHUNK_SHAS);
            values.putNull(COL_LAST_ERROR);
            values.put(COL_UPDATED_AT, System.currentTimeMillis());
            db.update(
                TABLE,
                values,
                COL_ORIGIN + "=? AND " + COL_STATE + " IN (?,?,?,?,?)",
                new String[] { ORIGIN_BACKUP, STATE_PENDING, STATE_UPLOADING, STATE_DONE, STATE_DUPLICATE, STATE_FAILED }
            );
            db.delete(TABLE, COL_ORIGIN + "=?", new String[] { ORIGIN_DOWNLOAD });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    int retryFailed() {
        ContentValues values = new ContentValues();
        values.put(COL_STATE, STATE_PENDING);
        values.put(COL_ATTEMPTS, 0);
        values.putNull(COL_LAST_ERROR);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase().update(TABLE, values, COL_STATE + "=?", new String[] { STATE_FAILED });
    }

    /** Library id of another row with the same bytes that is already in the library, or null. */
    String findBackedUpMediaId(String fileHash, Item except) {
        try (
            Cursor c = getReadableDatabase()
                .rawQuery(
                    "SELECT " +
                    COL_MEDIA_ID +
                    " FROM " +
                    TABLE +
                    " WHERE " +
                    COL_FILE_HASH +
                    "=? AND " +
                    COL_STATE +
                    " IN ('" +
                    STATE_DONE +
                    "','" +
                    STATE_DUPLICATE +
                    "') AND " +
                    COL_MEDIA_ID +
                    " IS NOT NULL AND NOT (" +
                    KEY_WHERE +
                    ") LIMIT 1",
                    new String[] { fileHash, except.collection, Long.toString(except.mediaStoreId) }
                )
        ) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Records a file saved from the library onto the phone so backup never re-uploads it. */
    void insertDownloaded(
        String collection,
        long mediaStoreId,
        String uri,
        String displayName,
        String mime,
        long size,
        String fileHash,
        String mediaId
    ) {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        ContentValues values = new ContentValues();
        values.put(COL_COLLECTION, collection);
        values.put(COL_MEDIA_STORE_ID, mediaStoreId);
        values.put(COL_URI, uri);
        values.put(COL_DISPLAY_NAME, displayName);
        values.put(COL_MIME, mime);
        values.put(COL_SIZE, size);
        values.put(COL_DATE_MODIFIED, nowSeconds);
        values.put(COL_DATE_ADDED, nowSeconds);
        values.put(COL_STATE, STATE_DONE);
        values.put(COL_FILE_HASH, fileHash);
        values.put(COL_MEDIA_ID, mediaId);
        values.put(COL_ORIGIN, ORIGIN_DOWNLOAD);
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void deleteRow(String collection, long mediaStoreId) {
        getWritableDatabase().delete(TABLE, KEY_WHERE, keyArgs(collection, mediaStoreId));
    }

    Counts counts() {
        Counts counts = new Counts();
        try (
            Cursor c = getReadableDatabase()
                .rawQuery(
                    "SELECT " + COL_STATE + ", " + COL_ORIGIN + ", COUNT(*) FROM " + TABLE + " GROUP BY " + COL_STATE + ", " + COL_ORIGIN,
                    null
                )
        ) {
            while (c.moveToNext()) {
                String state = c.getString(0);
                String origin = c.getString(1);
                int n = c.getInt(2);
                if (STATE_PENDING.equals(state) || STATE_UPLOADING.equals(state)) {
                    counts.pending += n;
                } else if (STATE_DONE.equals(state)) {
                    if (ORIGIN_BACKUP.equals(origin)) {
                        counts.uploaded += n;
                    }
                } else if (STATE_DUPLICATE.equals(state)) {
                    counts.duplicates += n;
                } else if (STATE_FAILED.equals(state)) {
                    counts.failed += n;
                }
            }
        }
        return counts;
    }

    private void update(Item item, ContentValues values) {
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, KEY_WHERE, keyArgs(item.collection, item.mediaStoreId));
    }

    /** A worker write: only applies while the row is still the one being uploaded. */
    private void updateWhileUploading(Item item, ContentValues values) {
        values.put(COL_UPDATED_AT, System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, UPLOADING_WHERE, keyArgs(item.collection, item.mediaStoreId));
    }

    private static String[] keyArgs(String collection, long mediaStoreId) {
        return new String[] { collection, Long.toString(mediaStoreId) };
    }

    private static Item read(Cursor c) {
        Item item = new Item();
        item.collection = c.getString(c.getColumnIndexOrThrow(COL_COLLECTION));
        item.mediaStoreId = c.getLong(c.getColumnIndexOrThrow(COL_MEDIA_STORE_ID));
        item.uri = c.getString(c.getColumnIndexOrThrow(COL_URI));
        item.displayName = c.getString(c.getColumnIndexOrThrow(COL_DISPLAY_NAME));
        item.mime = c.getString(c.getColumnIndexOrThrow(COL_MIME));
        item.size = c.getLong(c.getColumnIndexOrThrow(COL_SIZE));
        item.dateTaken = c.getLong(c.getColumnIndexOrThrow(COL_DATE_TAKEN));
        item.dateModified = c.getLong(c.getColumnIndexOrThrow(COL_DATE_MODIFIED));
        item.dateAdded = c.getLong(c.getColumnIndexOrThrow(COL_DATE_ADDED));
        item.width = c.getInt(c.getColumnIndexOrThrow(COL_WIDTH));
        item.height = c.getInt(c.getColumnIndexOrThrow(COL_HEIGHT));
        item.orientation = c.getInt(c.getColumnIndexOrThrow(COL_ORIENTATION));
        item.durationMs = c.getLong(c.getColumnIndexOrThrow(COL_DURATION));
        item.state = c.getString(c.getColumnIndexOrThrow(COL_STATE));
        item.attempts = c.getInt(c.getColumnIndexOrThrow(COL_ATTEMPTS));
        item.fileHash = c.getString(c.getColumnIndexOrThrow(COL_FILE_HASH));
        item.mediaId = c.getString(c.getColumnIndexOrThrow(COL_MEDIA_ID));
        item.chunkShasJson = c.getString(c.getColumnIndexOrThrow(COL_CHUNK_SHAS));
        item.lastError = c.getString(c.getColumnIndexOrThrow(COL_LAST_ERROR));
        return item;
    }
}
