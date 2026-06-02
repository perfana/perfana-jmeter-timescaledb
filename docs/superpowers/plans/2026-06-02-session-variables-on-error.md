# Session Variables on Error Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a failed JMeter sample, snapshot the virtual user's session variables (minus a secret deny-list) and store them as a queryable `jsonb` map in `requests_error.session_variables`.

**Architecture:** Variables are live only on the sampler thread, so `createSampleResult` (sampler thread) filters + snapshots them on failure and stashes the immutable map in a bounded identity-keyed side-channel. `handleSampleResults` (worker thread) pulls the snapshot back out by the top-level `SampleResult` identity and attaches it to the error record. `TimescaleDBWriter` serializes the map to JSON and binds it as `jsonb`, but only after a `setupTest` probe confirms the column exists — if it's missing, capture is disabled for the run instead of failing every insert.

**Tech Stack:** Java 17, Gradle, JUnit 5 (newly added), Testcontainers (newly added), PostgreSQL JDBC (`PGobject`), JMeter 5.6.3, HikariCP. No new runtime/JSON dependency — JSON is hand-rolled for `Map<String,String>`.

**Spec:** `docs/superpowers/specs/2026-06-02-session-variables-on-error-design.md`

---

## File Structure

**New files:**
- `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJson.java` — serialize `Map<String,String>` → compact JSON (or `null` when empty).
- `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilter.java` — pure filter: deny-list (case-insensitive), per-value length cap, total-bytes cap → immutable map.
- `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrier.java` — bounded identity-keyed side-channel between sampler and worker threads.
- `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJsonTest.java`
- `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilterTest.java`
- `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrierTest.java`
- `src/test/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfigSessionVariablesTest.java`
- `src/test/java/io/perfana/jmeter/timescaledb/writer/SessionVariablesIntegrationTest.java`
- `src/test/java/io/perfana/jmeter/timescaledb/SmokeTest.java` (Task 0 sanity, deleted in Task 0 last step — see note)
- `migrations/V003__add_session_variables.sql`

**Modified files:**
- `build.gradle` — JUnit 5 + Testcontainers test deps, `test { useJUnitPlatform() }`.
- `src/main/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfig.java` — 4 new keys/defaults/fields/getters.
- `src/main/java/io/perfana/jmeter/timescaledb/model/RequestErrorRecord.java` — `Map<String,String> sessionVariables` field.
- `src/main/java/io/perfana/jmeter/timescaledb/writer/TimescaleDBWriter.java` — column probe, conditional INSERT SQL, jsonb bind.
- `src/main/java/io/perfana/jmeter/timescaledb/JMeterTimescaleDBBackendListenerClient.java` — `createSampleResult` override, snapshot threading, carrier init, new default params.
- `README.md` — document new args + PII warning + timing note.

---

## Task 0: Test infrastructure

**Files:**
- Modify: `build.gradle`
- Test: `src/test/java/io/perfana/jmeter/timescaledb/SmokeTest.java`

- [ ] **Step 1: Add test dependencies and JUnit platform to `build.gradle`**

In the `dependencies { ... }` block (after line 34, the logging block), add:

```groovy
    // Test dependencies
    testImplementation "org.apache.jmeter:ApacheJMeter_core:${jmeterVersion}"
    testImplementation "org.apache.jmeter:ApacheJMeter_http:${jmeterVersion}"
    testImplementation "org.apache.logging.log4j:log4j-api:${log4jVersion}"
    testImplementation "org.apache.logging.log4j:log4j-core:${log4jVersion}"
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
    testImplementation 'org.testcontainers:postgresql:1.20.4'
    testImplementation 'org.testcontainers:junit-jupiter:1.20.4'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

After the `java { ... }` block (after line 42), add:

```groovy
test {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
    }
}
```

- [ ] **Step 2: Write a smoke test**

Create `src/test/java/io/perfana/jmeter/timescaledb/SmokeTest.java`:

```java
package io.perfana.jmeter.timescaledb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void junitIsWired() {
        assertTrue(true);
    }
}
```

- [ ] **Step 3: Run the smoke test**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.SmokeTest'`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/test/java/io/perfana/jmeter/timescaledb/SmokeTest.java
git commit -m "test: add JUnit 5 + Testcontainers test infrastructure"
```

> Note: keep `SmokeTest.java` — it is a cheap guard that the test toolchain stays wired. Do not delete it.

---

## Task 1: JSON serializer for the snapshot map

**Files:**
- Create: `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJson.java`
- Test: `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJsonTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJsonTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariablesJsonTest'`
Expected: FAIL — `SessionVariablesJson` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJson.java`:

```java
package io.perfana.jmeter.timescaledb.util;

