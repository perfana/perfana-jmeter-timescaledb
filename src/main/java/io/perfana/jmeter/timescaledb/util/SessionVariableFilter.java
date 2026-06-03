package io.perfana.jmeter.timescaledb.util;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filters a raw session-variable map down to what is safe and bounded to persist:
 * drops JMeter-internal noise variables, drops deny-listed names (case-insensitive),
 * skips oversized values, and stops once the cumulative key+value byte budget is exceeded.
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
            if (isJMeterInternal(key)) {
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

    /**
     * Whether a variable name is a JMeter built-in / internal bookkeeping variable that
     * carries no debugging value (timestamps, thread iteration indices, internal object
     * references). Always excluded, independent of the user-configurable deny-list.
     *
     * <p>Covers the {@code __}-prefixed reserved namespace (e.g. {@code __jm__<TG>__idx},
     * {@code __jmeter.U_T__}, {@code __jmv_SAME_USER}), the {@code JMeterThread.*} thread
     * state (e.g. {@code JMeterThread.pack}, {@code JMeterThread.last_sample_ok}), and the
     * fixed test-start timestamp variables.
     */
    private static boolean isJMeterInternal(String name) {
        if (name.startsWith("__") || name.startsWith("JMeterThread.")) {
            return true;
        }
        switch (name) {
            case "START.MS":
            case "START.YMD":
            case "START.HMS":
            case "TESTSTART.MS":
                return true;
            default:
                return false;
        }
    }
}
