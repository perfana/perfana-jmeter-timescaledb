package io.perfana.jmeter.timescaledb;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.RequestErrorRecord;
import io.perfana.jmeter.timescaledb.model.RequestRawRecord;
import io.perfana.jmeter.timescaledb.model.TransactionRecord;
import io.perfana.jmeter.timescaledb.model.UrlPatternRecord;
import io.perfana.jmeter.timescaledb.model.VirtualUsersRecord;
import io.perfana.jmeter.timescaledb.util.UrlNormalizer;
import io.perfana.jmeter.timescaledb.writer.TimescaleDBWriter;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterContextService.ThreadCounts;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Backend listener that writes JMeter metrics to TimescaleDB.
 * Writes to four tables: requests_raw, transactions, requests_error, virtual_users.
 *
 * @author Joerek van Gaalen, refactored for TimescaleDB
 */
public class JMeterTimescaleDBBackendListenerClient extends AbstractBackendListenerClient implements Runnable {
    private static final Logger log = LogManager.getLogger(JMeterTimescaleDBBackendListenerClient.class);

    private static final String TRANSACTION_MESSAGE = "Number of samples in transaction";
    private static final String SEPARATOR = "::";
    private static final String HTTP_SAMPLE_RESULT = "HTTPSampleResult";
    private static final int ONE_MS_IN_NANOSECONDS = 1000000;

    private ScheduledExecutorService scheduler;
    private TimescaleDBConfig config;
    private TimescaleDBWriter writer;
    private UrlNormalizer urlNormalizer;

    private static class SampleNames {
        final String transactionName;
        final String samplerName;

        SampleNames(String transactionName, String samplerName) {
            this.transactionName = transactionName;
            this.samplerName = samplerName;
        }
    }

    private SampleNames determineNames(SampleResult sampleResult) {
        String sampleLabel = sampleResult.getSampleLabel();
        int separatorIndex = sampleLabel.indexOf(SEPARATOR);
        if (separatorIndex > 0) {
            return new SampleNames(
                    sampleLabel.substring(0, separatorIndex),
                    sampleLabel.substring(separatorIndex + 2).trim()
            );
        }

        String responseMessage;
        SampleResult current = sampleResult;
        while (current != null) {
            responseMessage = current.getResponseMessage();
            if (responseMessage != null && responseMessage.startsWith(TRANSACTION_MESSAGE)) {
                return new SampleNames(current.getSampleLabel(), sampleLabel);
            }
            current = current.getParent();
        }

        // Standalone samplers (no transaction parent) - use label as both names
        return new SampleNames(sampleLabel, sampleLabel);
    }

    private void addAllSubResults(SampleResult sampleResult, List<SampleResult> samplerList, List<SampleResult> transactionList) {
        if (sampleResult.getResponseMessage() != null && sampleResult.getResponseMessage().startsWith(TRANSACTION_MESSAGE)) {
            transactionList.add(sampleResult);
        } else if (sampleResult.getSubResults().length == 0) {
            samplerList.add(sampleResult);
        }

        for (SampleResult subResult : sampleResult.getSubResults()) {
            addAllSubResults(subResult, samplerList, transactionList);
        }
    }

    private String gatherHeaderString(SampleResult sampleResult) {
        if (isHttpSampleResult(sampleResult)) {
            HTTPSampleResult httpResult = (HTTPSampleResult) sampleResult;
            StringBuilder headerString = new StringBuilder();

            headerString.append(httpResult.getHTTPMethod())
                    .append(" ")
                    .append(httpResult.getURL())
                    .append("\n");

            headerString.append(httpResult.getRequestHeaders());

            String cookies = httpResult.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                headerString.append("Cookie: ").append(cookies).append("\n");
            }

            String queryString = httpResult.getQueryString();
            if (queryString != null && !queryString.isEmpty()) {
                headerString.append("\n").append(queryString);
            }

            return headerString.toString();
        } else if (sampleResult.getSamplerData() != null) {
            return sampleResult.getSamplerData();
        }
        return "";
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        if (sampleResults == null || sampleResults.isEmpty()) {
            return;
        }

        List<SampleResult> samplerList = new ArrayList<>();
        List<SampleResult> transactionList = new ArrayList<>();

        for (SampleResult sampleResult : sampleResults) {
            addAllSubResults(sampleResult, samplerList, transactionList);
        }

