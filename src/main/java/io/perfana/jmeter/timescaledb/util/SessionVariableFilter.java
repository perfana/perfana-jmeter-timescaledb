package io.perfana.jmeter.timescaledb.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Filters a raw session-variable map down to what is safe and bounded to persist: keeps only
 * names matching the configured allow-list patterns, drops JMeter-internal noise variables,
 * skips oversized values, and stops once the cumulative key+value byte budget is exceeded.
 *
 * <p>Capture is opt-in per variable: a name nobody asked for is never stored, so a session
 * holding an unexpected token or personal detail cannot leak into the database by default.
 */
public final class SessionVariableFilter {

    private SessionVariableFilter() {
    }

    /**
     * Compiles the configured allow-list into matchers.
     *
     * @param csv comma-separated name patterns, where {@code *} matches any run of characters
     *        ({@code cart*}, {@code *Id}, {@code *} for everything). Matching is
     *        case-insensitive and must cover the whole name.
     * @return one matcher per non-blank pattern, empty when nothing was configured (which means
     *         no variable is captured)
     */
    public static List<Pattern> compilePatterns(String csv) {
        List<Pattern> patterns = new ArrayList<>();
        if (csv == null) {
            return patterns;
        }
        for (String raw : csv.split(",")) {
            String glob = raw.trim();
            if (!glob.isEmpty()) {
                patterns.add(Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE));
            }
        }
        return patterns;
    }

    /**
     * @param source           raw variable map (may be null)
     * @param includePatterns  allow-list matchers from {@link #compilePatterns(String)}; an empty
     *                         list captures nothing
     * @param maxValueLength   values longer than this (char length) are skipped entirely
     * @param maxTotalBytes    once cumulative UTF-8 bytes of kept key+value pairs would
     *                         exceed this, no further entries are added
     * @return an immutable, insertion-ordered map of kept entries (never null)
     */
    public static Map<String, String> filter(
            Map<String, String> source,
            List<Pattern> includePatterns,
            int maxValueLength,
            int maxTotalBytes) {

        if (source == null || source.isEmpty() || includePatterns.isEmpty()) {
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
            if (!matchesAny(key, includePatterns)) {
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

    private static boolean matchesAny(String name, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translates a name glob to a regex: everything is literal except {@code *}, so a pattern
     * cannot accidentally be a regex that matches more than the author meant.
     */
    // ponytail: '*' only. Add '?' if a real plan ever needs single-character matching.
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        int from = 0;
        int star;
        while ((star = glob.indexOf('*', from)) >= 0) {
            if (star > from) {
                regex.append(Pattern.quote(glob.substring(from, star)));
            }
            regex.append(".*");
            from = star + 1;
        }
        if (from < glob.length()) {
            regex.append(Pattern.quote(glob.substring(from)));
        }
        return regex.toString();
    }

    /**
     * Whether a variable name is a JMeter built-in / internal bookkeeping variable that
     * carries no debugging value (timestamps, thread iteration indices, internal object
     * references). Always excluded, so a broad pattern such as {@code *} captures the test's
     * own variables without the engine's noise.
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
