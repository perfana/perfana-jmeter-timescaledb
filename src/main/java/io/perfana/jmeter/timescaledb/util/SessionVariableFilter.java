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
