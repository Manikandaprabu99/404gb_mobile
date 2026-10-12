package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives library entries this phone backed up before uploads carried their
 * device folder (MediaRecord.folder) that folder, so the web app's automatic
 * albums (Camera, Screenshots, WhatsApp…) include them too. Runs once per
 * library, from the upload worker when nothing is left to upload:
 *
 *  1. Uploaded ledger rows that don't know their folder look it up in
 *     MediaStore; rows whose item is gone are skipped.
 *  2. Each library id gets at most one folder: the one this phone uploaded it
 *     from (state done), or else, for a file the library had already
 *     (duplicate), the folder all of this phone's copies agree on. Anything
 *     unknown or ambiguous is skipped.
 *  3. Entries at the branch head that hold those bytes (same hash) and have no
 *     folder get it, at most {@link #MAX_FIXES_PER_COMMIT} per commit, through
 *     LibraryWriter's transaction, which checks every entry again in each
 *     attempt and never overwrites a folder.
 *
 * Only manifest/media-index.json changes: metadata/&lt;id&gt;.json records keep
 * no folder, since rewriting a record means reading it first (a request per
 * item). The web app edits entries with { ...entry, … }, so the folder
 * survives later favorites, dates and thumbnails. The index itself is the
 * progress record: a stopped or rate-limited run just starts over later and
 * finds less to do, and the run that gets through everything (with every
 * MediaStore lookup answered) marks the library done in BackupPrefs.
 */
final class FolderBackfill {

    /** Asked before each commit: true when new items wait to be uploaded, which go first. */
    interface Yield {
        boolean shouldYield();
    }

    /** One library id and the folder this phone knows for it. */
    static final class Target {

        final String mediaId;
        /** "sha256:&lt;hex&gt;", as the index stores it. */
        final String fileHash;
        final String folder;

        Target(String mediaId, String fileHash, String folder) {
            this.mediaId = mediaId;
            this.fileHash = fileHash;
            this.folder = folder;
        }
    }

    /** Entries changed per commit, so a commit (and its retry after a conflict) stays a modest size. */
    // Every commit re-uploads the whole manifest, so fewer, bigger commits save phone data.
    static final int MAX_FIXES_PER_COMMIT = 2000;
    /**
     * Whether a file the library had before this phone uploaded it (a web
     * upload, another phone's) lends that entry its folder here. Safe in the
     * sense that only entries without a folder and with the same bytes change,
     * and only when every copy on this phone is in the same folder; but the
     * first device to fill it in decides.
     */
    static final boolean FROM_DUPLICATES = true;

    private static final String TAG = "404gb.FolderBackfill";
    private static final String FILE_HASH_PREFIX = "sha256:";

    private final Context context;
    private final BackupLedger ledger;
    private final BackupPrefs prefs;
    private final LibraryWriter library;
    private final String libraryKey;
    private final GitHubClient.StopSignal stop;

    /** {@code libraryKey}: LibraryIndex.libraryKey of the session {@code library} writes to. */
    FolderBackfill(Context context, BackupLedger ledger, LibraryWriter library, String libraryKey, GitHubClient.StopSignal stop) {
        this.context = context.getApplicationContext();
        this.ledger = ledger;
        this.prefs = BackupPrefs.get(this.context);
        this.library = library;
        this.libraryKey = libraryKey;
        this.stop = stop;
    }

    boolean isNeeded() {
        return !prefs.folderBackfillDone(libraryKey);
    }

    /**
     * One pass over everything left to do. Sign-in, rate-limit, conflict,
     * network and stop problems are thrown, as for uploads, and the next run
     * starts over from the branch head.
     *
     * @return true when it stepped aside for newly queued items (call it again
     *     once they are uploaded); false when it finished, or gave up for this run
     */
    boolean run(Yield yield) throws IOException {
        if (yield.shouldYield()) {
            return true;
        }
        // Without every folder looked up, this run does what it can and the next one tries again.
        boolean foldersKnown = MediaScanner.fillMissingFolders(context);
        List<Target> targets = resolve(ledger.uploadedRows(), FROM_DUPLICATES);
        // A first pick against the head; each commit checks its entries again.
        List<LibraryWriter.Op> fixes = targets.isEmpty() ? Collections.<LibraryWriter.Op>emptyList() : plan(library.headIndex(), targets);
        int set = 0;
        try {
            for (int start = 0; start < fixes.size(); start += MAX_FIXES_PER_COMMIT) {
                checkStopped();
                if (yield.shouldYield()) {
                    Log.i(TAG, "Pausing for new uploads after setting " + set + " folders");
                    return true;
                }
                List<LibraryWriter.Op> batch = new ArrayList<>(fixes.subList(start, Math.min(fixes.size(), start + MAX_FIXES_PER_COMMIT)));
                for (LibraryWriter.Outcome outcome : library.commit(batch).outcomes) {
                    if (outcome.kind == LibraryWriter.Kind.FOLDER_SET) {
                        set++;
                    }
                }
            }
        } catch (GitHubClient.TreeRejectedException e) {
            // Nothing but the index is in these trees, so this isn't a lost blob; try again next run.
            Log.w(TAG, "GitHub rejected a folder commit; trying again next run", e);
            return false;
        } catch (GitHubClient.HttpStatusException e) {
            if (e.isServerError()) {
                throw e;
            }
            Log.w(TAG, "Folder commit refused; trying again next run", e);
            return false;
        }
        Log.i(TAG, "Folder backfill set " + set + " of " + fixes.size() + " entries (" + targets.size() + " known folders)");
        if (foldersKnown) {
            prefs.setFolderBackfillDone(libraryKey);
        }
        return false;
    }

    /**
     * The folder each library id gets from this phone's uploaded rows: a row
     * this phone uploaded (done) decides alone, and none is set when such a row
     * doesn't know its folder or two of them disagree; without one, duplicates
     * decide when {@code fromDuplicates} and all of them that know a folder
     * agree. Ids whose rows disagree about the bytes are skipped.
     */
    static List<Target> resolve(List<BackupLedger.UploadedRow> rows, boolean fromDuplicates) {
        Map<String, Sources> byId = new LinkedHashMap<>();
        for (BackupLedger.UploadedRow row : rows) {
            if (!BackupUtil.isMediaId(row.mediaId) || !BackupUtil.isSha256Hex(row.fileHash)) {
                continue;
            }
            Sources sources = byId.get(row.mediaId);
            if (sources == null) {
                sources = new Sources(row.fileHash);
                byId.put(row.mediaId, sources);
            }
            sources.add(row);
        }
        List<Target> targets = new ArrayList<>();
        for (Map.Entry<String, Sources> entry : byId.entrySet()) {
            String folder = entry.getValue().folder(fromDuplicates);
            if (folder != null) {
                targets.add(new Target(entry.getKey(), FILE_HASH_PREFIX + entry.getValue().fileHash, folder));
            }
        }
        return targets;
    }

    /**
     * Folder fixes for the targets whose entry in {@code index} has these
     * bytes, this id and no folder, in manifest order.
     */
    static List<LibraryWriter.Op> plan(LibraryIndex index, List<Target> targets) {
        final Map<LibraryWriter.Op, Integer> positions = new LinkedHashMap<>();
        for (Target target : targets) {
            int position = index.indexOfHash(target.fileHash);
            if (position < 0 || !target.mediaId.equals(index.idAt(position)) || index.hasFolder(position)) {
                continue;
            }
            positions.put(new LibraryWriter.FolderFix(target, target.fileHash, target.mediaId, target.folder), position);
        }
        List<LibraryWriter.Op> fixes = new ArrayList<>(positions.keySet());
        Collections.sort(fixes, (a, b) -> Integer.compare(positions.get(a), positions.get(b)));
        return fixes;
    }

    private void checkStopped() throws GitHubClient.StoppedException {
        if (stop.isStopped()) {
            throw new GitHubClient.StoppedException();
        }
    }

    /** What this phone's rows say about one library id. */
    private static final class Sources {

        final String fileHash;
        boolean mixedHashes;
        int doneRows;
        String doneFolder;
        boolean doneUnclear;
        String duplicateFolder;
        boolean duplicateUnclear;

        Sources(String fileHash) {
            this.fileHash = fileHash;
        }

        void add(BackupLedger.UploadedRow row) {
            if (!fileHash.equals(row.fileHash)) {
                mixedHashes = true;
            }
            String folder = BackupUtil.normalizeFolder(row.folder);
            if (row.done) {
                doneRows++;
                if (folder == null || (doneFolder != null && !doneFolder.equals(folder))) {
                    doneUnclear = true;
                } else {
                    doneFolder = folder;
                }
            } else if (folder != null) {
                // A duplicate whose file is gone from the phone (no folder) has no say.
                if (duplicateFolder == null) {
                    duplicateFolder = folder;
                } else if (!duplicateFolder.equals(folder)) {
                    duplicateUnclear = true;
                }
            }
        }

        /** Null when unknown or ambiguous. */
        String folder(boolean fromDuplicates) {
            if (mixedHashes) {
                return null;
            }
            if (doneRows > 0) {
                return doneUnclear ? null : doneFolder;
            }
            return fromDuplicates && !duplicateUnclear ? duplicateFolder : null;
        }
    }
}
