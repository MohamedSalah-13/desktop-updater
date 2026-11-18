package com.hamza.updater.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void readsObjectsArraysAndValues() {
        Map<String, Object> object = Json.object(Json.parse("""
                { "name": "v2.4.0", "size": 34853376, "ratio": 1.5, "draft": false, "pre": true,
                  "nothing": null, "assets": [ {"name": "a.exe"}, {"name": "b.msi"} ], "empty": {}, "none": [] }
                """));
        assertEquals("v2.4.0", Json.text(object, "name"));
        assertEquals(34853376L, Json.number(object, "size", -1));
        assertEquals(1.5, object.get("ratio"));
        assertTrue(Json.flag(object, "pre"));
        assertEquals(false, Json.flag(object, "draft"));
        assertNull(object.get("nothing"));
        List<Object> assets = Json.array(object.get("assets"));
        assertEquals(2, assets.size());
        assertEquals("b.msi", Json.text(Json.object(assets.get(1)), "name"));
        assertTrue(Json.object(object.get("empty")).isEmpty());
        assertTrue(Json.array(object.get("none")).isEmpty());
    }

    @Test
    void readsEscapesAndArabic() {
        Map<String, Object> object = Json.object(Json.parse(
                "{\"body\": \"line one\\r\\nline \\\"two\\\"\\t\\u0645\\u0631\\u062d\\u0628\\u0627 \\/ إصدار\"}"));
        assertEquals("line one\r\nline \"two\"\tمرحبا / إصدار", Json.text(object, "body"));
    }

    @Test
    void aMissingOrWronglyTypedKeyHasADefault() {
        Map<String, Object> object = Json.object(Json.parse("{\"size\": \"big\", \"name\": 7}"));
        assertEquals("", Json.text(object, "name"));
        assertEquals("", Json.text(object, "absent"));
        assertEquals(-1, Json.number(object, "size", -1));
        assertTrue(Json.object("text").isEmpty());
        assertTrue(Json.array(null).isEmpty());
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("<html>502 Bad Gateway</html>"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": \"unclosed}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(500)));
    }
}
