package io.perfana.jmeter.timescaledb.model;

import java.time.Instant;

/**
 * Data model for transactions table.
 */
public class TransactionRecord {
    private Instant time;
    private String testRunId;
    private String systemUnderTest;
    private String testEnvironment;
    private String scenarioName;
    private String location;
    private String transactionName;
    private boolean success;
    private Integer requestSize;
    private Integer responseSize;
    private Integer responseTime;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final TransactionRecord record = new TransactionRecord();

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

        public Builder responseTime(Integer responseTime) {
            record.responseTime = responseTime;
            return this;
        }

        public TransactionRecord build() {
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

    public boolean isSuccess() {
        return success;
    }

    public Integer getRequestSize() {
        return requestSize;
    }

    public Integer getResponseSize() {
        return responseSize;
    }

    public Integer getResponseTime() {
        return responseTime;
    }
}
