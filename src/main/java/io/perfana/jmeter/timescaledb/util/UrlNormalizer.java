package io.perfana.jmeter.timescaledb.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.regex.Matcher;
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

    // Separator between query param name and value: literal '=' or percent-encoded '%3d'
    private static final Pattern PARAM_SEPARATOR_PATTERN = Pattern.compile("=|%3[dD]");

    // Pattern for long alphanumeric tokens (>20 chars, likely encoded values)
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9]{21,}$");

    /**
     * A path segment mixing letters and digits is an identifier, not a word: a Siebel row id
     * ({@code 1-q02oj1h}), a short hash, a tenant key. Eight characters is the floor that keeps the
     * real path words which happen to carry a digit: {@code v1}, {@code v2}, {@code oauth2},
     * {@code html5}, {@code base64}, {@code sha256}.
     */
    private static final int MIN_MIXED_ID_LENGTH = 8;

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
     * Order of replacement: UUID -> Numeric ID -> Long tokens -> mixed letter/digit ids.
     *
     * <p>Only the path is examined. The scheme and host are copied through untouched, or a host
     * like {@code web3app0110.farm.example.com} would be masked as an identifier and every node
     * of a farm would collapse into one pattern.
     */
    private String normalizePath(String path) {
        int pathStart = pathStart(path);
        String authority = path.substring(0, pathStart);

        // First replace UUIDs
        String result = UUID_PATTERN.matcher(path.substring(pathStart)).replaceAll("{uuid}");

        // Split into segments and process each
        String[] segments = result.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }

            // Path parameters (;jsessionid=...) belong to the segment, not to the query, so they
            // are masked here and the segment itself is classified without them.
            String parameters = "";
            int semicolon = segment.indexOf(';');
            if (semicolon >= 0) {
                parameters = normalizePathParameters(segment.substring(semicolon));
                segment = segment.substring(0, semicolon);
            }

            segments[i] = classifySegment(segment) + parameters;
        }

        return authority + String.join("/", segments);
    }

    /**
     * @return the index where the path begins, so that the scheme and host are never classified
     */
    private static int pathStart(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return 0;
        }
        int slash = url.indexOf('/', schemeEnd + 3);
        return slash < 0 ? url.length() : slash;
    }

    /**
     * Masks the values of the {@code ;name=value} parameters a path segment can carry. A
     * {@code jsessionid} there is per session, so leaving it in makes every request its own
     * pattern. The names are kept, matching how the query string is handled.
     *
     * @param parameters the segment tail starting at the first {@code ;}
     */
    private String normalizePathParameters(String parameters) {
        StringBuilder masked = new StringBuilder();
        for (String parameter : parameters.split(";", -1)) {
            if (parameter.isEmpty()) {
                continue;
            }
            int equals = parameter.indexOf('=');
            masked.append(';')
                    .append(equals >= 0 ? parameter.substring(0, equals) + "={val}" : parameter);
        }
        return masked.toString();
    }

    /** @return the placeholder for a dynamic segment, or the segment itself when it is a word */
    private String classifySegment(String segment) {
        if (segment.isEmpty()) {
            return segment;
        }

        // Skip if already normalized
        if (segment.startsWith("{") && segment.endsWith("}")) {
            return segment;
        }

        if (NUMERIC_ID_PATTERN.matcher(segment).matches()) {
            return "{id}";
        }

        // Before the mixed-id rule, so a token that already normalized to {token} keeps that
        // placeholder and the url_hash of patterns captured by earlier versions does not move.
        if (LONG_TOKEN_PATTERN.matcher(segment).matches()) {
            return "{token}";
        }

        return isMixedId(segment) ? "{id}" : segment;
    }

    /**
     * @return whether the segment mixes letters and digits and is long enough to be an identifier
     *         rather than a word carrying a version or a standard's name
     */
    static boolean isMixedId(String segment) {
        if (segment.length() < MIN_MIXED_ID_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < segment.length(); i++) {
            char character = segment.charAt(i);
            if (Character.isLetter(character)) {
                letter = true;
            } else if (Character.isDigit(character)) {
                digit = true;
            } else if (character != '-' && character != '_' && character != '.') {
                // Anything else is not the shape of an identifier; leave it alone.
                return false;
            }
        }
        return letter && digit;
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
        Matcher separator = PARAM_SEPARATOR_PATTERN.matcher(param);
        if (!separator.find()) {
            // Parameter without value
            return param;
        }

        String name = param.substring(0, separator.start());
        // Replace value with placeholder; everything after the first separator is dynamic,
        // including encoded '&' (%26) and spaces (%20) that hide extra key/value pairs.
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
