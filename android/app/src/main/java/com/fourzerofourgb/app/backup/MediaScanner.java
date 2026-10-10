package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles MediaStore with the ledger: every matching photo/video gets a
 * row, rows whose file changed before upload are reset for re-hashing, and
 * queued rows whose item disappeared (or no longer matches the folder
 * setting) are dropped. Uploaded rows are never touched, so the ledger also
 * serves as the identity record that prevents duplicates.
 */
final class MediaScanner {

    private static final String TAG = "404gb.MediaScanner";

    // Column names that exist on every supported API level for these tables;
    // the SDK constants for them moved to MediaColumns in API 29.
    private static final String COL_DATE_TAKEN = "datetaken";
    private static final String COL_ORIENTATION = "orientation";
    private static final String COL_DURATION = "duration";

    private static final int BATCH = 500;
    private static final long LEGACY_SETTLE_SECONDS = 10;
    private static final Object LOCK = new Object();

    private MediaScanner() {}

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

                BackupLedger.Snapshot snapshot = known.get(id);
                if (snapshot != null && (snapshot.isUploaded() || BackupLedger.STATE_UPLOADING.equals(snapshot.state))) {
                    continue;
                }
                boolean changed = snapshot != null && (snapshot.size != size || snapshot.dateModified != dateModified);
                if (snapshot != null && !changed) {
                    continue;
                }

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

                if (inserts.size() + resets.size() >= BATCH) {
                    added += ledger.applyScan(collection, inserts, resets);
                    inserts.clear();
                    resets.clear();
                }
            }
            added += ledger.applyScan(collection, inserts, resets);
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
