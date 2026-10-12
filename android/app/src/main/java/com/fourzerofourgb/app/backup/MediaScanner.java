package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles MediaStore with the ledger: every matching photo/video gets a
 * row, rows whose file changed before upload are reset for re-hashing, and
 * queued rows whose item disappeared (or no longer matches the folder
 * setting) are dropped. Uploaded rows are never touched, so the ledger also
 * serves as the identity record that prevents duplicates; the one exception
 * is filling in the device folder of a row that doesn't know it yet (rows
 * from before the ledger recorded folders).
 */
final class MediaScanner {

    private static final String TAG = "404gb.MediaScanner";

    // Column names that exist on every supported API level for these tables;
    // the SDK constants for them moved to MediaColumns in API 29.
    private static final String COL_DATE_TAKEN = "datetaken";
    private static final String COL_ORIENTATION = "orientation";
    private static final String COL_DURATION = "duration";

    private static final int BATCH = 500;
    // Ids per MediaStore lookup: under SQLite's 999 bound arguments on older Android versions.
    private static final int LOOKUP_BATCH = 500;
    private static final long LEGACY_SETTLE_SECONDS = 10;
    private static final Object LOCK = new Object();

    /** elapsedRealtime of the last scan that read both collections, or 0. */
    private static volatile long lastCompletedAt;

    private MediaScanner() {}

    /** True when a scan finished within {@code millis}, so another one would find nothing new. */
    static boolean scannedWithin(long millis) {
        long last = lastCompletedAt;
        return last != 0 && SystemClock.elapsedRealtime() - last < millis;
    }

    /** @return the number of newly queued items, or 0 when media access is missing */
    static int scan(Context context) {
        Context app = context.getApplicationContext();
        if (!MediaAccess.hasFullAccess(app)) {
            return 0;
        }
        BackupPrefs prefs = BackupPrefs.get(app);
        synchronized (LOCK) {
            int added = 0;
            added += scanCollection(app, prefs, BackupLedger.COLLECTION_IMAGES);
            added += scanCollection(app, prefs, BackupLedger.COLLECTION_VIDEO);
            lastCompletedAt = SystemClock.elapsedRealtime();
            return added;
        }
    }