        List<RequestRawRecord> requestRawRecords = new ArrayList<>();
        List<RequestErrorRecord> requestErrorRecords = new ArrayList<>();
        List<TransactionRecord> transactionRecords = new ArrayList<>();

        // Process samplers
        for (SampleResult sampleResult : samplerList) {
            getUserMetrics().add(sampleResult);

            SampleNames names = determineNames(sampleResult);

            // Skip orphan samplers that don't belong to any transaction
            if (names == null) {
                log.debug("Dropping orphan sampler: {}", sampleResult.getSampleLabel());
                continue;
            }

            // Skip phantom samples with invalid timestamps (nested transaction controllers)
            if (sampleResult.getEndTime() == 0) {
                log.debug("Dropping phantom sample with epoch 0 timestamp: {}", sampleResult.getSampleLabel());
                continue;
            }

            Instant timestamp = Instant.ofEpochMilli(sampleResult.getEndTime());
            int randomId = getUniqueNumberForTheSamplerThread();

            // Normalize URL if enabled
            String urlHash = null;
            String originalUrl = sampleResult.getUrlAsString();
            if (config.isNormalizeUrls() && originalUrl != null && !originalUrl.isEmpty()) {
                UrlNormalizer.NormalizedUrl normalizedUrl = urlNormalizer.normalize(originalUrl);
                urlHash = normalizedUrl.hash();

                // Create URL pattern record for upsert
                UrlPatternRecord urlPatternRecord = UrlPatternRecord.builder()
                        .urlHash(normalizedUrl.hash())
                        .systemUnderTest(config.getSystemUnderTest())
                        .testEnvironment(config.getTestEnvironment())
                        .normalizedUrl(normalizedUrl.normalized())
                        .originalExample(normalizedUrl.original())
                        .firstSeen(timestamp)
                        .build();
                writer.writeUrlPattern(urlPatternRecord);
            }

            // Build request_raw record
            RequestRawRecord rawRecord = RequestRawRecord.builder()
                    .time(timestamp)
                    .testRunId(config.getEffectiveRunId())
                    .systemUnderTest(config.getSystemUnderTest())
                    .testEnvironment(config.getTestEnvironment())
                    .scenarioName(config.getScenarioName())
                    .location(config.getLocation())
                    .transactionName(names.transactionName)
                    .samplerName(names.samplerName)
                    .success(sampleResult.isSuccessful())
                    .requestSize(toInt(sampleResult.getSentBytes()))
                    .responseSize(toInt(sampleResult.getBytesAsLong()))
                    .responseCode(sampleResult.getResponseCode())
                    .responseConnectTime(toInt(sampleResult.getConnectTime()))
                    .responseLatency(toInt(sampleResult.getLatency()))
                    .responseTime(toInt(sampleResult.getTime()))
                    .urlHash(urlHash)
                    .build();

            requestRawRecords.add(rawRecord);

            // Build request_error record if failed
            if (!sampleResult.isSuccessful()) {
                boolean reducedPayload = writer.isUnderPressure();
                String assertions = buildAssertionsString(sampleResult);
                String responseData = getResponseData(sampleResult);

                RequestErrorRecord errorRecord = RequestErrorRecord.builder()
                        .time(timestamp)
                        .testRunId(config.getEffectiveRunId())
                        .systemUnderTest(config.getSystemUnderTest())
                        .testEnvironment(config.getTestEnvironment())
                        .scenarioName(config.getScenarioName())
                        .location(config.getLocation())
                        .nodeName(config.getNodeName())
                        .transactionName(names.transactionName)
                        .samplerName(names.samplerName)
                        .responseCode(sampleResult.getResponseCode())
                        .responseTime(toInt(sampleResult.getTime()))
                        .connectionTime(toInt(sampleResult.getConnectTime()))
                        .url(originalUrl)
                        .urlHash(urlHash)
                        .assertions(assertions)
                        .responseMessage(sampleResult.getResponseMessage())
                        .requestHeaders(reducedPayload ? null : gatherHeaderString(sampleResult))
                        .responseHeaders(reducedPayload ? null : sampleResult.getResponseHeaders())
                        .responseData(responseData)
                        .randomId(randomId)
                        .build();

                requestErrorRecords.add(errorRecord);
            }
        }

