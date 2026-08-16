package io.perfana.jmeter.timescaledb.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVariableFilterTest {

    /** Everything not JMeter-internal, the broadest allow-list a user can configure. */
    private static final List<Pattern> ALL = SessionVariableFilter.compilePatterns("*");

    private Map<String, String> source(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void capturesNothingWithoutAnAllowList() {
        // The whole point of opt-in: an unconfigured run stores no session state at all.
        Map<String, String> out = SessionVariableFilter.filter(
                source("userId", "1", "cartId", "9"),
                SessionVariableFilter.compilePatterns(""), 2048, 16384);
        assertTrue(out.isEmpty());
        assertTrue(SessionVariableFilter.compilePatterns(null).isEmpty());
    }

    @Test
    void keepsOnlyAllowedNames() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("userId", "1", "password", "secret", "cartId", "9"),
                SessionVariableFilter.compilePatterns("userId, cartId "), 2048, 16384);
        assertEquals(2, out.size());
        assertTrue(out.containsKey("userId"));
        assertTrue(out.containsKey("cartId"));
        assertFalse(out.containsKey("password"));
    }

    @Test
    void matchesNamesCaseInsensitively() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("USERID", "1", "UserId", "2"),
                SessionVariableFilter.compilePatterns("userid"), 2048, 16384);
        assertEquals(2, out.size());
    }

    @Test
    void supportsWildcards() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("order_id", "1", "order_total", "99", "userId", "7", "sessionId", "s",
                        "shipping_id_ext", "x"),
                SessionVariableFilter.compilePatterns("order_*,*Id,ship*_id_*"), 2048, 16384);
        assertEquals(Map.of("order_id", "1", "order_total", "99", "userId", "7",
                "sessionId", "s", "shipping_id_ext", "x"), out);
    }

    @Test
    void patternMustMatchTheWholeName() {
        // "cart" allows exactly cart, not cartId — otherwise a narrow pattern silently widens.
        Map<String, String> out = SessionVariableFilter.filter(
                source("cart", "1", "cartId", "2", "shoppingcart", "3"),
                SessionVariableFilter.compilePatterns("cart"), 2048, 16384);
        assertEquals(Map.of("cart", "1"), out);
    }

    @Test
    void treatsPatternsAsGlobsNotRegexes() {
        // A name containing regex metacharacters is matched literally.
        Map<String, String> out = SessionVariableFilter.filter(
                source("a.b", "1", "axb", "2"),
                SessionVariableFilter.compilePatterns("a.b"), 2048, 16384);
        assertEquals(Map.of("a.b", "1"), out);
    }

    @Test
    void skipsValuesLongerThanMaxValueLength() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("short", "ok", "long", "abcdef"),
                ALL, 3, 16384);
        assertEquals(1, out.size());
        assertEquals("ok", out.get("short"));
        assertFalse(out.containsKey("long"));
    }

    @Test
    void stopsAddingOnceTotalBytesExceeded() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("k1", "aaa", "k2", "bbb", "k3", "ccc"),
                ALL, 2048, 11); // room for 2 entries (10 bytes), 3rd would hit 15
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
        Map<String, String> out = SessionVariableFilter.filter(in, ALL, 2048, 16384);
        assertEquals(1, out.size());
        assertEquals("d", out.get("c"));
    }

    @Test
    void emptyOrNullSourceReturnsEmpty() {
        assertTrue(SessionVariableFilter.filter(null, ALL, 1, 1).isEmpty());
        assertTrue(SessionVariableFilter.filter(source(), ALL, 1, 1).isEmpty());
    }

    @Test
    void resultIsImmutable() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("a", "b"), ALL, 2048, 16384);
        assertThrows(UnsupportedOperationException.class, () -> out.put("x", "y"));
    }

    @Test
    void dropsDoubleUnderscoreJMeterInternals() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("__jm__Webshop Browse Users__idx", "0",
                        "__jmeter.U_T__", "Webshop Browse Users 1-1",
                        "__jmv_SAME_USER", "true",
                        "userId", "42"),
                ALL, 2048, 16384);
        assertEquals(1, out.size());
        assertTrue(out.containsKey("userId"));
    }

    @Test
    void dropsJMeterThreadAndStartBuiltins() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("JMeterThread.pack", "org.apache.jmeter.threads.SamplePackage@6ab524d",
                        "JMeterThread.last_sample_ok", "false",
                        "START.MS", "1780466711837",
                        "START.YMD", "20260603",
                        "START.HMS", "060511",
                        "TESTSTART.MS", "1780466712108",
                        "FIRST_NAME", "Bezalel"),
                ALL, 2048, 16384);
        assertEquals(1, out.size());
        assertEquals("Bezalel", out.get("FIRST_NAME"));
    }

    @Test
    void jmeterInternalFilterBeatsAnExplicitPattern() {
        // Asking for the noise by name still does not store it.
        Map<String, String> out = SessionVariableFilter.filter(
                source("__jm__x", "1", "keep", "2"),
                SessionVariableFilter.compilePatterns("__jm__x,keep"), 2048, 16384);
        assertEquals(Map.of("keep", "2"), out);
    }

    @Test
    void keepsUserDefinedPropertiesThatAreNotJMeterInternal() {
        Map<String, String> out = SessionVariableFilter.filter(
                source("HOST", "afterburner-fe", "PORT", "8080", "testRunId", "run-1"),
                ALL, 2048, 16384);
        assertEquals(3, out.size());
        assertTrue(out.containsKey("HOST"));
        assertTrue(out.containsKey("PORT"));
        assertTrue(out.containsKey("testRunId"));
    }
}
