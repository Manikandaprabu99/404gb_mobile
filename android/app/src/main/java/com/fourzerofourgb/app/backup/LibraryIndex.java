package com.fourzerofourgb.app.backup;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * manifest/media-index.json at one commit, as the phone needs it: every entry
 * kept as the exact line serializeJsonArray writes for it (so a commit only
 * rewrites the lines it changes), plus the hashes and ids that duplicate
 * checks look up. The newest snapshot is kept in memory and in the cache dir,
 * keyed by commit sha, together with the ETag GitHub sent for it, so reading
 * an unchanged manifest at a newer commit costs a free 304.
 */
final class LibraryIndex {

    static final String PATH = "manifest/media-index.json";

    /** A manifest that isn't a JSON array: writing over it as "empty" would wipe the library. */
    static final class CorruptIndexException extends GitHubClient.GitHubException {

        CorruptIndexException(String detail) {
            super(PATH + " in your repo isn't a valid JSON array (" + detail + "); fix or restore it on GitHub");
        }
    }

    private static final String TAG = "404gb.LibraryIndex";
    private static final String CACHE_DIR = "library-index";
    private static final String DATA_SUFFIX = ".json";
    private static final String ETAG_SUFFIX = ".etag";
    private static final Object LOCK = new Object();
    private static LibraryIndex latest;

    /** owner/repo@branch this snapshot belongs to (owner and repo lowercased). */
    final String library;
    final String commitSha;
    /** GitHub's ETag for exactly this content; null when unknown (e.g. the phone wrote it). */
    final String etag;
    private final List<String> lines;
    private final List<String> ids;
    private final BitSet dated;
    private final Map<String, Integer> firstByHash;
    private final Set<String> idSet;

    private LibraryIndex(String library, String commitSha, String etag, Builder content) {
        this.library = library;
        this.commitSha = commitSha;
        this.etag = etag;
        this.lines = content.lines;
        this.ids = content.ids;
        this.dated = content.dated;
        this.firstByHash = content.firstByHash;
        this.idSet = content.idSet;
    }

    /** The cache key for a session's library. */
    static String libraryKey(BackupPrefs.Auth auth) {
        return auth.owner.toLowerCase(Locale.US) + "/" + auth.repo.toLowerCase(Locale.US) + "@" + auth.branch;
    }

    int size() {
        return lines.size();
    }

    /** Position of the first entry with this whole-file hash ("sha256:<hex>"), or -1 (JS index.find). */
    int indexOfHash(String hash) {
        Integer at = firstByHash.get(hash);
        return at == null ? -1 : at;
    }

    /** The entry's id, or null when it has none. */
    String idAt(int index) {
        return ids.get(index);
    }

    /** Whether the entry has a capture date (a truthy takenAt). */
    boolean isDated(int index) {
        return dated.get(index);
    }

    /** The same content at another commit (GitHub answered 304 for it). */
    LibraryIndex atCommit(String newCommitSha) {
        return new LibraryIndex(library, newCommitSha, etag, Builder.sharing(this));
    }

    /** The file's text exactly as serializeJsonArray writes it. */
    String text() {
        return JsJson.arrayFile(lines);
    }

    static LibraryIndex empty(String library, String commitSha) {
        return new LibraryIndex(library, commitSha, null, new Builder());
    }

