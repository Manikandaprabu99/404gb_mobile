package com.fourzerofourgb.app.backup;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON exactly the way the web app's JavaScript reads and writes it, so the
 * phone's commits change only the manifest lines they mean to change: a strict
 * JSON.parse (key order kept, numbers kept as written), JSON.stringify (compact
 * or 2-space) with JavaScript's escaping and key order, and the one-entry-per-
 * line array layout of lib/github/jsonArray.ts. Pure Java: no Android classes,
 * so it runs in plain JVM unit tests.
 *
 * Values are {@link Map} (LinkedHashMap), {@link List}, {@link String},
 * {@link Num}, {@link Boolean} and {@link #NULL}; {@link Long} and
 * {@link Integer} are accepted when writing. A Java null map value means
 * "undefined" and is skipped, as JSON.stringify skips undefined properties.
 */
final class JsJson {

    /** JSON null. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /**
     * A number kept as its JSON text. JSON.stringify writes the shortest text
     * that reads back as the same double, which is what a JavaScript writer put
     * in the file; writing the text back unchanged avoids reformatting it.
     */
    static final class Num {

        final String text;

        Num(String text) {
            this.text = text;
        }

        static Num of(long value) {
            return new Num(Long.toString(value));
        }

        /**
         * {@code millis / 1000} as JavaScript prints that double: any decimal
         * with fewer than 16 significant digits is the shortest text for the
         * double nearest to it, so the exact decimal is what JSON.stringify writes.
         */
        static Num secondsFromMillis(long millis) {
            if (millis < 0) {
                throw new IllegalArgumentException("negative duration");
            }
            long whole = millis / 1000;
            int fraction = (int) (millis % 1000);
            if (fraction == 0) {
                return new Num(Long.toString(whole));
            }
            String digits = Integer.toString(1000 + fraction).substring(1);
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') {
                end--;
            }
            return new Num(whole + "." + digits.substring(0, end));
        }

        double doubleValue() {
            return Double.parseDouble(text);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Num && ((Num) other).text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Input JSON.parse would reject (or, for a manifest, that isn't an array). */
    static final class SyntaxException extends Exception {

        SyntaxException(String message) {
            super(message);
        }
    }

    /** Receives the elements of a top-level array one at a time. */
    interface ElementHandler {
        void onElement(Object element);
    }

    private static final long MAX_ARRAY_INDEX = 4294967294L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsJson() {}

    // ---- reading ----

    /** JSON.parse: one value, nothing but JSON whitespace around it. */
    static Object parse(String text) throws SyntaxException {
        Parser parser = new Parser(new StringReader(text));
        try {
            Object value = parser.value();
            parser.skipWhitespace();
            if (parser.peek() >= 0) {
                throw parser.error("Unexpected data after JSON");
            }
            return value;
        } catch (IOException e) {
            // A StringReader only fails once closed.
            throw new SyntaxException("Unreadable JSON: " + e.getMessage());
        }
    }

    /**
     * Reads a manifest the way lib/github/jsonArray.ts parseJsonArray does,
     * handing each element over as soon as it is parsed so a large file is never
     * held as one tree: blank text (by JavaScript's trim) is an empty array,
     * anything that isn't a JSON array throws.
     *
     * @return the number of elements
     */
    static int parseArray(Reader reader, ElementHandler handler) throws IOException, SyntaxException {
        Parser parser = new Parser(reader);
        boolean onlyJsonWhitespace = true;
        int c;
        while (true) {
            c = parser.peek();
            if (c < 0) {
                return 0;
            }
            if (isJsonWhitespace(c)) {
                parser.pos++;
            } else if (isJsWhitespace(c)) {
                // Blank for trim(), but JSON.parse rejects it in front of a value.
                onlyJsonWhitespace = false;
                parser.pos++;
            } else {
                break;
            }
        }
        if (!onlyJsonWhitespace || c != '[') {
            throw parser.error("Not a JSON array");
        }
        parser.enter();
        parser.pos++;
        int count = 0;
        parser.skipWhitespace();
        if (parser.peek() == ']') {
            parser.pos++;
        } else {
            while (true) {
                handler.onElement(parser.value());
                count++;
                parser.skipWhitespace();
                int next = parser.read();
                if (next == ']') {
                    break;
                }
                if (next != ',') {
                    throw parser.error("Expected ',' or ']'");
                }
            }
        }
        parser.depth--;
        parser.skipWhitespace();
        if (parser.peek() >= 0) {
            throw parser.error("Unexpected data after JSON");
        }
        return count;
    }

    /** JavaScript truthiness of a parsed value (e.g. {@code if (entry.takenAt)}). */
    static boolean truthy(Object value) {
        if (value == null || value == NULL) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof Num) {
            double d = ((Num) value).doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (value instanceof Long || value instanceof Integer) {
            return ((Number) value).longValue() != 0;
        }
        return true;
    }

    /** String.prototype.trim: strips JavaScript whitespace and line terminators. */
    static String jsTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJsWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isJsonWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** JavaScript WhiteSpace and LineTerminator code points (what trim() removes). */
    static boolean isJsWhitespace(int c) {
        switch (c) {
            case 0x09:
            case 0x0A:
            case 0x0B:
            case 0x0C:
            case 0x0D:
            case 0x20:
            case 0xA0:
            case 0x2028:
            case 0x2029:
            case 0xFEFF:
                return true;
            default:
                return c > 0x7F && Character.getType(c) == Character.SPACE_SEPARATOR;
        }
    }

    // ---- writing ----

    /** JSON.stringify(value). */
    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, null, "");
        return out.toString();
    }

    /** JSON.stringify(value, null, 2) (no trailing newline, like the web app's metadata files). */
    static String stringifyPretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, "  ", "");
        return out.toString();
    }

    /**
     * lib/github/jsonArray.ts serializeJsonArray over items that are already
     * JSON.stringify'd: "[]\n" when empty, else one "  item" per line.
     */
    static String arrayFile(List<String> items) {
        if (items.isEmpty()) {
            return "[]\n";
        }
        int length = 4;
        for (String item : items) {
            length += item.length() + 4;
        }
        StringBuilder out = new StringBuilder(length);
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            out.append("  ").append(items.get(i));
        }
        out.append("\n]\n");
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, String gap, String indent) {
        if (value == null || value == NULL) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Num) {
            out.append(((Num) value).text);
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, gap, indent);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value, gap, indent);
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, String gap, String indent) {
        String inner = gap == null ? indent : indent + gap;
        boolean any = false;
        for (String key : keyOrder(map)) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            out.append(any ? ',' : '{');
            any = true;
            if (gap != null) {
                out.append('\n').append(inner);
            }
            quote(out, key);
            out.append(gap != null ? ": " : ":");
            write(out, value, gap, inner);
        }
        if (!any) {
            out.append("{}");
            return;
        }
        if (gap != null) {
            out.append('\n').append(indent);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, String gap, String indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        String inner = gap == null ? indent : indent + gap;
        for (int i = 0; i < list.size(); i++) {
            out.append(i == 0 ? '[' : ',');
            if (gap != null) {
                out.append('\n').append(inner);
            }
            write(out, list.get(i), gap, inner);
        }
        if (gap != null) {
            out.append('\n').append(indent);
        }
        out.append(']');
    }

    /** JavaScript's own-key order: array-index keys ascending, then the rest as inserted. */
    private static List<String> keyOrder(Map<?, ?> map) {
        List<String> indexKeys = null;
        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            String name = (String) key;
            if (arrayIndex(name) >= 0) {
                if (indexKeys == null) {
                    indexKeys = new ArrayList<>();
                }
                indexKeys.add(name);
            } else {
                keys.add(name);
            }
        }
        if (indexKeys == null) {
            return keys;
        }
        Collections.sort(indexKeys, (a, b) -> Long.compare(arrayIndex(a), arrayIndex(b)));
        indexKeys.addAll(keys);
        return indexKeys;
    }

    /** The array index a property key stands for ("0", "320"…), or -1. */
    private static long arrayIndex(String key) {
        int length = key.length();
        if (length == 0 || length > 10 || (length > 1 && key.charAt(0) == '0')) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value <= MAX_ARRAY_INDEX ? value : -1;
    }

    /** JSON.stringify's string quoting (lone surrogates escaped, as since ES2019). */
    static void quote(StringBuilder out, String value) {
        out.append('"');
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        unicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                            out.append(c).append(value.charAt(i + 1));
                            i++;
                        } else {
                            unicodeEscape(out, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        unicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    private static void unicodeEscape(StringBuilder out, char c) {
        out.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF]).append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
    }

    /** A strict RFC 8259 reader over a Reader, buffered so big manifests parse quickly. */
    private static final class Parser {

        private static final int MAX_DEPTH = 512;

        private final Reader reader;
        private final char[] buffer = new char[16 * 1024];
        int pos;
        private int limit;
        private long consumed;
        int depth;

        Parser(Reader reader) {
            this.reader = reader;
        }

        /** The next char without consuming it, or -1 at the end. */
        int peek() throws IOException {
            if (pos == limit && !fill()) {
                return -1;
            }
            return buffer[pos];
        }

        int read() throws IOException {
            if (pos == limit && !fill()) {
                return -1;
            }
            return buffer[pos++];
        }

        private boolean fill() throws IOException {
            consumed += limit;
            pos = 0;
            limit = 0;
            int n;
            do {
                n = reader.read(buffer, 0, buffer.length);
            } while (n == 0);
            if (n < 0) {
                return false;
            }
            limit = n;
            return true;
        }

        void skipWhitespace() throws IOException {
            while (true) {
                int c = peek();
                if (!isJsonWhitespace(c)) {
                    return;
                }
                pos++;
            }
        }

        void enter() throws SyntaxException {
            if (++depth > MAX_DEPTH) {
                throw error("JSON is nested too deeply");
            }
        }

        SyntaxException error(String message) {
            return new SyntaxException(message + " at position " + (consumed + pos));
        }

        Object value() throws IOException, SyntaxException {
            skipWhitespace();
            int c = peek();
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                pos++;
                return string();
            }
            if (c == 't') {
                word("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                word("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                word("null");
                return NULL;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return number();
            }
            throw error(c < 0 ? "Unexpected end of JSON" : "Unexpected character");
        }

        private Map<String, Object> object() throws IOException, SyntaxException {
            enter();
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                depth--;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (read() != '"') {
                    throw error("Expected a property name");
                }
                String key = string();
                skipWhitespace();
                if (read() != ':') {
                    throw error("Expected ':'");
                }
                // Like JSON.parse, a repeated key keeps its first position and its last value.
                map.put(key, value());
                skipWhitespace();
                int next = read();
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
            depth--;
            return map;
        }

        private List<Object> array() throws IOException, SyntaxException {
            enter();
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                depth--;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                int next = read();
                if (next == ']') {
                    break;
                }
                if (next != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
            depth--;
            return list;
        }

        /** The rest of a string whose opening quote was consumed. */
        private String string() throws IOException, SyntaxException {
            StringBuilder out = null;
            while (true) {
                if (pos == limit && !fill()) {
                    throw error("Unterminated string");
                }
                int start = pos;
                while (pos < limit) {
                    char c = buffer[pos];
                    if (c == '"' || c == '\\' || c < 0x20) {
                        break;
                    }
                    pos++;
                }
                if (pos == limit) {
                    if (out == null) {
                        out = new StringBuilder();
                    }
                    out.append(buffer, start, pos - start);
                    continue;
                }
                char c = buffer[pos];
                if (c == '"') {
                    String text;
                    if (out == null) {
                        text = new String(buffer, start, pos - start);
                    } else {
                        text = out.append(buffer, start, pos - start).toString();
                    }
                    pos++;
                    return text;
                }
                if (c < 0x20) {
                    throw error("Bad control character in string");
                }
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(buffer, start, pos - start);
                pos++;
                escape(out);
            }
        }

        private void escape(StringBuilder out) throws IOException, SyntaxException {
            int c = read();
            switch (c) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = hexValue(read());
                        if (digit < 0) {
                            throw error("Bad Unicode escape");
                        }
                        code = (code << 4) | digit;
                    }
                    out.append((char) code);
                    break;
                default:
                    throw error("Bad escaped character");
            }
        }

        private Num number() throws IOException, SyntaxException {
            StringBuilder text = new StringBuilder(16);
            if (peek() == '-') {
                text.append('-');
                pos++;
            }
            int c = peek();
            if (c == '0') {
                text.append('0');
                pos++;
            } else if (c >= '1' && c <= '9') {
                digits(text);
            } else {
                throw error("No number after minus sign");
            }
            if (peek() == '.') {
                text.append('.');
                pos++;
                if (!isDigit(peek())) {
                    throw error("Unterminated fractional number");
                }
                digits(text);
            }
            c = peek();
            if (c == 'e' || c == 'E') {
                text.append((char) c);
                pos++;
                c = peek();
                if (c == '+' || c == '-') {
                    text.append((char) c);
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("Exponent part is missing a number");
                }
                digits(text);
            }
            return new Num(text.toString());
        }

        private void digits(StringBuilder text) throws IOException {
            while (isDigit(peek())) {
                text.append(buffer[pos]);
                pos++;
            }
        }

        private void word(String expected) throws IOException, SyntaxException {
            for (int i = 0; i < expected.length(); i++) {
                if (read() != expected.charAt(i)) {
                    throw error("Unexpected token");
                }
            }
        }

        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        private static int hexValue(int c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}
