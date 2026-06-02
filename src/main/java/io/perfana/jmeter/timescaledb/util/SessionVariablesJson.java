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
