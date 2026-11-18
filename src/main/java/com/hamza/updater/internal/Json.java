package com.hamza.updater.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, so that the library brings no dependency into the program. Objects come back as
 * {@link Map}, arrays as {@link List}, and the rest as {@link String}, {@link Long}, {@link Double},
 * {@link Boolean} or null.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException when the text is not JSON */
    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipBlanks();
        Object value = json.value(0);
        json.skipBlanks();
        if (json.at != text.length()) {
            throw json.broken("text after the value");
        }
        return value;
    }

    /** The value as an object; an empty one when it is something else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The value as an array; an empty one when it is something else. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    /** The text under a key; empty when it is absent or not text. */
    public static String text(Map<String, Object> object, String key) {
        return object.get(key) instanceof String string ? string : "";
    }

    /** The whole number under a key; {@code fallback} when it is absent or not a number. */
    public static long number(Map<String, Object> object, String key, long fallback) {
        return object.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    public static boolean flag(Map<String, Object> object, String key) {
        return Boolean.TRUE.equals(object.get(key));
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw broken("nested too deep");
        }
        if (at >= text.length()) {
            throw broken("ended early");
        }
        char next = text.charAt(at);
        return switch (next) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        skipBlanks();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            skipBlanks();
            if (peek() != '"') {
                throw broken("a key was expected");
            }
            String key = string();
            skipBlanks();
            expect(':');
            skipBlanks();
            object.put(key, value(depth + 1));
            skipBlanks();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> array = new ArrayList<>();
        at++;
        skipBlanks();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            skipBlanks();
            array.add(value(depth + 1));
            skipBlanks();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String string() {
        StringBuilder string = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) {
                throw broken("a string was not closed");
            }
            char next = text.charAt(at++);
            if (next == '"') {
                return string.toString();
            }
            if (next != '\\') {
                string.append(next);
                continue;
            }
            if (at >= text.length()) {
                throw broken("a string was not closed");
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> string.append(escaped);
                case 'b' -> string.append('\b');
                case 'f' -> string.append('\f');
                case 'n' -> string.append('\n');
                case 'r' -> string.append('\r');
                case 't' -> string.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw broken("a \\u escape was cut short");
                    }
                    try {
                        string.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException notHex) {
                        throw broken("a \\u escape is not hexadecimal");
                    }
                    at += 4;
                }
                default -> throw broken("an unknown escape");
            }
        }
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw broken("an unknown word");
        }
        at += word.length();
        return value;
    }

    private Object number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        try {
            if (number.matches("-?\\d{1,18}")) {
                return Long.parseLong(number);
            }
            return Double.parseDouble(number);
        } catch (NumberFormatException notNumber) {
            throw broken("a value was expected");
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw broken("ended early");
        }
        return text.charAt(at);
    }

    private void expect(char wanted) {
        if (peek() != wanted) {
            throw broken("'" + wanted + "' was expected");
        }
        at++;
    }

    private void skipBlanks() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException broken(String what) {
        return new IllegalArgumentException("Not JSON at character " + at + ": " + what);
    }
}
