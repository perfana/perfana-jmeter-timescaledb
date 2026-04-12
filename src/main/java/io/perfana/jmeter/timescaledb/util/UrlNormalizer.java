package io.perfana.jmeter.timescaledb.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for normalizing URLs by masking dynamic values.
 * Creates consistent URL patterns for deduplication and analysis.
 */
public class UrlNormalizer {

    // Pattern for UUIDs (case-insensitive)
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    // Pattern for pure numeric IDs
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("^\\d+$");

    // Pattern for long alphanumeric tokens (>20 chars, likely encoded values)
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9]{21,}$");

    /**
     * Result of URL normalization containing the normalized URL, its hash, and the original.
     */
    public record NormalizedUrl(String normalized, String hash, String original) {}

    /**
     * Normalizes a URL by masking dynamic values and computing its hash.
     *
     * @param url the original URL to normalize
     * @return NormalizedUrl containing normalized form, MD5 hash, and original
     */
    public NormalizedUrl normalize(String url) {
        if (url == null || url.isEmpty()) {
            return new NormalizedUrl("", computeMd5Hash(""), url);
        }

        String normalized = normalizeUrl(url);
        String hash = computeMd5Hash(normalized);
        return new NormalizedUrl(normalized, hash, url);
    }

    /**
     * Normalizes the URL by processing path and query components.
     */
    private String normalizeUrl(String url) {
        // Split URL into path and query parts
        int queryIndex = url.indexOf('?');
        String path;
        String query;

        if (queryIndex >= 0) {
            path = url.substring(0, queryIndex);
            query = url.substring(queryIndex + 1);
        } else {
            path = url;
            query = null;
        }

        // Normalize path
        String normalizedPath = normalizePath(path);

        // Normalize query if present
        if (query != null && !query.isEmpty()) {
            String normalizedQuery = normalizeQuery(query);
            return normalizedPath + "?" + normalizedQuery;
        }

        return normalizedPath;
    }

    /**
     * Normalizes URL path by replacing dynamic segments.
     * Order of replacement: UUID -> Numeric ID -> Long tokens
     */
    private String normalizePath(String path) {
        // First replace UUIDs
        String result = UUID_PATTERN.matcher(path).replaceAll("{uuid}");

        // Split into segments and process each
        String[] segments = result.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }

            // Skip if already normalized
            if (segment.startsWith("{") && segment.endsWith("}")) {
                continue;
            }

            // Check for numeric ID
            if (NUMERIC_ID_PATTERN.matcher(segment).matches()) {
                segments[i] = "{id}";
                continue;
            }

            // Check for long alphanumeric token
            if (LONG_TOKEN_PATTERN.matcher(segment).matches()) {
                segments[i] = "{token}";
            }
        }

        return String.join("/", segments);
    }

    /**
     * Normalizes query string by replacing values and sorting parameters.
     */
    private String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }

        // Split into parameters, normalize values, and sort
        return Arrays.stream(query.split("&"))
                .map(this::normalizeQueryParam)
                .sorted()
                .collect(Collectors.joining("&"));
    }

    /**
     * Normalizes a single query parameter by replacing its value.
     */
    private String normalizeQueryParam(String param) {
        int equalsIndex = param.indexOf('=');
        if (equalsIndex < 0) {
            // Parameter without value
            return param;
        }

        String name = param.substring(0, equalsIndex);
        // Replace value with placeholder
        return name + "={val}";
    }

    /**
     * Computes MD5 hash of the input string and returns it as a 32-char hex string.
     */
    private String computeMd5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            BigInteger bigInt = new BigInteger(1, digest);
            String hash = bigInt.toString(16);
            // Pad with leading zeros if necessary
            while (hash.length() < 32) {
                hash = "0" + hash;
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available, but fallback to simple hash
            return String.format("%032x", input.hashCode());
        }
    }
}
