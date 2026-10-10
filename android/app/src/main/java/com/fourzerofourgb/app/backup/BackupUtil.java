package com.fourzerofourgb.app.backup;

import java.io.Closeable;
import java.io.IOException;
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
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class BackupUtil {

    /** Every transfer is one chunk so it fits under Vercel's 4.5 MB body cap. */
    static final int CHUNK_SIZE = 4 * 1024 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

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