import java.util.Map;

/**
 * Minimal JSON serializer for a flat {@code Map<String,String>} of session variables.
 * Hand-rolled to avoid adding a JSON runtime dependency to the JMeter plugin jar.
 */
public final class SessionVariablesJson {

    private SessionVariablesJson() {
    }

    /**
     * Serializes the map to a compact JSON object string, preserving iteration order.
     *
     * @return JSON object string, or {@code null} when the map is null or empty
     *         (so callers write SQL NULL rather than an empty object)
     */
    public static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, e.getKey());
            sb.append(':');
            appendString(sb, e.getValue() == null ? "" : e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariablesJsonTest'`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJson.java \
        src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariablesJsonTest.java
git commit -m "feat: add JSON serializer for session variable maps"
```

---

## Task 2: Deny-list + cap filter

**Files:**
- Create: `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilter.java`
- Test: `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilterTest.java`:

```java
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
        // each "kN"=3 bytes key-ish; use explicit sizes: key 2 bytes + value 3 bytes = 5 bytes each
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariableFilterTest'`
Expected: FAIL — `SessionVariableFilter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilter.java`:

```java
package io.perfana.jmeter.timescaledb.util;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filters a raw session-variable map down to what is safe and bounded to persist:
 * drops deny-listed names (case-insensitive), skips oversized values, and stops
 * once the cumulative key+value byte budget is exceeded.
 */
public final class SessionVariableFilter {

    private SessionVariableFilter() {
    }

    /**
     * @param source           raw variable map (may be null)
     * @param denyLowercase    variable names to skip, already lower-cased
     * @param maxValueLength   values longer than this (char length) are skipped entirely
     * @param maxTotalBytes    once cumulative UTF-8 bytes of kept key+value pairs would
     *                         exceed this, no further entries are added
     * @return an immutable, insertion-ordered map of kept entries (never null)
     */
    public static Map<String, String> filter(
            Map<String, String> source,
            Set<String> denyLowercase,
            int maxValueLength,
            int maxTotalBytes) {

        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        int totalBytes = 0;

        for (Map.Entry<String, String> e : source.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (denyLowercase.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (value.length() > maxValueLength) {
                continue;
            }
            int entryBytes = key.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes + entryBytes > maxTotalBytes) {
                break;
            }
            totalBytes += entryBytes;
            result.put(key, value);
        }

        return Collections.unmodifiableMap(result);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariableFilterTest'`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilter.java \
        src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableFilterTest.java
git commit -m "feat: add session variable deny-list and size-cap filter"
```

---

## Task 3: Side-channel carrier

**Files:**
- Create: `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrier.java`
- Test: `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrierTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrierTest.java`:

```java
package io.perfana.jmeter.timescaledb.util;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVariableCarrierTest {

    @Test
    void putThenRemoveReturnsSnapshotByIdentity() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, Map.of("a", "b"));
        assertEquals(Map.of("a", "b"), carrier.removeAndGet(key));
    }

    @Test
    void removeIsOneShot() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, Map.of("a", "b"));
        carrier.removeAndGet(key);
        assertNull(carrier.removeAndGet(key));
        assertEquals(0, carrier.size());
    }

    @Test
    void unknownKeyReturnsNull() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        assertNull(carrier.removeAndGet(new SampleResult()));
    }

    @Test
    void distinctSampleResultsAreDistinctKeysEvenWhenEqualByContent() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult a = new SampleResult();
        SampleResult b = new SampleResult();
        carrier.put(a, Map.of("k", "1"));
        carrier.put(b, Map.of("k", "2"));
        assertEquals(Map.of("k", "1"), carrier.removeAndGet(a));
        assertEquals(Map.of("k", "2"), carrier.removeAndGet(b));
    }

    @Test
    void evictsEldestBeyondCapacity() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(2);
        SampleResult first = new SampleResult();
        SampleResult second = new SampleResult();
        SampleResult third = new SampleResult();
        carrier.put(first, Map.of("n", "1"));
        carrier.put(second, Map.of("n", "2"));
        carrier.put(third, Map.of("n", "3")); // evicts "first"
        assertEquals(2, carrier.size());
        assertNull(carrier.removeAndGet(first));
        assertEquals(Map.of("n", "2"), carrier.removeAndGet(second));
        assertEquals(Map.of("n", "3"), carrier.removeAndGet(third));
    }

    @Test
    void toleratesNullSnapshotWithoutStoring() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, null);
        assertTrue(carrier.size() == 0);
        assertNull(carrier.removeAndGet(key));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariableCarrierTest'`
