package io.perfana.jmeter.timescaledb.model;

import java.time.Instant;

/**
 * Data model for requests_raw table.
 */
public class RequestRawRecord {
    private Instant time;
    private String testRunId;
    private String systemUnderTest;
    private String testEnvironment;
    private String scenarioName;
    private String location;
    private String transactionName;
    private String samplerName;
    private boolean success;
    private Integer requestSize;
    private Integer responseSize;
    private String responseCode;
    private Integer responseConnectTime;
    private Integer responseLatency;
    private Integer responseTime;
    private String urlHash;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final RequestRawRecord record = new RequestRawRecord();

        public Builder time(Instant time) {
            record.time = time;
            return this;
        }

        public Builder testRunId(String testRunId) {
            record.testRunId = testRunId;
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

        public Builder scenarioName(String scenarioName) {
            record.scenarioName = scenarioName;
            return this;
        }

        public Builder location(String location) {
            record.location = location;
            return this;
        }

        public Builder transactionName(String transactionName) {
            record.transactionName = transactionName;
            return this;
        }

        public Builder samplerName(String samplerName) {
            record.samplerName = samplerName;
            return this;
        }

        public Builder success(boolean success) {
            record.success = success;
            return this;
        }

        public Builder requestSize(Integer requestSize) {
            record.requestSize = requestSize;
            return this;
        }

        public Builder responseSize(Integer responseSize) {
            record.responseSize = responseSize;
            return this;
        }

        public Builder responseCode(String responseCode) {
            record.responseCode = responseCode;
            return this;
        }

        public Builder responseConnectTime(Integer responseConnectTime) {
            record.responseConnectTime = responseConnectTime;
            return this;
        }

        public Builder responseLatency(Integer responseLatency) {
            record.responseLatency = responseLatency;
            return this;
        }

        public Builder responseTime(Integer responseTime) {
            record.responseTime = responseTime;
            return this;
        }

        public Builder urlHash(String urlHash) {
            record.urlHash = urlHash;
            return this;
        }

        public RequestRawRecord build() {
            return record;
        }
    }

    // Getters

    public Instant getTime() {
        return time;
    }

    public String getTestRunId() {
        return testRunId;
    }

    public String getSystemUnderTest() {
        return systemUnderTest;
    }

    public String getTestEnvironment() {
        return testEnvironment;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public String getLocation() {
        return location;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getSamplerName() {
        return samplerName;
    }

    public boolean isSuccess() {
        return success;
    }

    public Integer getRequestSize() {
        return requestSize;
    }

    public Integer getResponseSize() {
        return responseSize;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public Integer getResponseConnectTime() {
        return responseConnectTime;
    }

    public Integer getResponseLatency() {
        return responseLatency;
    }

    public Integer getResponseTime() {
        return responseTime;
    }

    public String getUrlHash() {
        return urlHash;
    }
}