    /** Parses a manifest file (parseJsonArray rules); throws CorruptIndexException for anything but an array. */
    static LibraryIndex parse(File file, String library, String commitSha, String etag) throws IOException {
        Builder builder = new Builder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsJson.parseArray(reader, builder);
        } catch (JsJson.SyntaxException e) {
            throw new CorruptIndexException(e.getMessage());
        }
        return builder.build(library, commitSha, etag);
    }

    /**
     * The entries of one transaction attempt: starts as a copy of a snapshot,
     * takes appended and rewritten entries, then becomes the next snapshot.
     */
    static final class Builder implements JsJson.ElementHandler {

        private final List<String> lines;
        private final List<String> ids;
        private final BitSet dated;
        private final Map<String, Integer> firstByHash;
        private final Set<String> idSet;

        Builder() {
            this(new ArrayList<String>(), new ArrayList<String>(), new BitSet(), new HashMap<String, Integer>(), new HashSet<String>());
        }

        /** A modifiable copy of {@code base}. */
        Builder(LibraryIndex base) {
            this(new ArrayList<>(base.lines), new ArrayList<>(base.ids), (BitSet) base.dated.clone(), new HashMap<>(base.firstByHash), new HashSet<>(base.idSet));
        }

        private Builder(List<String> lines, List<String> ids, BitSet dated, Map<String, Integer> firstByHash, Set<String> idSet) {
            this.lines = lines;
            this.ids = ids;
            this.dated = dated;
            this.firstByHash = firstByHash;
            this.idSet = idSet;
        }

        /** Read-only reuse of a snapshot's content (it is never modified). */
        static Builder sharing(LibraryIndex index) {
            return new Builder(index.lines, index.ids, index.dated, index.firstByHash, index.idSet);
        }

        @Override
        public void onElement(Object element) {
            add(element);
        }

        /** Appends an entry; returns its position. */
        int add(Object entry) {
            int index = lines.size();
            lines.add(JsJson.stringify(entry));
            ids.add(null);
            describe(index, entry);
            return index;
        }

        /** Rewrites an entry in place (its hash must stay the same). */
        void replace(int index, Object entry) {
            String oldId = ids.get(index);
            lines.set(index, JsJson.stringify(entry));
            describe(index, entry);
            if (oldId != null && !oldId.equals(ids.get(index)) && !ids.contains(oldId)) {
                idSet.remove(oldId);
            }
        }

        private void describe(int index, Object entry) {
            String id = null;
            boolean hasDate = false;
            if (entry instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) entry;
                Object idValue = map.get("id");
                if (idValue instanceof String) {
                    id = (String) idValue;
                }
                Object hash = map.get("hash");
                if (hash instanceof String && !firstByHash.containsKey(hash)) {
                    firstByHash.put((String) hash, index);
                }
                hasDate = JsJson.truthy(map.get("takenAt"));
            }
            ids.set(index, id);
            if (id != null) {
                idSet.add(id);
            }
            dated.set(index, hasDate);
        }

        /** The parsed entry at {@code index}. */
        Object entry(int index) {
            try {
                return JsJson.parse(lines.get(index));
            } catch (JsJson.SyntaxException e) {
                // Lines are JSON.stringify output, so this can't happen.
                throw new IllegalStateException("Unreadable index line " + index, e);
            }
        }

        int indexOfHash(String hash) {
            Integer at = firstByHash.get(hash);
            return at == null ? -1 : at;
        }

        String idAt(int index) {
            return ids.get(index);
        }

        boolean isDated(int index) {
            return dated.get(index);
        }

        boolean hasId(String id) {
            return idSet.contains(id);
        }

        /** Every position holding this id, in order (normally one). */
        List<Integer> indexesOfId(String id) {
            if (!idSet.contains(id)) {
                return Collections.emptyList();
            }
            List<Integer> found = new ArrayList<>(1);
            for (int i = 0; i < ids.size(); i++) {
                if (id.equals(ids.get(i))) {
                    found.add(i);
                }
            }
            return found;
        }

        String text() {
            return JsJson.arrayFile(lines);
        }

        LibraryIndex build(String library, String commitSha, String etag) {
            return new LibraryIndex(library, commitSha, etag, this);
        }
    }

    // ---- cache ----

    /** The snapshot of {@code library} at {@code commitSha} from memory or disk, or null. */
    static LibraryIndex cached(Context context, String library, String commitSha) {
        synchronized (LOCK) {
            if (latest != null && latest.library.equals(library) && latest.commitSha.equals(commitSha)) {
                return latest;
            }
        }
        File data = new File(dir(context, library), commitSha + DATA_SUFFIX);
        if (!data.isFile()) {
            return null;
        }
        return loadFromDisk(context, library, commitSha);
    }

    /** The newest snapshot of {@code library} at any commit (to revalidate with its ETag), or null. */
    static LibraryIndex newest(Context context, String library) {
        synchronized (LOCK) {
            if (latest != null && latest.library.equals(library)) {
                return latest;
            }
        }
        File[] files = dir(context, library).listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File file : files) {
            if (file.getName().endsWith(DATA_SUFFIX) && (newest == null || file.lastModified() > newest.lastModified())) {
                newest = file;
            }
        }
        if (newest == null) {
            return null;
        }
        String name = newest.getName();
        return loadFromDisk(context, library, name.substring(0, name.length() - DATA_SUFFIX.length()));
    }

    private static LibraryIndex loadFromDisk(Context context, String library, String commitSha) {
        File dir = dir(context, library);
        File data = new File(dir, commitSha + DATA_SUFFIX);
        try {
            LibraryIndex index = parse(data, library, commitSha, readEtag(new File(dir, commitSha + ETAG_SUFFIX)));
            synchronized (LOCK) {
                latest = index;
            }
            return index;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Dropping an unreadable cached index", e);
            deleteQuietly(data);
            return null;
        }
    }

    /** A fresh file in the cache dir to download a manifest into. */
    static File downloadFile(Context context, String library) throws IOException {
        File dir = dir(context, library);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        return File.createTempFile("download", ".tmp", dir);
    }

    /**
     * Makes {@code index} the newest snapshot, in memory and on disk. With
     * {@code contentFile} (a download of exactly this content) the file is moved
     * into place; otherwise the text is written out. The disk copy is best effort.
     */
    static void remember(Context context, LibraryIndex index, File contentFile) {
        synchronized (LOCK) {
            latest = index;
        }
        File dir = dir(context, index.library);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Could not create " + dir);
            }
            File data = new File(dir, index.commitSha + DATA_SUFFIX);
            File etagFile = new File(dir, index.commitSha + ETAG_SUFFIX);
            if (contentFile == null || !contentFile.renameTo(data)) {
                File temp = File.createTempFile("index", ".tmp", dir);
                try (Writer out = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                    out.write(index.text());
                }
                if (!temp.renameTo(data)) {
                    deleteQuietly(temp);
                    throw new IOException("Could not write " + data);
                }
            }
            if (index.etag != null) {
                try (OutputStream out = new FileOutputStream(etagFile)) {
                    out.write(index.etag.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                deleteQuietly(etagFile);
            }
            // Only the newest snapshot (of only this library) is worth keeping.
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (!name.equals(data.getName()) && !name.equals(etagFile.getName())) {
                        deleteQuietly(file);
                    }
                }
            }
            File[] libraries = dir.getParentFile() == null ? null : dir.getParentFile().listFiles();
            if (libraries != null) {
                for (File other : libraries) {
                    if (!other.equals(dir)) {
                        deleteRecursively(other);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not cache the index on disk", e);
        } finally {
            if (contentFile != null && contentFile.exists()) {
                deleteQuietly(contentFile);
            }
        }
    }

    /** Forgets every cached manifest (sign-out). */
    static void clear(Context context) {
        synchronized (LOCK) {
            latest = null;
        }
        deleteRecursively(new File(context.getCacheDir(), CACHE_DIR));
    }

    private static File dir(Context context, String library) {
        String key = BackupUtil.hex(BackupUtil.sha256().digest(library.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        return new File(new File(context.getCacheDir(), CACHE_DIR), key);
    }

    private static String readEtag(File file) {
        if (!file.isFile() || file.length() > 1024) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    return null;
                }
                read += n;
            }
            String etag = new String(bytes, StandardCharsets.UTF_8).trim();
            return etag.isEmpty() ? null : etag;
        } catch (IOException e) {
            return null;
        }
    }

    static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        deleteQuietly(file);
    }
}
