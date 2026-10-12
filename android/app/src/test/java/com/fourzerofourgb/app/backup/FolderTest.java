package com.fourzerofourgb.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Device folders (MediaRecord.folder): how MediaStore paths become folders,
 * which folder the backfill gives each library id, and which index entries
 * it touches. Pure Java, like JsJsonTest.
 */
public class FolderTest {

    private static final String HASH_A = repeat('a', 64);
    private static final String HASH_B = repeat('b', 64);
    private static final String HASH_C = repeat('c', 64);
    private static final String HASH_D = repeat('d', 64);

    @Test
    public void relativePathsBecomeFolders() {
        assertEquals("DCIM/Camera", BackupUtil.folderFromRelativePath("DCIM/Camera/"));
        assertEquals("Pictures/Screenshots", BackupUtil.folderFromRelativePath("Pictures/Screenshots/"));
        assertEquals(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            BackupUtil.folderFromRelativePath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/")
        );
        assertEquals("Download", BackupUtil.folderFromRelativePath("Download/"));
        assertEquals("DCIM/Camera", BackupUtil.folderFromRelativePath("/DCIM//Camera"));
        // The volume root, or nothing at all, is no folder.
        assertNull(BackupUtil.folderFromRelativePath("/"));
        assertNull(BackupUtil.folderFromRelativePath(""));
        assertNull(BackupUtil.folderFromRelativePath(null));
        assertNull(BackupUtil.normalizeFolder(repeat('x', 1025)));
    }

    @Test
    public void filePathsBecomeFoldersRelativeToTheirVolume() {
        assertEquals("DCIM/Camera", BackupUtil.folderFromDataPath("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"));
        assertEquals("Pictures/Screenshots", BackupUtil.folderFromDataPath("/storage/emulated/10/Pictures/Screenshots/a.png"));
        assertEquals("DCIM/Camera", BackupUtil.folderFromDataPath("/storage/1234-ABCD/DCIM/Camera/IMG_1.jpg"));
        assertEquals("Download", BackupUtil.folderFromDataPath("/sdcard/Download/x.jpg"));
        assertEquals("Download", BackupUtil.folderFromDataPath("/storage/self/primary/Download/x.jpg"));
        assertEquals("WhatsApp/Media/WhatsApp Images", BackupUtil.folderFromDataPath("/mnt/sdcard/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"));
        assertNull(BackupUtil.folderFromDataPath("/storage/emulated/0/IMG_1.jpg"));
        assertNull(BackupUtil.folderFromDataPath("/data/user/0/app/IMG_1.jpg"));
        assertNull(BackupUtil.folderFromDataPath("IMG_1.jpg"));
        assertNull(BackupUtil.folderFromDataPath(null));
    }

    @Test
    public void theCopyThisPhoneUploadedDecides() {
        List<FolderBackfill.Target> targets = FolderBackfill.resolve(
            Arrays.asList(
                new BackupLedger.UploadedRow("media_a", HASH_A, "Pictures/Edits", false),
                new BackupLedger.UploadedRow("media_a", HASH_A, "DCIM/Camera", true),
                // Uploaded from a folder that is no longer known: nothing is guessed.
                new BackupLedger.UploadedRow("media_b", HASH_B, null, true),
                new BackupLedger.UploadedRow("media_b", HASH_B, "DCIM/Camera", false),
                // Two uploads under one id that disagree.
                new BackupLedger.UploadedRow("media_c", HASH_C, "DCIM/Camera", true),
                new BackupLedger.UploadedRow("media_c", HASH_C, "Download", true)
            ),
            true
        );
        assertEquals(1, targets.size());
        assertEquals("media_a", targets.get(0).mediaId);
        assertEquals("sha256:" + HASH_A, targets.get(0).fileHash);
        assertEquals("DCIM/Camera", targets.get(0).folder);
    }

    @Test
    public void duplicatesDecideOnlyWhenTheyAgree() {
        List<BackupLedger.UploadedRow> rows = Arrays.asList(
            new BackupLedger.UploadedRow("media_a", HASH_A, "Pictures/Screenshots/", false),
            new BackupLedger.UploadedRow("media_a", HASH_A, "Pictures/Screenshots", false),
            // A copy that is gone from the phone has no say.
            new BackupLedger.UploadedRow("media_a", HASH_A, null, false),
            new BackupLedger.UploadedRow("media_b", HASH_B, "DCIM/Camera", false),
            new BackupLedger.UploadedRow("media_b", HASH_B, "Download", false),
            // Rows that disagree about the bytes, or aren't valid at all.
            new BackupLedger.UploadedRow("media_c", HASH_C, "DCIM/Camera", false),
            new BackupLedger.UploadedRow("media_c", HASH_D, "DCIM/Camera", false),
            new BackupLedger.UploadedRow("not-an-id", HASH_D, "DCIM/Camera", true),
            new BackupLedger.UploadedRow("media_d", "sha256:" + HASH_D, "DCIM/Camera", true)
        );
        List<FolderBackfill.Target> targets = FolderBackfill.resolve(rows, true);
        assertEquals(1, targets.size());
        assertEquals("media_a", targets.get(0).mediaId);
        assertEquals("Pictures/Screenshots", targets.get(0).folder);
        assertTrue(FolderBackfill.resolve(rows, false).isEmpty());
    }

