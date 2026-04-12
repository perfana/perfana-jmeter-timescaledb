package io.perfana.jmeter.timescaledb.model;

import java.time.Instant;

/**
 * Data model for url_patterns table.
 * Stores normalized URL patterns for deduplication.
 */
public class UrlPatternRecord {
    private String urlHash;
    private String systemUnderTest;
    private String testEnvironment;
    private String normalizedUrl;
    private String originalExample;
    private Instant firstSeen;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final UrlPatternRecord record = new UrlPatternRecord();

        public Builder urlHash(String urlHash) {
            record.urlHash = urlHash;
            return this;
        }

        public Builder systemUnderTest(String systemUnderTest) {
            record.systemUnderTest = systemUnderTest;
            return this;
        }

        public Builder testEnvironment(String testEnvironment) {
            record.testEnvironment = testEnvironment;
            return this;
        }

        public Builder normalizedUrl(String normalizedUrl) {
            record.normalizedUrl = normalizedUrl;
            return this;
        }

        public Builder originalExample(String originalExample) {
            record.originalExample = originalExample;
            return this;
        }

        public Builder firstSeen(Instant firstSeen) {
            record.firstSeen = firstSeen;
            return this;
        }

        public UrlPatternRecord build() {
            return record;
        }
    }

    // Getters

    public String getUrlHash() {
        return urlHash;
    }

    public String getSystemUnderTest() {
        return systemUnderTest;
    }

    public String getTestEnvironment() {
        return testEnvironment;
    }

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public String getOriginalExample() {
        return originalExample;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    /**
     * Creates a composite key for deduplication within a batch.
     */
    public String getCompositeKey() {
        return urlHash + "|" + systemUnderTest + "|" + testEnvironment;
    }
}