Expected: FAIL — `SessionVariableCarrier` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrier.java`:

```java
package io.perfana.jmeter.timescaledb.util;

import org.apache.jmeter.samplers.SampleResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries a per-sample session-variable snapshot from the sampler thread
 * ({@code createSampleResult}) to the listener worker thread ({@code handleSampleResults}).
 *
 * <p>Keyed on {@link SampleResult} identity ({@code SampleResult} does not override
 * {@code equals}/{@code hashCode}). Only failed samples ever populate it, and the worker
 * removes each entry on lookup, so it normally self-cleans. A bounded eldest-eviction
 * policy caps memory if some entries never reach the worker (e.g. dropped phantom samples).
 *
 * <p>Thread-safe: all access is synchronized on the backing map.
 */
public final class SessionVariableCarrier {

    private final Map<SampleResult, Map<String, String>> store;

    public SessionVariableCarrier(int maxEntries) {
        this.store = Collections.synchronizedMap(
                new LinkedHashMap<SampleResult, Map<String, String>>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<SampleResult, Map<String, String>> eldest) {
                        return size() > maxEntries;
                    }
                });
    }

    /** Stashes a snapshot for the given top-level result. No-op when snapshot is null. */
    public void put(SampleResult key, Map<String, String> snapshot) {
        if (snapshot == null) {
            return;
        }
        store.put(key, snapshot);
    }

    /** Removes and returns the snapshot for the given result, or null if absent. */
    public Map<String, String> removeAndGet(SampleResult key) {
        return store.remove(key);
    }

    public int size() {
        return store.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.util.SessionVariableCarrierTest'`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrier.java \
        src/test/java/io/perfana/jmeter/timescaledb/util/SessionVariableCarrierTest.java
git commit -m "feat: add identity-keyed side-channel carrier for session snapshots"
```

---

## Task 4: Config — new keys, defaults, parsing

**Files:**
- Modify: `src/main/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfig.java`
- Test: `src/test/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfigSessionVariablesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfigSessionVariablesTest.java`:

```java
package io.perfana.jmeter.timescaledb.config;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimescaleDBConfigSessionVariablesTest {

    private BackendListenerContext context(Arguments args) {
        return new BackendListenerContext(args);
    }

    @Test
    void defaultsDisableCaptureWithSecretDenyList() {
        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(new Arguments()));
        assertFalse(config.isSaveSessionVariables());
        assertEquals(2048, config.getSessionVariablesMaxValueLength());
        assertEquals(16384, config.getSessionVariablesMaxTotalBytes());
        // default deny-list is lower-cased
        assertTrue(config.getSessionVariablesExclude().contains("password"));
        assertTrue(config.getSessionVariablesExclude().contains("jsessionid"));
    }

    @Test
    void parsesEnabledFlagAndCustomDenyList() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, "true");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_EXCLUDE, "Foo, BAR ,baz");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, "10");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, "100");

        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(args));

        assertTrue(config.isSaveSessionVariables());
        assertEquals(10, config.getSessionVariablesMaxValueLength());
        assertEquals(100, config.getSessionVariablesMaxTotalBytes());
        // custom list REPLACES default and is lower-cased + trimmed
        assertTrue(config.getSessionVariablesExclude().contains("foo"));
        assertTrue(config.getSessionVariablesExclude().contains("bar"));
        assertTrue(config.getSessionVariablesExclude().contains("baz"));
        assertFalse(config.getSessionVariablesExclude().contains("password"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.config.TimescaleDBConfigSessionVariablesTest'`
Expected: FAIL — new keys/getters do not exist.

- [ ] **Step 3: Add fields, keys, defaults, parsing, getters**

In `TimescaleDBConfig.java`:

(a) Add imports at the top (after the existing `import org.apache.jmeter...` line):

```java
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
```

(b) Add fields after the `flattenNestedTransactions` field (line 48):

```java
    // Session variable capture settings
    private boolean saveSessionVariables;
    private Set<String> sessionVariablesExclude;
    private int sessionVariablesMaxValueLength;
    private int sessionVariablesMaxTotalBytes;
```

(c) Add keys after `KEY_FLATTEN_NESTED_TRANSACTIONS` (line 71):

```java
    public static final String KEY_SAVE_SESSION_VARIABLES = "saveSessionVariables";
    public static final String KEY_SESSION_VARIABLES_EXCLUDE = "sessionVariablesExclude";
    public static final String KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH = "sessionVariablesMaxValueLength";
    public static final String KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES = "sessionVariablesMaxTotalBytes";
```

(d) Add defaults after `DEFAULT_FLATTEN_NESTED_TRANSACTIONS` (line 94):

```java
    public static final String DEFAULT_SAVE_SESSION_VARIABLES = "false";
    public static final String DEFAULT_SESSION_VARIABLES_EXCLUDE =
            "password,passwd,pwd,token,secret,authorization,auth,apikey,api_key," +
            "sessionid,jsessionid,cookie,credential,bearer";
    public static final String DEFAULT_SESSION_VARIABLES_MAX_VALUE_LENGTH = "2048";
    public static final String DEFAULT_SESSION_VARIABLES_MAX_TOTAL_BYTES = "16384";
```

(e) In `fromContext`, after the flattenNestedTransactions parsing (line 149), add:

```java
        // Session variable capture settings
        config.saveSessionVariables = Boolean.parseBoolean(
                context.getParameter(KEY_SAVE_SESSION_VARIABLES, DEFAULT_SAVE_SESSION_VARIABLES));
        config.sessionVariablesExclude = parseDenyList(
                context.getParameter(KEY_SESSION_VARIABLES_EXCLUDE, DEFAULT_SESSION_VARIABLES_EXCLUDE));
        config.sessionVariablesMaxValueLength = Integer.parseInt(
                context.getParameter(KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, DEFAULT_SESSION_VARIABLES_MAX_VALUE_LENGTH).trim());
        config.sessionVariablesMaxTotalBytes = Integer.parseInt(
                context.getParameter(KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, DEFAULT_SESSION_VARIABLES_MAX_TOTAL_BYTES).trim());
```

(f) Add the deny-list parser as a private static method (place it just above `getJdbcUrl()`, around line 154):

```java
    private static Set<String> parseDenyList(String csv) {
        Set<String> set = new HashSet<>();
        if (csv == null) {
            return set;
        }
        for (String name : csv.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }
```

(g) Add getters at the end of the class (after `isFlattenNestedTransactions()`, line 268):

```java
    public boolean isSaveSessionVariables() {
        return saveSessionVariables;
    }

    public Set<String> getSessionVariablesExclude() {
        return sessionVariablesExclude;
    }

    public int getSessionVariablesMaxValueLength() {
        return sessionVariablesMaxValueLength;
    }

    public int getSessionVariablesMaxTotalBytes() {
        return sessionVariablesMaxTotalBytes;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.config.TimescaleDBConfigSessionVariablesTest'`
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfig.java \
        src/test/java/io/perfana/jmeter/timescaledb/config/TimescaleDBConfigSessionVariablesTest.java
git commit -m "feat: add session variable capture config keys and parsing"
```

---

## Task 5: RequestErrorRecord — sessionVariables field

**Files:**
- Modify: `src/main/java/io/perfana/jmeter/timescaledb/model/RequestErrorRecord.java`

> This is a plain data holder consistent with the existing builder pattern; it is exercised
> by the integration test (Task 9). No standalone unit test (the existing record classes have none).

- [ ] **Step 1: Add the field, builder method, and getter**

(a) Add import at the top:

```java
import java.util.Map;
```

(b) Add the field after `randomId` (line 28):

```java
    private Map<String, String> sessionVariables;
```

(c) Add the builder method after the `randomId(...)` builder method (after line 135):

```java
        public Builder sessionVariables(Map<String, String> sessionVariables) {
            record.sessionVariables = sessionVariables;
            return this;
        }
```

(d) Add the getter after `getRandomId()` (after line 222):

```java
    public Map<String, String> getSessionVariables() {
        return sessionVariables;
    }
```

- [ ] **Step 2: Compile to verify it builds**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/model/RequestErrorRecord.java
git commit -m "feat: add sessionVariables field to RequestErrorRecord"
```

---

## Task 6: Writer — column probe, conditional SQL, jsonb bind

**Files:**
- Modify: `src/main/java/io/perfana/jmeter/timescaledb/writer/TimescaleDBWriter.java`

> The probe + jsonb binding are verified end-to-end by the integration test (Task 9).
> This task changes the writer to (a) detect the column, (b) include it in the INSERT
> only when present, and (c) bind the map as jsonb.

- [ ] **Step 1: Add imports**

After the existing `java.sql.*` imports (around line 18), add:

```java
import io.perfana.jmeter.timescaledb.util.SessionVariablesJson;
import org.postgresql.util.PGobject;

import java.sql.ResultSet;
```

- [ ] **Step 2: Add the column-present flag field**

After the line `private volatile boolean underPressure = false;` (line 71), add:

```java
    private final boolean sessionVariablesColumnPresent;
```

- [ ] **Step 3: Reorder constructor so the probe runs before building the error SQL**

In the constructor, the current order builds all SQL strings (lines 87–92) and then creates
the data source (line 95). Replace that region so the data source is created first, then the
probe, then the SQL. Replace:

```java
        // Build insert SQL statements
        this.insertRequestRawSql = buildRequestRawInsertSql();
        this.insertTransactionSql = buildTransactionInsertSql();
        this.insertRequestErrorSql = buildRequestErrorInsertSql();
        this.insertVirtualUsersSql = buildVirtualUsersInsertSql();
        this.upsertUrlPatternSql = buildUrlPatternUpsertSql();

        // Initialize connection pool
        this.dataSource = createDataSource();
```

with:

```java
        // Initialize connection pool (needed by the session_variables column probe below)
        this.dataSource = createDataSource();

        // Probe for the optional session_variables column. If capture is enabled but the
        // column is missing (plugin deployed before the schema migration landed), disable
        // it for this run rather than failing every error insert (writeup R1).
        this.sessionVariablesColumnPresent =
                config.isSaveSessionVariables() && probeSessionVariablesColumn();
        if (config.isSaveSessionVariables() && !sessionVariablesColumnPresent) {
            LOGGER.warn("saveSessionVariables is enabled but column {}.{}.session_variables is " +
                    "absent; session variable capture is DISABLED for this run.",
                    config.getSchema(), TimescaleDBConfig.TABLE_REQUESTS_ERROR);
        }

        // Build insert SQL statements
        this.insertRequestRawSql = buildRequestRawInsertSql();
        this.insertTransactionSql = buildTransactionInsertSql();
        this.insertRequestErrorSql = buildRequestErrorInsertSql();
        this.insertVirtualUsersSql = buildVirtualUsersInsertSql();
        this.upsertUrlPatternSql = buildUrlPatternUpsertSql();
```

- [ ] **Step 4: Add the probe method**

Add this private method (place it next to `testConnection()`, around line 663):

```java
    private boolean probeSessionVariablesColumn() {
        String sql = "SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? AND column_name = 'session_variables'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, config.getSchema());
            stmt.setString(2, TimescaleDBConfig.TABLE_REQUESTS_ERROR);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.warn("Could not probe for session_variables column ({}); capture disabled.",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Whether session-variable capture is active for this run (master switch on AND
     * the column exists). The listener checks this to skip snapshotting work entirely.
     */
    public boolean isSessionVariablesCaptureEnabled() {
        return sessionVariablesColumnPresent;
    }
```

- [ ] **Step 5: Make the error INSERT SQL conditional on the column**

Replace `buildRequestErrorInsertSql()` (lines 159–167) with:

```java
    private String buildRequestErrorInsertSql() {
        String columns = "time, test_run_id, system_under_test, test_environment, scenario_name, location, node_name, transaction_name, sampler_name, " +
                "response_code, response_time, connection_time, url, url_hash, assertions, response_message, " +
                "request_headers, response_headers, response_data, random_id";
        String placeholders = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
        if (sessionVariablesColumnPresent) {
            columns += ", session_variables";
            placeholders += ", ?";
        }
        return String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                config.getFullTableName(TimescaleDBConfig.TABLE_REQUESTS_ERROR),
                columns, placeholders
        );
    }
```

- [ ] **Step 6: Bind the jsonb value in the error batch**

In `writeRequestErrorBatch`, after the existing `setNullableInt(stmt, 20, record.getRandomId());`
line (line 577) and before `stmt.addBatch();`, add:

```java
                if (sessionVariablesColumnPresent) {
                    setJsonb(stmt, 21, SessionVariablesJson.toJson(record.getSessionVariables()));
                }
```

- [ ] **Step 7: Add the jsonb bind helper**

Add next to `setNullableString` (around line 611):

```java
    private void setJsonb(PreparedStatement stmt, int index, String json) throws SQLException {
        if (json == null) {
            stmt.setNull(index, Types.OTHER);
        } else {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(json);
            stmt.setObject(index, obj);
        }
    }
```

- [ ] **Step 8: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/writer/TimescaleDBWriter.java
git commit -m "feat: probe for session_variables column and bind it as jsonb"
```

---

## Task 7: Listener — snapshot on failure, thread to worker, write

**Files:**
- Modify: `src/main/java/io/perfana/jmeter/timescaledb/JMeterTimescaleDBBackendListenerClient.java`

> End-to-end behaviour is verified by the integration test (Task 9). The pure pieces it
> relies on (filter, JSON, carrier, config) are already unit-tested in Tasks 1–4.

- [ ] **Step 1: Add imports**

After the existing `import org.apache.jmeter.threads.JMeterContextService...` lines (line 16), add:

```java
import io.perfana.jmeter.timescaledb.util.SessionVariableCarrier;
import io.perfana.jmeter.timescaledb.util.SessionVariableFilter;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterVariables;
```

And in the `java.util` import group (around line 23), add:

```java
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 2: Add the carrier field and constant**

After the `private UrlNormalizer urlNormalizer;` field (line 47), add:

```java
    private SessionVariableCarrier sessionVariableCarrier;

    // Safety cap so snapshots from dropped phantom samples (never seen by the worker)
    // cannot grow unbounded. Only failed samples ever populate the carrier.
    private static final int SESSION_VARIABLE_CARRIER_MAX_ENTRIES = 10000;
```

- [ ] **Step 3: Initialize the carrier in setupTest**

In `setupTest`, right after `config = TimescaleDBConfig.fromContext(context);` (line 369), add:

```java
            sessionVariableCarrier = new SessionVariableCarrier(SESSION_VARIABLE_CARRIER_MAX_ENTRIES);
```

- [ ] **Step 4: Override createSampleResult**

Add this method (place it just above `handleSampleResults`, around line 145):

```java
    /**
     * Runs on the sampler thread, where the live session variables are reachable. For failed
     * samples (only), snapshots a filtered, immutable copy of the thread variables and stashes
     * it keyed on the top-level result so the worker thread can attach it to the error record.
     * Success path does no work (writeup R3).
     */
    @Override
    public SampleResult createSampleResult(BackendListenerContext context, SampleResult result) {
        if (config != null
                && config.isSaveSessionVariables()
                && writer != null
                && writer.isSessionVariablesCaptureEnabled()
                && !result.isSuccessful()
                && !writer.isUnderPressure()) {
            Map<String, String> snapshot = snapshotSessionVariables();
            if (!snapshot.isEmpty()) {
                sessionVariableCarrier.put(result, snapshot);
            }
        }
        return result;
    }

    private Map<String, String> snapshotSessionVariables() {
        JMeterContext ctx = JMeterContextService.getContext();
        if (ctx == null) {
            return Collections.emptyMap();
        }
        JMeterVariables vars = ctx.getVariables();
        if (vars == null) {
            return Collections.emptyMap();
        }
        Map<String, String> source = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            Object value = entry.getValue();
            source.put(entry.getKey(), value == null ? null : value.toString());
        }
        return SessionVariableFilter.filter(
                source,
                config.getSessionVariablesExclude(),
                config.getSessionVariablesMaxValueLength(),
                config.getSessionVariablesMaxTotalBytes());
    }
```

- [ ] **Step 5: Thread the snapshot through handleSampleResults**

(a) Replace the top-level loop that calls `addAllSubResults` (lines 154–156):

```java
        for (SampleResult sampleResult : sampleResults) {
            addAllSubResults(sampleResult, samplerList, transactionList);
        }
```

with a version that resolves each top-level snapshot and records it per leaf:

```java
        Map<SampleResult, Map<String, String>> leafSnapshots = new IdentityHashMap<>();
        for (SampleResult sampleResult : sampleResults) {
            Map<String, String> snapshot = sessionVariableCarrier != null
                    ? sessionVariableCarrier.removeAndGet(sampleResult)
                    : null;
            addAllSubResults(sampleResult, samplerList, transactionList, snapshot, leafSnapshots);
        }
```

(b) Replace the `addAllSubResults` method (lines 90–102) with a version that carries the
snapshot down and tags each leaf:

```java
    private void addAllSubResults(SampleResult sampleResult, List<SampleResult> samplerList,
                                  List<SampleResult> transactionList,
                                  Map<String, String> snapshot,
                                  Map<SampleResult, Map<String, String>> leafSnapshots) {
        if (sampleResult.getResponseMessage() != null && sampleResult.getResponseMessage().startsWith(TRANSACTION_MESSAGE)) {
            if (!config.isFlattenNestedTransactions() || !hasTransactionAncestor(sampleResult)) {
                transactionList.add(sampleResult);
            }
        } else if (sampleResult.getSubResults().length == 0) {
            samplerList.add(sampleResult);
            if (snapshot != null) {
                leafSnapshots.put(sampleResult, snapshot);
            }
        }

        for (SampleResult subResult : sampleResult.getSubResults()) {
            addAllSubResults(subResult, samplerList, transactionList, snapshot, leafSnapshots);
        }
    }
```

(c) Set the snapshot on the error record. In the failed-sample block, in the
`RequestErrorRecord.builder()...` chain, add `.sessionVariables(...)` after
`.randomId(randomId)` (line 250):

```java
                        .randomId(randomId)
                        .sessionVariables(leafSnapshots.get(sampleResult))
```

- [ ] **Step 6: Add new default parameters**

In `getDefaultParameters`, after the `KEY_FLATTEN_NESTED_TRANSACTIONS` argument (line 358), add:

```java
        // Session variable capture parameters
        arguments.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, "${__P(saveSessionVariables," + TimescaleDBConfig.DEFAULT_SAVE_SESSION_VARIABLES + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_EXCLUDE, "${__P(sessionVariablesExclude," + TimescaleDBConfig.DEFAULT_SESSION_VARIABLES_EXCLUDE + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, "${__P(sessionVariablesMaxValueLength," + TimescaleDBConfig.DEFAULT_SESSION_VARIABLES_MAX_VALUE_LENGTH + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, "${__P(sessionVariablesMaxTotalBytes," + TimescaleDBConfig.DEFAULT_SESSION_VARIABLES_MAX_TOTAL_BYTES + ")}");
```

- [ ] **Step 7: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/perfana/jmeter/timescaledb/JMeterTimescaleDBBackendListenerClient.java
git commit -m "feat: snapshot session variables on failed samples and write them"
```

---

## Task 8: Local schema mirror — V003

**Files:**
- Create: `migrations/V003__add_session_variables.sql`

- [ ] **Step 1: Create the migration**

Create `migrations/V003__add_session_variables.sql`:

```sql
-- Migration: Add session_variables capture to requests_error
-- Description: Adds a nullable jsonb column holding a snapshot of the failing virtual
--              user's JMeter session variables. Populated only for failed samples when
--              the listener's saveSessionVariables option is enabled.
--
-- NOTE: This is a DEV/TEST MIRROR of the canonical schema, which is owned by the Perfana
-- repo (packages/shared/src/database/migrations). This change has already landed there;
-- this file keeps local and integration test databases in sync. Do not diverge from the
-- canonical DDL.

ALTER TABLE requests_error ADD COLUMN IF NOT EXISTS session_variables jsonb;

COMMENT ON COLUMN requests_error.session_variables IS
    'Snapshot of the failing virtual user JMeter session variables (deny-list filtered). NULL for success rows and when capture is disabled.';
```

- [ ] **Step 2: Commit**

```bash
git add migrations/V003__add_session_variables.sql
git commit -m "chore: mirror session_variables column in local schema migrations"
```

---

## Task 9: Integration test (Testcontainers)

**Files:**
- Test: `src/test/java/io/perfana/jmeter/timescaledb/writer/SessionVariablesIntegrationTest.java`

> Requires Docker. Spins a real TimescaleDB, runs the three migrations, and verifies the
> full write path: jsonb storage + key-level queryability, NULL for empty, and the R1
> degradation path when the column is absent.

- [ ] **Step 1: Write the integration test**

Create `src/test/java/io/perfana/jmeter/timescaledb/writer/SessionVariablesIntegrationTest.java`:

```java
package io.perfana.jmeter.timescaledb.writer;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.RequestErrorRecord;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class SessionVariablesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jmeter")
            .withUsername("jmeter")
            .withPassword("jmeter");

    private TimescaleDBWriter writer;

    @AfterEach
    void closeWriter() {
        if (writer != null) {
            writer.close();
        }
    }

    private void runMigrations(boolean includeV003) throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute(readMigration("V001__initial_schema.sql"));
            st.execute(readMigration("V002__add_url_normalization.sql"));
            if (includeV003) {
                st.execute(readMigration("V003__add_session_variables.sql"));
            }
        }
    }

    private String readMigration(String name) throws IOException {
        return Files.readString(Path.of("migrations", name), StandardCharsets.UTF_8);
    }

    private TimescaleDBConfig config(boolean save) {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, String.valueOf(save));
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    private RequestErrorRecord errorRecord(Map<String, String> sessionVariables) {
        return RequestErrorRecord.builder()
                .time(Instant.now())
                .testRunId("run-1")
                .systemUnderTest("sut")
                .testEnvironment("test")
                .nodeName("controller")
                .transactionName("txn")
                .samplerName("sampler")
                .responseCode("500")
                .sessionVariables(sessionVariables)
                .build();
    }

    @Test
    void storesSessionVariablesAsQueryableJsonb() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config(true));
        assertTrue(writer.isSessionVariablesCaptureEnabled());

        writer.writeAllRequestErrors(List.of(errorRecord(Map.of("cartId", "xyz-123"))));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT session_variables->>'cartId' AS cart FROM requests_error " +
                     "WHERE session_variables->>'cartId' = 'xyz-123'")) {
            assertTrue(rs.next());
            assertEquals("xyz-123", rs.getString("cart"));
        }
    }

    @Test
    void writesNullWhenNoSessionVariables() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config(true));

        writer.writeAllRequestErrors(List.of(errorRecord(null)));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_variables FROM requests_error")) {
            assertTrue(rs.next());
            assertNull(rs.getString("session_variables"));
        }
    }

    @Test
    void degradesGracefullyWhenColumnAbsent() throws Exception {
        runMigrations(false); // no V003 -> column missing
        writer = new TimescaleDBWriter(config(true));
        assertTrue(!writer.isSessionVariablesCaptureEnabled());

        // Insert must still succeed (column simply omitted from the INSERT)
        writer.writeAllRequestErrors(List.of(errorRecord(Map.of("cartId", "xyz-123"))));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM requests_error")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./gradlew test --tests 'io.perfana.jmeter.timescaledb.writer.SessionVariablesIntegrationTest'`
Expected: PASS — 3 tests. (Docker must be running. First run pulls the TimescaleDB image.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/perfana/jmeter/timescaledb/writer/SessionVariablesIntegrationTest.java
git commit -m "test: integration-test session_variables jsonb write path and R1 degradation"
```

---

## Task 10: Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the feature and its args**

Find the section in `README.md` that lists configuration parameters (search for an existing
key such as `saveResponseBody` or `flattenNestedTransactions`). Add a subsection
immediately after it:

````markdown
### Session variable capture on errors

When a sample fails, the listener can snapshot the failing virtual user's JMeter session
variables and store them in `requests_error.session_variables` (a queryable `jsonb` column),
so failures can be debugged with the session state that produced them.

> ⚠️ **PII / secret exposure.** Session variables routinely hold emails, account ids, and
> correlation tokens. When enabled, these persist in TimescaleDB and its backups. Capture is
> **off by default** and gated by a deny-list — review the exposure for your environment
> before enabling.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `saveSessionVariables` | `false` | Master on/off switch. |
| `sessionVariablesExclude` | `password,passwd,pwd,token,secret,authorization,auth,apikey,api_key,sessionid,jsessionid,cookie,credential,bearer` | Comma-separated variable names to skip (case-insensitive). Supplying your own value **replaces** this default list. |
| `sessionVariablesMaxValueLength` | `2048` | Values longer than this (characters) are skipped entirely (not truncated). |
| `sessionVariablesMaxTotalBytes` | `16384` | Once kept key+value bytes exceed this for a row, no further variables are added. |

Notes:
- Requires the `session_variables jsonb` column on `requests_error`. If the column is absent
  the listener logs a warning and disables capture for the run (it never fails inserts).
- Capture is also skipped while the writer is under backpressure (same as response bodies).
- Captured values reflect **end-of-sample** state (after post-processors/extractors).

Query example:

```sql
SELECT time, sampler_name, session_variables->>'cartId' AS cart_id
FROM requests_error
WHERE session_variables->>'cartId' = '...';
```
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document session variable capture on errors"
```

---

## Task 11: Full build + verification

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; all unit + integration tests pass (Docker running).

- [ ] **Step 2: Confirm the fat jar still builds**

Run: `./gradlew fatJar`
Expected: BUILD SUCCESSFUL; jar produced under `build/libs/`.

- [ ] **Step 3: Final commit (if anything changed)**

```bash
git add -A
git commit -m "chore: session variables on error feature complete" || echo "nothing to commit"
```

---

## Self-Review notes (for the implementer)

- **Spec coverage:** R1 (Task 6 probe + Task 9 degradation test), R2 (Task 4 default-off + deny-list, Task 10 docs), R3 (Task 7 success-path early return), R4 (Task 7 immutable copy via filter), R5 (Task 3 carrier + Task 7 identity threading), R6 (Task 7 `isUnderPressure` gate), R7 (Task 2 caps), R8 (Task 6 jsonb bind + NULL-for-empty, Task 9 test), R9 (Task 7 null-guards), R10 (Task 10 docs). Config surface, V003 mirror, and integration coverage all mapped.
- **Type consistency:** `isSessionVariablesCaptureEnabled()` (writer) and `getSessionVariablesExclude()/...MaxValueLength()/...MaxTotalBytes()` (config) and `getSessionVariables()` (record) are used identically across Tasks 6, 7, and 9.
- **Carrier identity note:** `SampleResult` does not override `equals`/`hashCode`, so both the `LinkedHashMap` carrier and the `IdentityHashMap` leaf map key on object identity — intentional and consistent.
