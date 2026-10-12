package com.fourzerofourgb.app.backup;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class BackupUtil {

    /** The repo format's chunk size: objects/ files are at most 4 MiB. */
    static final int CHUNK_SIZE = 4 * 1024 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern MEDIA_ID = Pattern.compile("^media_[A-Za-z0-9_-]{1,80}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    // Where storage volumes were mounted before Android 10: the primary one
    // (/storage/emulated/0, /sdcard…), SD cards and USB drives (/storage/1234-ABCD).
    private static final Pattern VOLUME_ROOT = Pattern.compile(
        "^/(?:storage/emulated/\\d+|storage/self/primary|storage/[^/]+|mnt/media_rw/[^/]+|mnt/[^/]+|sdcard\\d*)/"
    );
    /** A longer folder is not a real one; it is left out like an unknown folder. */
    private static final int MAX_FOLDER_LENGTH = 1024;

    private BackupUtil() {}

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    static String sha256Hex(byte[] data, int offset, int length) {
        MessageDigest md = sha256();
        md.update(data, offset, length);
        return hex(md.digest());
    }

    static boolean isSha256Hex(String value) {
        return value != null && HEX_SHA256.matcher(value).matches();
    }

    /** lib/media/types.ts isMediaId. */
    static boolean isMediaId(String value) {
        return value != null && MEDIA_ID.matcher(value).matches();
    }

    /** The sha git (and so GitHub's blob API) gives this content: SHA-1 of "blob <size>\0" + bytes. */
    static String gitBlobSha(byte[] data) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
        sha1.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha1.update(data);
        return hex(sha1.digest());
    }

    /** String value of {@code key}, or null when the object is null or the value is absent, JSON null or empty. */
    static String optString(JSONObject json, String key) {
        if (json == null || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    /** Repo path of a content-addressed chunk, as the web app stores it. */
    static String objectPath(String chunkHash) {
        return "objects/" + chunkHash.substring(0, 2) + "/" + chunkHash;
    }

    /** Same shape as the web app's ids: "media_" + base36 time + 8 random base36 chars. */
    static String newMediaId() {
        StringBuilder sb = new StringBuilder("media_").append(Long.toString(System.currentTimeMillis(), 36));
        for (int i = 0; i < 8; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(36), 36));
        }
        return sb.toString();
    }

    /**
     * A device folder as MediaRecord.folder stores it: relative to its storage
     * volume, "/"-separated, without empty parts or a leading or trailing slash
     * ("DCIM/Camera"). Null when there is none (the volume root) or it is unknown.
     */
    static String normalizeFolder(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(path.length());
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.length() == 0 || out.length() > MAX_FOLDER_LENGTH ? null : out.toString();
    }

    /** MediaStore RELATIVE_PATH (Android 10+), e.g. "DCIM/Camera/", as a folder ("DCIM/Camera"). */
    static String folderFromRelativePath(String relativePath) {
        return normalizeFolder(relativePath);
    }

    /**
     * The folder of a MediaStore DATA file path (before Android 10), relative to
     * its storage volume: "/storage/emulated/0/DCIM/Camera/IMG_1.jpg" gives
     * "DCIM/Camera". Null for a path outside the known volume roots.
     */
    static String folderFromDataPath(String data) {
        if (data == null) {
            return null;
        }
        int slash = data.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String dir = data.substring(0, slash + 1);
        Matcher root = VOLUME_ROOT.matcher(dir);
        return root.find() ? normalizeFolder(dir.substring(root.end())) : null;
    }

    /** ISO 8601 in UTC with milliseconds, matching JavaScript's Date.toISOString(). */
    static String isoUtc(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }

    static Map<String, String> parseStringMap(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = obj.optString(key, "");
                if (!value.isEmpty()) {
                    out.put(key, value);
                }
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    static String toJson(Map<String, String> map) {
        return new JSONObject(map).toString();
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // Nothing useful to do with a failed close of a read-only stream.
        }
    }
}
