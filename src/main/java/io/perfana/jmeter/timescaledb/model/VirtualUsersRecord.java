package io.perfana.jmeter.timescaledb.model;

import java.time.Instant;

/**
 * Data model for virtual_users table.
 */
public class VirtualUsersRecord {
    private Instant time;
    private String testRunId;
    private String systemUnderTest;
    private String testEnvironment;
    private String scenarioName;
    private String location;
    private String nodeName;
    private Integer activeThreads;
    private Integer startedThreads;
    private Integer finishedThreads;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final VirtualUsersRecord record = new VirtualUsersRecord();

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

        public Builder activeThreads(Integer activeThreads) {
            record.activeThreads = activeThreads;
            return this;
        }

        public Builder startedThreads(Integer startedThreads) {
            record.startedThreads = startedThreads;
            return this;
        }

        public Builder finishedThreads(Integer finishedThreads) {
            record.finishedThreads = finishedThreads;
            return this;
        }

        public VirtualUsersRecord build() {
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

    public Integer getActiveThreads() {
        return activeThreads;
    }

    public Integer getStartedThreads() {
        return startedThreads;
    }

    public Integer getFinishedThreads() {
        return finishedThreads;
    }
}