    @Test
    public void planTouchesOnlyMatchingEntriesWithoutAFolder() {
        LibraryIndex.Builder builder = new LibraryIndex.Builder();
        builder.add(entry("media_with", HASH_A, "DCIM/Camera", null));
        builder.add(entry("media_trashed", HASH_B, null, "2026-10-01T00:00:00.000Z"));
        builder.add(entry("media_other", HASH_C, null, null));
        builder.add(entry("media_plain", HASH_D, null, null));
        LibraryIndex index = builder.build("o/r@main", "c0ffee", null);

        // A trashed copy still counts as in the library (duplicate checks find it).
        assertEquals(1, index.indexOfHash("sha256:" + HASH_B));
        assertTrue(index.hasFolder(0));
        assertFalse(index.hasFolder(1));

        List<LibraryWriter.Op> fixes = FolderBackfill.plan(
            index,
            Arrays.asList(
                new FolderBackfill.Target("media_plain", "sha256:" + HASH_D, "Download"),
                // Already has a folder: never overwritten.
                new FolderBackfill.Target("media_with", "sha256:" + HASH_A, "Pictures"),
                new FolderBackfill.Target("media_trashed", "sha256:" + HASH_B, "Pictures/Screenshots"),
                // Those bytes are in the library under another id.
                new FolderBackfill.Target("media_mine", "sha256:" + HASH_C, "DCIM/Camera"),
                new FolderBackfill.Target("media_gone", "sha256:" + repeat('e', 64), "DCIM/Camera")
            )
        );
        assertEquals(2, fixes.size());
        // Manifest order, whatever order the targets came in.
        LibraryWriter.FolderFix first = (LibraryWriter.FolderFix) fixes.get(0);
        LibraryWriter.FolderFix second = (LibraryWriter.FolderFix) fixes.get(1);
        assertEquals("media_trashed", first.mediaId);
        assertEquals("Pictures/Screenshots", first.folder);
        assertEquals("sha256:" + HASH_B, first.fileHash);
        assertEquals("media_plain", second.mediaId);
        assertEquals("Download", second.folder);
    }

    @Test
    public void rewrittenEntriesKeepTheirLinesAndFlags() throws Exception {
        LibraryIndex.Builder builder = new LibraryIndex.Builder();
        builder.add(entry("media_a", HASH_A, null, null));
        builder.add(entry("media_b", HASH_B, null, "2026-10-01T00:00:00.000Z"));
        String untouched = builder.text().split("\n")[1];
        Map<String, List<Integer>> positions = builder.indexesOfIds(new HashSet<>(Arrays.asList("media_b", "media_x")));
        assertEquals(Arrays.asList(1), positions.get("media_b"));
        assertFalse(positions.containsKey("media_x"));

        builder.replace(1, LibraryWriter.withFolder((Map<?, ?>) builder.entry(1), "Pictures/Screenshots"));
        assertTrue(builder.hasFolder(1));
        assertFalse(builder.hasFolder(0));
        String[] lines = builder.text().split("\n");
        assertEquals(untouched, lines[1]);
        assertEquals(
            "  {\"id\":\"media_b\",\"filename\":\"media_b.jpg\",\"hash\":\"sha256:" +
            HASH_B +
            "\",\"mimeType\":\"image/jpeg\",\"createdAt\":\"2025-01-01T00:00:00.000Z\",\"folder\":\"Pictures/Screenshots\",\"trashedAt\":\"2026-10-01T00:00:00.000Z\"}",
            lines[2]
        );
        assertEquals(1, builder.indexOfHash("sha256:" + HASH_B));
    }

    private static Map<String, Object> entry(String id, String hash, String folder, String trashedAt) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("filename", id + ".jpg");
        entry.put("hash", "sha256:" + hash);
        entry.put("mimeType", "image/jpeg");
        entry.put("createdAt", "2025-01-01T00:00:00.000Z");
        if (folder != null) {
            entry.put("folder", folder);
        }
        if (trashedAt != null) {
            entry.put("trashedAt", trashedAt);
        }
        return entry;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