        // Process transactions
        for (SampleResult sampleResult : transactionList) {
            getUserMetrics().add(sampleResult);

            String transactionName = sampleResult.getSampleLabel();
            Instant timestamp = Instant.ofEpochMilli(sampleResult.getEndTime());

            TransactionRecord transactionRecord = TransactionRecord.builder()
                    .time(timestamp)
                    .testRunId(config.getEffectiveRunId())
                    .systemUnderTest(config.getSystemUnderTest())
                    .testEnvironment(config.getTestEnvironment())
                    .scenarioName(config.getScenarioName())
                    .location(config.getLocation())
                    .transactionName(transactionName)
                    .success(sampleResult.isSuccessful())
                    .requestSize(toInt(sampleResult.getSentBytes()))
                    .responseSize(toInt(sampleResult.getBytesAsLong()))
                    .responseTime(toInt(sampleResult.getTime()))
                    .build();

            transactionRecords.add(transactionRecord);
        }

        // Write all records
        writer.writeAllRequestRaw(requestRawRecords);
        writer.writeAllRequestErrors(requestErrorRecords);
        writer.writeAllTransactions(transactionRecords);
    }

    private String buildAssertionsString(SampleResult sampleResult) {
        AssertionResult[] assertions = sampleResult.getAssertionResults();
        if (assertions == null || assertions.length == 0) {
            return null;
        }

        StringBuilder assertionsList = new StringBuilder();
        for (AssertionResult assertion : assertions) {
            if (assertion != null && assertion.getFailureMessage() != null) {
                assertionsList.append(assertion.getFailureMessage()).append("\n");
            }
        }
        return assertionsList.length() > 0 ? assertionsList.toString() : null;
    }

    private String getResponseData(SampleResult sampleResult) {
        if (!config.isSaveResponseBody() || writer.isUnderPressure()) {
            return null;
        }

        String dataType = sampleResult.getDataType();
        if ("text".equals(dataType)) {
            String respData = sampleResult.getResponseDataAsString();
            return respData != null ? respData : null;
        }
        return "binary";
    }

    private Integer toInt(long value) {
        return (int) value;
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();

        // TimescaleDB connection parameters
        arguments.addArgument(TimescaleDBConfig.KEY_HOST, "${__P(timescaleHost," + TimescaleDBConfig.DEFAULT_HOST + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_PORT, "${__P(timescalePort," + TimescaleDBConfig.DEFAULT_PORT + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_DATABASE, "${__P(timescaleDatabase," + TimescaleDBConfig.DEFAULT_DATABASE + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SCHEMA, "${__P(timescaleSchema," + TimescaleDBConfig.DEFAULT_SCHEMA + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_USER, "${__P(timescaleUser," + TimescaleDBConfig.DEFAULT_USER + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_PASSWORD, "${__P(timescalePassword," + TimescaleDBConfig.DEFAULT_PASSWORD + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "${__P(timescaleSslMode," + TimescaleDBConfig.DEFAULT_SSL_MODE + ")}");

        // Connection pool parameters
        arguments.addArgument(TimescaleDBConfig.KEY_MAX_POOL_SIZE, "${__P(timescaleMaxPoolSize," + TimescaleDBConfig.DEFAULT_MAX_POOL_SIZE + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_CONNECTION_TIMEOUT, "${__P(timescaleConnectionTimeout," + TimescaleDBConfig.DEFAULT_CONNECTION_TIMEOUT + ")}");

        // Batch parameters
        arguments.addArgument(TimescaleDBConfig.KEY_BATCH_SIZE, "${__P(timescaleBatchSize," + TimescaleDBConfig.DEFAULT_BATCH_SIZE + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_FLUSH_INTERVAL, "${__P(timescaleFlushInterval," + TimescaleDBConfig.DEFAULT_FLUSH_INTERVAL + ")}");

        // Test identification parameters
        arguments.addArgument(TimescaleDBConfig.KEY_RUN_ID, "${__P(runId," + TimescaleDBConfig.DEFAULT_RUN_ID + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_LOCATION, "${__P(location," + TimescaleDBConfig.DEFAULT_LOCATION + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_NODE_NAME, "${__P(nodeName," + TimescaleDBConfig.DEFAULT_NODE_NAME + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SYSTEM_UNDER_TEST, "${__P(systemUnderTest," + TimescaleDBConfig.DEFAULT_SYSTEM_UNDER_TEST + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_TEST_ENVIRONMENT, "${__P(testEnvironment," + TimescaleDBConfig.DEFAULT_TEST_ENVIRONMENT + ")}");

        // Mode parameters
        arguments.addArgument(TimescaleDBConfig.KEY_SYNTHETIC_MONITORING, "${__P(syntheticMonitoring," + TimescaleDBConfig.DEFAULT_SYNTHETIC_MONITORING + ")}");
        arguments.addArgument(TimescaleDBConfig.KEY_SCENARIO_NAME, "${__P(scenarioName," + TimescaleDBConfig.DEFAULT_SCENARIO_NAME + ")}");

        // Data capture parameters
        arguments.addArgument(TimescaleDBConfig.KEY_SAVE_RESPONSE_BODY, "${__P(saveResponseBody," + TimescaleDBConfig.DEFAULT_SAVE_RESPONSE_BODY + ")}");

        // URL normalization parameters
        arguments.addArgument(TimescaleDBConfig.KEY_NORMALIZE_URLS, "${__P(normalizeUrls," + TimescaleDBConfig.DEFAULT_NORMALIZE_URLS + ")}");

        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) {
        try {
            log.info("Setting up Perfana JMeter TimescaleDB Backend Listener...");

            // Parse configuration
            config = TimescaleDBConfig.fromContext(context);

            // Initialize URL normalizer if enabled
            if (config.isNormalizeUrls()) {
                urlNormalizer = new UrlNormalizer();
                log.info("URL normalization enabled");
            }

            // Initialize writer
            writer = new TimescaleDBWriter(config);

            // Test connection
            if (!writer.testConnection()) {
                throw new RuntimeException("Failed to connect to TimescaleDB at " + config.getJdbcUrl());
            }

            log.info("Successfully connected to TimescaleDB: {}", config.getJdbcUrl());

            // Setup scheduler for virtual users metrics (standard mode only)
            if (!config.isSyntheticMonitoring()) {
                scheduler = Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "TimescaleDB-VU-Scheduler");
                    t.setDaemon(true);
                    return t;
                });
                scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
                log.info("Virtual users tracking enabled");
            }

            log.info("Perfana JMeter TimescaleDB Backend Listener setup complete");

        } catch (Exception e) {
            log.error("Error setting up test: " + e.getMessage(), e);
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
            if (writer != null) {
                writer.close();
            }
            throw new RuntimeException("Failed to setup TimescaleDB Backend Listener", e);
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        try {
            log.info("Tearing down Perfana JMeter TimescaleDB Backend Listener...");

            if (!config.isSyntheticMonitoring() && scheduler != null) {
                log.info("Shutting down virtual users scheduler...");
                scheduler.shutdown();

                // Record final virtual users metrics
                addVirtualUsersMetrics(0, 0, JMeterContextService.getThreadCounts().finishedThreads);

                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("The scheduler did not terminate within the specified timeout.");
                    scheduler.shutdownNow();
                } else {
                    log.info("Virtual users scheduler terminated successfully.");
                }
            }

            // Close the writer (flushes remaining records)
            if (writer != null) {
                writer.close();
            }

            super.teardownTest(context);
            log.info("Perfana JMeter TimescaleDB Backend Listener teardown complete");

        } catch (InterruptedException e) {
            log.error("Thread was interrupted while waiting for the executor to terminate.", e);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        if (!config.isSyntheticMonitoring()) {
            try {
                ThreadCounts tc = JMeterContextService.getThreadCounts();
                addVirtualUsersMetrics(tc.activeThreads, tc.startedThreads, tc.finishedThreads);
            } catch (Exception e) {
                log.error("Failed writing virtual users metrics to TimescaleDB", e);
            }
        }
    }

    private void addVirtualUsersMetrics(int activeThreads, int startedThreads, int finishedThreads) {
        VirtualUsersRecord record = VirtualUsersRecord.builder()
                .time(Instant.now())
                .testRunId(config.getEffectiveRunId())
                .systemUnderTest(config.getSystemUnderTest())
                .testEnvironment(config.getTestEnvironment())
                .scenarioName(config.getScenarioName())
                .location(config.getLocation())
                .nodeName(config.getNodeName())
                .activeThreads(activeThreads)
                .startedThreads(startedThreads)
                .finishedThreads(finishedThreads)
                .build();

        writer.writeVirtualUsers(record);
    }

    private int getUniqueNumberForTheSamplerThread() {
        return ThreadLocalRandom.current().nextInt(ONE_MS_IN_NANOSECONDS);
    }

    private boolean isHttpSampleResult(SampleResult result) {
        return result.getClass().getSimpleName().equals(HTTP_SAMPLE_RESULT);
    }
}