    private static int scanCollection(Context context, BackupPrefs prefs, String collection) {
        boolean video = BackupLedger.COLLECTION_VIDEO.equals(collection);
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        StringBuilder selection = new StringBuilder();
        List<String> args = new ArrayList<>();
        long cutoff = prefs.cutoffSeconds();
        if (cutoff > 0) {
            selection.append(MediaStore.MediaColumns.DATE_ADDED).append(" >= ?");
            args.add(Long.toString(cutoff));
        }
        if (BackupPrefs.FOLDERS_CAMERA.equals(prefs.folders())) {
            if (selection.length() > 0) {
                selection.append(" AND ");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection.append(MediaStore.MediaColumns.RELATIVE_PATH).append(" LIKE ?");
                args.add("DCIM/%");
            } else {
                selection.append(legacyDataColumn()).append(" LIKE ?");
                args.add("%/DCIM/%");
            }
        }
        String where = selection.length() > 0 ? selection.toString() : null;
        String[] whereArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        String order = MediaStore.MediaColumns.DATE_ADDED + " DESC";

        ContentResolver resolver = context.getContentResolver();
        Cursor cursor;
        try {
            cursor = resolver.query(base, projection(video, true), where, whereArgs, order);
        } catch (IllegalArgumentException | SQLiteException e) {
            // Some OEM MediaStore builds lack optional columns; retry with the basics.
            Log.w(TAG, "Full projection rejected for " + collection + ", retrying with core columns", e);
            try {
                cursor = resolver.query(base, projection(video, false), where, whereArgs, order);
            } catch (IllegalArgumentException | SQLiteException | SecurityException e2) {
                Log.w(TAG, "MediaStore query failed for " + collection, e2);
                return 0;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to read " + collection, e);
            return 0;
        }
        if (cursor == null) {
            // Provider unavailable: leave the ledger alone rather than treat it as empty.
            return 0;
        }

        BackupLedger ledger = BackupLedger.get(context);
        Map<Long, BackupLedger.Snapshot> known = ledger.snapshot(collection);
        Set<Long> seen = new HashSet<>();
        List<BackupLedger.ScanRow> inserts = new ArrayList<>();
        List<BackupLedger.ScanRow> resets = new ArrayList<>();
        List<BackupLedger.ScanRow> folders = new ArrayList<>();
        int added = 0;
        boolean complete = false;
        long nowMillis = System.currentTimeMillis();
        long nowSeconds = nowMillis / 1000L;

        try {
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            int modifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int takenCol = cursor.getColumnIndex(COL_DATE_TAKEN);
            int widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int orientationCol = cursor.getColumnIndex(COL_ORIENTATION);
            int durationCol = cursor.getColumnIndex(COL_DURATION);
            int pendingCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING) : -1;
            int folderCol = cursor.getColumnIndex(folderColumn());

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                seen.add(id);

                long size = longAt(cursor, sizeCol);
                long dateModified = longAt(cursor, modifiedCol);
                if (pendingCol >= 0 && longAt(cursor, pendingCol) == 1) {
                    continue; // still being written; a later scan picks it up
                }
                if (size <= 0) {
                    continue;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    long age = nowSeconds - dateModified;
                    if (age >= 0 && age < LEGACY_SETTLE_SECONDS) {
                        continue; // no IS_PENDING before Android 10; let the writer finish
                    }
                }

                String folder = folderAt(cursor, folderCol);
                BackupLedger.Snapshot snapshot = known.get(id);
                boolean changed = snapshot != null && (snapshot.size != size || snapshot.dateModified != dateModified);
                if (snapshot != null && (snapshot.isUploaded() || BackupLedger.STATE_UPLOADING.equals(snapshot.state) || !changed)) {
                    // Nothing to queue, but the folder is recorded: an uploaded row only gets a
                    // missing one (it keeps the folder it was uploaded from), a queued row
                    // follows its file into another folder.
                    if (folder != null && (snapshot.folder == null || (snapshot.isWaiting() && !folder.equals(snapshot.folder)))) {
                        folders.add(folderRow(id, folder));
                    }
                } else {
                    ContentValues values = new ContentValues();
                    values.put(BackupLedger.COL_URI, ContentUris.withAppendedId(base, id).toString());
                    values.put(BackupLedger.COL_DISPLAY_NAME, stringAt(cursor, nameCol));
                    values.put(BackupLedger.COL_MIME, stringAt(cursor, mimeCol));
                    values.put(BackupLedger.COL_SIZE, size);
                    values.put(BackupLedger.COL_DATE_TAKEN, longAt(cursor, takenCol));
                    values.put(BackupLedger.COL_DATE_MODIFIED, dateModified);
                    values.put(BackupLedger.COL_DATE_ADDED, longAt(cursor, addedCol));
                    values.put(BackupLedger.COL_WIDTH, (int) longAt(cursor, widthCol));
                    values.put(BackupLedger.COL_HEIGHT, (int) longAt(cursor, heightCol));
                    values.put(BackupLedger.COL_ORIENTATION, (int) longAt(cursor, orientationCol));
                    values.put(BackupLedger.COL_DURATION, longAt(cursor, durationCol));
                    values.put(BackupLedger.COL_FOLDER, folder);
                    values.put(BackupLedger.COL_STATE, BackupLedger.STATE_PENDING);
                    values.put(BackupLedger.COL_ATTEMPTS, 0);
                    values.put(BackupLedger.COL_UPDATED_AT, nowMillis);

                    if (snapshot == null) {
                        values.put(BackupLedger.COL_COLLECTION, collection);
                        values.put(BackupLedger.COL_MEDIA_STORE_ID, id);
                        values.put(BackupLedger.COL_ORIGIN, BackupLedger.ORIGIN_BACKUP);
                        inserts.add(new BackupLedger.ScanRow(id, values));
                    } else {
                        // The file changed before it was uploaded: hash it again from scratch.
                        values.putNull(BackupLedger.COL_FILE_HASH);
                        values.putNull(BackupLedger.COL_MEDIA_ID);
                        values.putNull(BackupLedger.COL_CHUNK_SHAS);
                        values.putNull(BackupLedger.COL_LAST_ERROR);
                        resets.add(new BackupLedger.ScanRow(id, values));
                    }
                }

                if (inserts.size() + resets.size() + folders.size() >= BATCH) {
                    added += ledger.applyScan(collection, inserts, resets, folders);
                    inserts.clear();
                    resets.clear();
                    folders.clear();
                }
            }
            added += ledger.applyScan(collection, inserts, resets, folders);
            complete = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Scan of " + collection + " stopped early", e);
        } finally {
            cursor.close();
        }

