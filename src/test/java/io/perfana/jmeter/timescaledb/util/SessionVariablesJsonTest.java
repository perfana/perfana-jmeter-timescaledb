package io.perfana.jmeter.timescaledb.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionVariablesJsonTest {

    @Test
    void nullMapReturnsNull() {
        assertNull(SessionVariablesJson.toJson(null));
    }

    @Test
    void emptyMapReturnsNull() {
        assertNull(SessionVariablesJson.toJson(new LinkedHashMap<>()));
    }

    @Test
    void serializesSimpleMapInInsertionOrder() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("userId", "42");
        map.put("cartId", "abc");
        assertEquals("{\"userId\":\"42\",\"cartId\":\"abc\"}", SessionVariablesJson.toJson(map));
    }

    @Test
    void escapesSpecialCharacters() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("q", "a\"b\\c\nd\te");
        assertEquals("{\"q\":\"a\\\"b\\\\c\\nd\\te\"}", SessionVariablesJson.toJson(map));
    }

    @Test
    void escapesControlCharactersAsUnicode() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("k", "");
        assertEquals("{\"k\":\"\\u0001\"}", SessionVariablesJson.toJson(map));
    }

    @Test
    void nullValueSerializedAsEmptyString() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("k", null);
        assertEquals("{\"k\":\"\"}", SessionVariablesJson.toJson(map));
    }
}
