package io.perfana.jmeter.timescaledb.model;

import java.time.Instant;

/**
 * Data model for requests_error table.
 */
public class RequestErrorRecord {
    private Instant time;
    private String testRunId;
    private String systemUnderTest;
    private String testEnvironment;
    private String scenarioName;
    private String location;
    private String nodeName;
    private String transactionName;
    private String samplerName;
    private String responseCode;
    private Integer responseTime;
    private Integer connectionTime;
    private String url;
    private String urlHash;
    private String assertions;
    private String responseMessage;
    private String requestHeaders;
    private String responseHeaders;
    private String responseData;
    private Integer randomId;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final RequestErrorRecord record = new RequestErrorRecord();

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

        public Builder nodeName(String nodeName) {
            record.nodeName = nodeName;
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

        public Builder responseCode(String responseCode) {
            record.responseCode = responseCode;
            return this;
        }

        public Builder responseTime(Integer responseTime) {
            record.responseTime = responseTime;
            return this;
        }

        public Builder connectionTime(Integer connectionTime) {
            record.connectionTime = connectionTime;
            return this;
        }

        public Builder url(String url) {
            record.url = url;
            return this;
        }

        public Builder urlHash(String urlHash) {
            record.urlHash = urlHash;
            return this;
        }

        public Builder assertions(String assertions) {
            record.assertions = assertions;
            return this;
        }

        public Builder responseMessage(String responseMessage) {
            record.responseMessage = responseMessage;
            return this;
        }

        public Builder requestHeaders(String requestHeaders) {
            record.requestHeaders = requestHeaders;
            return this;
        }

        public Builder responseHeaders(String responseHeaders) {
            record.responseHeaders = responseHeaders;
            return this;
        }

        public Builder responseData(String responseData) {
            record.responseData = responseData;
            return this;
        }

        public Builder randomId(Integer randomId) {
            record.randomId = randomId;
            return this;
        }

        public RequestErrorRecord build() {
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

    public String getNodeName() {
        return nodeName;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getSamplerName() {
        return samplerName;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public Integer getResponseTime() {
        return responseTime;
    }

    public Integer getConnectionTime() {
        return connectionTime;
    }

    public String getUrl() {
        return url;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public String getAssertions() {
        return assertions;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public String getResponseHeaders() {
        return responseHeaders;
    }

    public String getResponseData() {
        return responseData;
    }

    public Integer getRandomId() {
        return randomId;
    }
}