        if (complete) {
            List<Long> gone = new ArrayList<>();
            for (Map.Entry<Long, BackupLedger.Snapshot> entry : known.entrySet()) {
                if (entry.getValue().isWaiting() && !seen.contains(entry.getKey())) {
                    gone.add(entry.getKey());
                }
            }
            ledger.deleteUnseen(collection, gone);
        }
        return added;
    }

    private static String[] projection(boolean video, boolean full) {
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.MediaColumns._ID);
        columns.add(MediaStore.MediaColumns.DISPLAY_NAME);
        columns.add(MediaStore.MediaColumns.MIME_TYPE);
        columns.add(MediaStore.MediaColumns.SIZE);
        columns.add(MediaStore.MediaColumns.DATE_ADDED);
        columns.add(MediaStore.MediaColumns.DATE_MODIFIED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.MediaColumns.RELATIVE_PATH);
            columns.add(MediaStore.MediaColumns.IS_PENDING);
        } else {
            columns.add(legacyDataColumn());
        }
        if (full) {
            columns.add(COL_DATE_TAKEN);
            columns.add(MediaStore.MediaColumns.WIDTH);
            columns.add(MediaStore.MediaColumns.HEIGHT);
            if (video) {
                columns.add(COL_DURATION);
                // Videos only gained a populated orientation column in Android 11.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    columns.add(COL_ORIENTATION);
                }
            } else {
                columns.add(COL_ORIENTATION);
            }
        }
        return columns.toArray(new String[0]);
    }

    /**
     * Looks up the device folder of uploaded rows that don't know it yet (rows
     * from before the ledger recorded folders, outside what scans read: older
     * than the cutoff, or outside DCIM with the camera-only setting). Rows whose
     * item is gone from MediaStore stay without one.
     *
     * @return false when MediaStore couldn't be read, so some rows weren't looked up
     */
    static boolean fillMissingFolders(Context context) {
        Context app = context.getApplicationContext();
        if (!MediaAccess.hasFullAccess(app)) {
            return false;
        }
        BackupLedger ledger = BackupLedger.get(app);
        List<BackupLedger.ScanRow> none = Collections.emptyList();
        boolean complete = true;
        for (String collection : new String[] { BackupLedger.COLLECTION_IMAGES, BackupLedger.COLLECTION_VIDEO }) {
            List<Long> ids = ledger.uploadedWithoutFolder(collection);
            for (int start = 0; start < ids.size(); start += LOOKUP_BATCH) {
                List<BackupLedger.ScanRow> found = lookUpFolders(app, collection, ids.subList(start, Math.min(ids.size(), start + LOOKUP_BATCH)));
                if (found == null) {
                    complete = false;
                    break;
                }
                ledger.applyScan(collection, none, none, found);
            }
        }
        return complete;
    }

    /** Folder rows for those of {@code ids} MediaStore still has; null when MediaStore can't be read. */
    private static List<BackupLedger.ScanRow> lookUpFolders(Context context, String collection, List<Long> ids) {
        boolean video = BackupLedger.COLLECTION_VIDEO.equals(collection);
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        StringBuilder where = new StringBuilder(MediaStore.MediaColumns._ID).append(" IN (");
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            where.append(i == 0 ? "?" : ",?");
            args[i] = Long.toString(ids.get(i));
        }
        where.append(')');
        String[] columns = { MediaStore.MediaColumns._ID, folderColumn() };
        List<BackupLedger.ScanRow> found = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(base, columns, where.toString(), args, null)) {
            if (cursor == null) {
                return null;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int folderCol = cursor.getColumnIndex(folderColumn());
            while (cursor.moveToNext()) {
                String folder = folderAt(cursor, folderCol);
                if (folder != null) {
                    found.add(folderRow(cursor.getLong(idCol), folder));
                }
            }
        } catch (RuntimeException e) {
            // SecurityException, or an OEM MediaStore that rejects the query.
            Log.w(TAG, "Could not look up folders in " + collection, e);
            return null;
        }
        return found;
    }

    /** RELATIVE_PATH on Android 10+, the file path (DATA) before. */
    private static String folderColumn() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.MediaColumns.RELATIVE_PATH : legacyDataColumn();
    }

    /** The row's device folder ("DCIM/Camera"), or null when unknown. */
    private static String folderAt(Cursor cursor, int column) {
        String value;
        try {
            value = stringAt(cursor, column);
        } catch (RuntimeException e) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return BackupUtil.folderFromRelativePath(value);
        }
        return BackupUtil.folderFromDataPath(value);
    }

    private static BackupLedger.ScanRow folderRow(long mediaStoreId, String folder) {
        ContentValues values = new ContentValues();
        values.put(BackupLedger.COL_FOLDER, folder);
        return new BackupLedger.ScanRow(mediaStoreId, values);
    }

    @SuppressWarnings("deprecation")
    private static String legacyDataColumn() {
        return MediaStore.MediaColumns.DATA;
    }

    private static long longAt(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) {
            return 0L;
        }
        try {
            return cursor.getLong(column);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String stringAt(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) {
            return null;
        }
        return cursor.getString(column);
    }
}
