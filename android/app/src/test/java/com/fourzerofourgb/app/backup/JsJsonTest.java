package com.fourzerofourgb.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * JsJson must read and write exactly like the web app's JavaScript. The
 * expected strings below were produced by Node's JSON.parse / JSON.stringify
 * and lib/github/jsonArray.ts serializeJsonArray, not written by hand.
 */
public class JsJsonTest {

    // A manifest as a JavaScript writer (or a hand edit) leaves it: loose spacing,
    // escapes, integer-like and duplicate keys, a lone surrogate, non-object entries.
    private static final String MANIFEST_IN = "[ {\"id\":\"media_a\",\"filename\":\"caf\u00E9 / \u2028 \\u0001 \\\"q\\\" \\\\ \\ud800 \\uD83D\\uDE00 \\/x\",\"hash\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"mimeType\":\"image/jpeg\",\"createdAt\":\"2024-01-01T00:00:00.000Z\",\"width\":4000,\"height\":3000,\"duration\":12.345,\"size\":1e+21,\"small\":1.5e-7,\"__proto__\":1,\"2\":\"two\",\"b\":true,\"1\":null,\"x\":{\"z\":[],\"y\":{}}},\n  null, 5, \"s\", [1,{\"k\":0.30000000000000004}] ,{\"id\":\"media_b\",\"takenAt\":\"\",\"dup\":1,\"dup\":2}\n]";
    // serializeJsonArray(JSON.parse(MANIFEST_IN))
    private static final String MANIFEST_OUT = "[\n  {\"1\":null,\"2\":\"two\",\"id\":\"media_a\",\"filename\":\"caf\u00E9 / \u2028 \\u0001 \\\"q\\\" \\\\ \\ud800 \uD83D\uDE00 /x\",\"hash\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"mimeType\":\"image/jpeg\",\"createdAt\":\"2024-01-01T00:00:00.000Z\",\"width\":4000,\"height\":3000,\"duration\":12.345,\"size\":1e+21,\"small\":1.5e-7,\"__proto__\":1,\"b\":true,\"x\":{\"z\":[],\"y\":{}}},\n  null,\n  5,\n  \"s\",\n  [1,{\"k\":0.30000000000000004}],\n  {\"id\":\"media_b\",\"takenAt\":\"\",\"dup\":2}\n]\n";
    // JSON.stringify(record, null, 2) and JSON.stringify(record)
    private static final String RECORD_PRETTY = "{\n  \"id\": \"media_x\",\n  \"filename\": \"IMG_1.jpg\",\n  \"mimeType\": \"image/jpeg\",\n  \"size\": 123,\n  \"createdAt\": \"2026-10-11T00:00:00.000Z\",\n  \"takenAt\": \"2026-10-10T00:00:00.000Z\",\n  \"width\": 4000,\n  \"height\": 3000,\n  \"duration\": 1.5,\n  \"hash\": \"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\n  \"chunkSize\": 4194304,\n  \"chunks\": [\n    {\n      \"index\": 0,\n      \"hash\": \"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\n      \"path\": \"objects/bb/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"\n    }\n  ],\n  \"thumbnails\": {\n    \"320\": \"t/320.webp\",\n    \"800\": \"t/800.webp\",\n    \"1600\": \"t/1600.webp\"\n  },\n  \"emptyList\": [],\n  \"emptyMap\": {},\n  \"source\": \"android-backup\",\n  \"deletedAt\": null\n}";
    private static final String RECORD_COMPACT = "{\"id\":\"media_x\",\"filename\":\"IMG_1.jpg\",\"mimeType\":\"image/jpeg\",\"size\":123,\"createdAt\":\"2026-10-11T00:00:00.000Z\",\"takenAt\":\"2026-10-10T00:00:00.000Z\",\"width\":4000,\"height\":3000,\"duration\":1.5,\"hash\":\"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"chunkSize\":4194304,\"chunks\":[{\"index\":0,\"hash\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"path\":\"objects/bb/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}],\"thumbnails\":{\"320\":\"t/320.webp\",\"800\":\"t/800.webp\",\"1600\":\"t/1600.webp\"},\"emptyList\":[],\"emptyMap\":{},\"source\":\"android-backup\",\"deletedAt\":null}";

    @Test
    public void manifestRoundTripMatchesJavaScript() throws Exception {
        List<String> lines = new ArrayList<>();
        int count = JsJson.parseArray(new StringReader(MANIFEST_IN), element -> lines.add(JsJson.stringify(element)));
        assertEquals(6, count);
        assertEquals(MANIFEST_OUT, JsJson.arrayFile(lines));
    }

    @Test
    public void reserializingCanonicalManifestChangesNothing() throws Exception {
        List<String> lines = new ArrayList<>();
        JsJson.parseArray(new StringReader(MANIFEST_OUT), element -> lines.add(JsJson.stringify(element)));
        assertEquals(MANIFEST_OUT, JsJson.arrayFile(lines));
    }

    @Test
    public void recordMatchesJavaScript() {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("index", 0L);
        chunk.put("hash", repeat('b', 64));
        chunk.put("path", "objects/bb/" + repeat('b', 64));
        Map<String, Object> thumbnails = new LinkedHashMap<>();
        // Inserted out of order: JavaScript lists array-index keys ascending.
        thumbnails.put("1600", "t/1600.webp");
        thumbnails.put("320", "t/320.webp");
        thumbnails.put("800", "t/800.webp");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "media_x");
        record.put("filename", "IMG_1.jpg");
        record.put("mimeType", "image/jpeg");
        record.put("size", 123L);
        record.put("createdAt", "2026-10-11T00:00:00.000Z");
        record.put("takenAt", "2026-10-10T00:00:00.000Z");
        record.put("width", 4000L);
        record.put("height", 3000L);
        record.put("duration", JsJson.Num.secondsFromMillis(1500));
        record.put("notSet", null);
        record.put("hash", "sha256:" + repeat('b', 64));
        record.put("chunkSize", 4194304L);
        record.put("chunks", Collections.singletonList(chunk));
        record.put("thumbnails", thumbnails);
        record.put("emptyList", new ArrayList<Object>());
        record.put("emptyMap", new LinkedHashMap<String, Object>());
        record.put("source", "android-backup");
        record.put("deletedAt", JsJson.NULL);
        assertEquals(RECORD_PRETTY, JsJson.stringifyPretty(record));
        assertEquals(RECORD_COMPACT, JsJson.stringify(record));
    }

    @Test
    public void emptyAndBlankManifests() throws Exception {
        assertEquals("[]\n", JsJson.arrayFile(Collections.<String>emptyList()));
        for (String blank : Arrays.asList("", " \n\t\r", "\u00A0 \uFEFF\u3000", "[]", " [ ]\n")) {
            assertEquals(blank, 0, JsJson.parseArray(new StringReader(blank), element -> fail("no elements expected")));
        }
    }

    @Test
    public void rejectsWhatJsonParseRejectsOrWhatIsNotAnArray() throws IOException {
        List<String> bad = Arrays.asList(
            "{}",
            "5",
            "\"[]\"",
            "\u00A0[]",
            "\uFEFF[]",
            "[1,]",
            "[01]",
            "[1.]",
            "[-]",
            "[\"a\u0001\"]",
            "[\"\\x\"]",
            "[\"\\u12G4\"]",
            "[tru]",
            "[",
            "[1",
            "[1] x",
            "[{\"a\":1,}]",
            "[{'a':1}]",
            "[NaN]"
        );
        for (String text : bad) {
            try {
                JsJson.parseArray(new StringReader(text), element -> {});
                fail("accepted " + text);
            } catch (JsJson.SyntaxException expected) {
                // As JSON.parse (or parseJsonArray's isArray check) would.
            }
        }
    }

    @Test
    public void parseKeepsOrderAndLastDuplicate() throws Exception {
        Object value = JsJson.parse(" {\"b\":1,\"a\":[true,false,null],\"b\":2.5} ");
        assertEquals("{\"b\":2.5,\"a\":[true,false,null]}", JsJson.stringify(value));
    }

    @Test
    public void secondsFromMillisMatchesJavaScript() {
        long[] millis = { 1, 5, 50, 999, 1000, 1500, 12300, 12345, 3600000, 7261001 };
        String[] expected = { "0.001", "0.005", "0.05", "0.999", "1", "1.5", "12.3", "12.345", "3600", "7261.001" };
        for (int i = 0; i < millis.length; i++) {
            assertEquals(expected[i], JsJson.Num.secondsFromMillis(millis[i]).text);
        }
    }

    @Test
    public void trimAndTruthinessFollowJavaScript() {
        assertEquals("x y", JsJson.jsTrim(" \u00A0\uFEFF\u3000x y\u2028\t"));
        assertFalse(JsJson.truthy(null));
        assertFalse(JsJson.truthy(JsJson.NULL));
        assertFalse(JsJson.truthy(""));
        assertFalse(JsJson.truthy(Boolean.FALSE));
        assertFalse(JsJson.truthy(new JsJson.Num("0")));
        assertFalse(JsJson.truthy(new JsJson.Num("-0.0")));
        assertTrue(JsJson.truthy("2024-01-01T00:00:00.000Z"));
        assertTrue(JsJson.truthy(new JsJson.Num("1e-7")));
        assertTrue(JsJson.truthy(new LinkedHashMap<String, Object>()));
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
