package io.perfana.jmeter.timescaledb.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVariableFilterTest {

    private Map<String, String> source(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void deniesNamesCaseInsensitively() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("userId", "1", "Password", "secret", "TOKEN", "x"),
                Set.of("password", "token"), 2048, 16384);
        assertEquals(1, out.size());
        assertTrue(out.containsKey("userId"));
        assertFalse(out.containsKey("Password"));
        assertFalse(out.containsKey("TOKEN"));
    }

    @Test
    void skipsValuesLongerThanMaxValueLength() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("short", "ok", "long", "abcdef"),
                Set.of(), 3, 16384);
        assertEquals(1, out.size());
        assertEquals("ok", out.get("short"));
        assertFalse(out.containsKey("long"));
    }

    @Test
    void stopsAddingOnceTotalBytesExceeded() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("k1", "aaa", "k2", "bbb", "k3", "ccc"),
                Set.of(), 2048, 11); // room for 2 entries (10 bytes), 3rd would hit 15
        assertEquals(2, out.size());
        assertTrue(out.containsKey("k1"));
        assertTrue(out.containsKey("k2"));
        assertFalse(out.containsKey("k3"));
    }

    @Test
    void skipsNullKeysAndValues() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("a", null);
        in.put(null, "b");
        in.put("c", "d");
        Map<String, String> out = SessionVariableFilter.filter(in, Set.of(), 2048, 16384);
        assertEquals(1, out.size());
        assertEquals("d", out.get("c"));
    }

    @Test
    void emptyOrNullSourceReturnsEmpty() {
        assertTrue(SessionVariableFilter.filter(null, Set.of(), 1, 1).isEmpty());
        assertTrue(SessionVariableFilter.filter(source(), Set.of(), 1, 1).isEmpty());
    }

    @Test
    void resultIsImmutable() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("a", "b"), Set.of(), 2048, 16384);
        assertThrows(UnsupportedOperationException.class, () -> out.put("x", "y"));
    }
}
