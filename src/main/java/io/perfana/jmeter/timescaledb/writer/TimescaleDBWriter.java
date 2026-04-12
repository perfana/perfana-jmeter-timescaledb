package io.perfana.jmeter.timescaledb.writer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.RequestErrorRecord;
import io.perfana.jmeter.timescaledb.model.RequestRawRecord;
import io.perfana.jmeter.timescaledb.model.TransactionRecord;
import io.perfana.jmeter.timescaledb.model.UrlPatternRecord;
import io.perfana.jmeter.timescaledb.model.VirtualUsersRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handles batch writing of records to TimescaleDB tables.
 * Uses HikariCP for connection pooling and supports periodic flushing.
 */
public class TimescaleDBWriter implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(TimescaleDBWriter.class);

    private final TimescaleDBConfig config;
    private final HikariDataSource dataSource;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock bufferLock;

    // Separate buffers for each table type
    private final List<RequestRawRecord> requestRawBuffer;
    private final List<TransactionRecord> transactionBuffer;
    private final List<RequestErrorRecord> requestErrorBuffer;
    private final List<VirtualUsersRecord> virtualUsersBuffer;
    private final List<UrlPatternRecord> urlPatternBuffer;

    // Buffer high-water mark: when exceeded, writes block until flush completes
    private static final int BUFFER_HIGH_WATER_MARK = 50000;

    // LRU cache for URL patterns to avoid duplicate upserts
    private static final int URL_PATTERN_CACHE_SIZE = 10000;
    private final Set<String> knownUrlPatterns;

    // SQL statements
    private final String insertRequestRawSql;
    private final String insertTransactionSql;
    private final String insertRequestErrorSql;
    private final String insertVirtualUsersSql;
    private final String upsertUrlPatternSql;

    private volatile boolean closed = false;
    private volatile boolean underPressure = false;

    public TimescaleDBWriter(TimescaleDBConfig config) {
        this.config = config;
        this.bufferLock = new ReentrantLock();

        // Initialize buffers
        this.requestRawBuffer = new ArrayList<>();
        this.transactionBuffer = new ArrayList<>();
        this.requestErrorBuffer = new ArrayList<>();
        this.virtualUsersBuffer = new ArrayList<>();
        this.urlPatternBuffer = new ArrayList<>();

        // Initialize LRU cache for URL patterns
        this.knownUrlPatterns = new LinkedHashSet<>(URL_PATTERN_CACHE_SIZE);

        // Build insert SQL statements
        this.insertRequestRawSql = buildRequestRawInsertSql();
        this.insertTransactionSql = buildTransactionInsertSql();
        this.insertRequestErrorSql = buildRequestErrorInsertSql();
        this.insertVirtualUsersSql = buildVirtualUsersInsertSql();
        this.upsertUrlPatternSql = buildUrlPatternUpsertSql();

        // Initialize connection pool
        this.dataSource = createDataSource();

        // Start flush scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TimescaleDB-Flush-Scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::flushAllBuffers,
                config.getFlushInterval(),
                config.getFlushInterval(),
                TimeUnit.SECONDS
        );

        LOGGER.info("TimescaleDBWriter initialized. Host: {}, Database: {}, Schema: {}",
                config.getHost(), config.getDatabase(), config.getSchema());
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUser());
        hikariConfig.setPassword(config.getPassword());

        LOGGER.info("Connecting to: {} with user: {} (password length: {})",
                config.getJdbcUrl(), config.getUser(),
                config.getPassword() != null ? config.getPassword().length() : 0);
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setPoolName("TimescaleDB-JMeter-Pool");

        // Performance optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("reWriteBatchedInserts", "true");

        return new HikariDataSource(hikariConfig);
    }

    private String buildRequestRawInsertSql() {
        return String.format(
                "INSERT INTO %s (time, test_run_id, system_under_test, test_environment, scenario_name, location, transaction_name, sampler_name, success, " +
                        "request_size, response_size, response_code, response_connect_time, response_latency, response_time, url_hash) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                config.getFullTableName(TimescaleDBConfig.TABLE_REQUESTS_RAW)
        );
    }

    private String buildTransactionInsertSql() {
        return String.format(
                "INSERT INTO %s (time, test_run_id, system_under_test, test_environment, scenario_name, location, transaction_name, success, " +
                        "request_size, response_size, response_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                config.getFullTableName(TimescaleDBConfig.TABLE_TRANSACTIONS)
        );
    }

    private String buildRequestErrorInsertSql() {
        return String.format(
                "INSERT INTO %s (time, test_run_id, system_under_test, test_environment, scenario_name, location, node_name, transaction_name, sampler_name, " +
                        "response_code, response_time, connection_time, url, url_hash, assertions, response_message, " +
                        "request_headers, response_headers, response_data, random_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                config.getFullTableName(TimescaleDBConfig.TABLE_REQUESTS_ERROR)
        );
    }

    private String buildVirtualUsersInsertSql() {
        return String.format(
                "INSERT INTO %s (time, test_run_id, system_under_test, test_environment, scenario_name, location, node_name, active_threads, started_threads, finished_threads) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                config.getFullTableName(TimescaleDBConfig.TABLE_VIRTUAL_USERS)
        );
    }

    private String buildUrlPatternUpsertSql() {
        return String.format(
                "INSERT INTO %s (url_hash, system_under_test, test_environment, normalized_url, original_example, first_seen) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (url_hash, system_under_test, test_environment) DO NOTHING",
                config.getFullTableName(TimescaleDBConfig.TABLE_URL_PATTERNS)
        );
    }

    /**
     * Writes a request raw record to the buffer.
     */
    public void writeRequestRaw(RequestRawRecord record) {
        if (closed) {
            LOGGER.warn("Attempted to write to closed TimescaleDBWriter");
            return;
        }

        bufferLock.lock();
        try {
            requestRawBuffer.add(record);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes multiple request raw records to the buffer.
     */
    public void writeAllRequestRaw(List<RequestRawRecord> records) {
        if (closed || records.isEmpty()) {
            return;
        }

        bufferLock.lock();
        try {
            requestRawBuffer.addAll(records);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes a transaction record to the buffer.
     */
    public void writeTransaction(TransactionRecord record) {
        if (closed) {
            return;
        }

        bufferLock.lock();
        try {
            transactionBuffer.add(record);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes multiple transaction records to the buffer.
     */
    public void writeAllTransactions(List<TransactionRecord> records) {
        if (closed || records.isEmpty()) {
            return;
        }

        bufferLock.lock();
        try {
            transactionBuffer.addAll(records);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes a request error record to the buffer.
     */
    public void writeRequestError(RequestErrorRecord record) {
        if (closed) {
            return;
        }

        bufferLock.lock();
        try {
            requestErrorBuffer.add(record);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes multiple request error records to the buffer.
     */
    public void writeAllRequestErrors(List<RequestErrorRecord> records) {
        if (closed || records.isEmpty()) {
            return;
        }

        bufferLock.lock();
        try {
            requestErrorBuffer.addAll(records);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes a virtual users record to the buffer.
     */
    public void writeVirtualUsers(VirtualUsersRecord record) {
        if (closed) {
            return;
        }

        bufferLock.lock();
        try {
            virtualUsersBuffer.add(record);
            checkAndFlushIfNeeded();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Writes a URL pattern record to the buffer if not already known.
     * Uses an LRU cache to avoid duplicate writes within a session.
     */
    public void writeUrlPattern(UrlPatternRecord record) {
        if (closed || record == null) {
            return;
        }

        bufferLock.lock();
        try {
            String compositeKey = record.getCompositeKey();
            if (!knownUrlPatterns.contains(compositeKey)) {
                // Enforce LRU eviction if cache is full
                if (knownUrlPatterns.size() >= URL_PATTERN_CACHE_SIZE) {
                    // Remove oldest entry (first in insertion order)
                    String oldest = knownUrlPatterns.iterator().next();
                    knownUrlPatterns.remove(oldest);
                }
                knownUrlPatterns.add(compositeKey);
                urlPatternBuffer.add(record);
            }
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Checks total buffer size and flushes if needed.
     * When the buffer exceeds the high-water mark, a synchronous flush is forced,
     * applying backpressure to JMeter sampler threads.
     * Assumes lock is already held.
     */
    private void checkAndFlushIfNeeded() {
        int totalSize = requestRawBuffer.size() + transactionBuffer.size() +
                requestErrorBuffer.size() + virtualUsersBuffer.size();
        if (totalSize >= BUFFER_HIGH_WATER_MARK) {
            if (!underPressure) {
                underPressure = true;
                LOGGER.warn("BACKPRESSURE: buffer size {} exceeds high-water mark {}. " +
                        "JMeter threads are blocked until flush completes. " +
                        "Response body storage disabled until buffer drains. " +
                        "TimescaleDB may not be keeping up with the write load.",
                        totalSize, BUFFER_HIGH_WATER_MARK);
            }
            flushAllBuffersLocked();
        } else {
            if (underPressure) {
                underPressure = false;
                LOGGER.info("BACKPRESSURE RESOLVED: buffer size {} below high-water mark {}. " +
                        "Response body storage re-enabled.", totalSize, BUFFER_HIGH_WATER_MARK);
            }
            if (totalSize >= config.getBatchSize()) {
                flushAllBuffersLocked();
            }
        }
    }

    /**
     * Flushes all buffers to the database.
     */
    public void flushAllBuffers() {
        bufferLock.lock();
        try {
            flushAllBuffersLocked();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Internal flush method - assumes lock is already held.
     */
    private void flushAllBuffersLocked() {
        // Flush URL patterns first to ensure foreign key references are valid
        flushUrlPatternBuffer();
        flushRequestRawBuffer();
        flushTransactionBuffer();
        flushRequestErrorBuffer();
        flushVirtualUsersBuffer();
    }

    private void flushUrlPatternBuffer() {
        if (urlPatternBuffer.isEmpty()) {
            return;
        }

        List<UrlPatternRecord> toFlush = new ArrayList<>(urlPatternBuffer);
        urlPatternBuffer.clear();

        try {
            writeUrlPatternBatch(toFlush);
            LOGGER.debug("Flushed {} url_pattern records to TimescaleDB", toFlush.size());
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} url_pattern records: {}", toFlush.size(), e.getMessage(), e);
            // Don't re-add to buffer on failure - patterns are cached in knownUrlPatterns
            // and will be retried on next occurrence
        }
    }

    private void flushRequestRawBuffer() {
        if (requestRawBuffer.isEmpty()) {
            return;
        }

        List<RequestRawRecord> toFlush = new ArrayList<>(requestRawBuffer);
        requestRawBuffer.clear();

        try {
            writeRequestRawBatch(toFlush);
            LOGGER.debug("Flushed {} request_raw records to TimescaleDB", toFlush.size());
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} request_raw records: {}", toFlush.size(), e.getMessage(), e);
            reAddWithBackpressureWarning("request_raw", requestRawBuffer, toFlush);
        }
    }

    private void flushTransactionBuffer() {
        if (transactionBuffer.isEmpty()) {
            return;
        }

        List<TransactionRecord> toFlush = new ArrayList<>(transactionBuffer);
        transactionBuffer.clear();

        try {
            writeTransactionBatch(toFlush);
            LOGGER.debug("Flushed {} transaction records to TimescaleDB", toFlush.size());
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} transaction records: {}", toFlush.size(), e.getMessage(), e);
            reAddWithBackpressureWarning("transaction", transactionBuffer, toFlush);
        }
    }

    private void flushRequestErrorBuffer() {
        if (requestErrorBuffer.isEmpty()) {
            return;
        }

        List<RequestErrorRecord> toFlush = new ArrayList<>(requestErrorBuffer);
        requestErrorBuffer.clear();

        try {
            writeRequestErrorBatch(toFlush);
            LOGGER.debug("Flushed {} request_error records to TimescaleDB", toFlush.size());
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} request_error records: {}", toFlush.size(), e.getMessage(), e);
            reAddWithBackpressureWarning("request_error", requestErrorBuffer, toFlush);
        }
    }

    private void flushVirtualUsersBuffer() {
        if (virtualUsersBuffer.isEmpty()) {
            return;
        }

        List<VirtualUsersRecord> toFlush = new ArrayList<>(virtualUsersBuffer);
        virtualUsersBuffer.clear();

        try {
            writeVirtualUsersBatch(toFlush);
            LOGGER.debug("Flushed {} virtual_users records to TimescaleDB", toFlush.size());
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} virtual_users records: {}", toFlush.size(), e.getMessage(), e);
            reAddWithBackpressureWarning("virtual_users", virtualUsersBuffer, toFlush);
        }
    }

    private <T> void reAddWithBackpressureWarning(String tableName, List<T> buffer, List<T> failedRecords) {
        buffer.addAll(0, failedRecords);
        int totalSize = buffer.size();
        if (totalSize >= BUFFER_HIGH_WATER_MARK && !underPressure) {
            underPressure = true;
            LOGGER.warn("BACKPRESSURE: {} buffer at {} records after flush failure. " +
                    "Subsequent writes will block JMeter threads until TimescaleDB recovers. " +
                    "Response body storage disabled.",
                    tableName, totalSize);
        }
    }

    /**
     * Returns whether the writer is under backpressure.
     * When true, callers should reduce payload size (e.g. skip response body storage).
     */
    public boolean isUnderPressure() {
        return underPressure;
    }

    private void writeRequestRawBatch(List<RequestRawRecord> records) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(insertRequestRawSql)) {

            connection.setAutoCommit(false);

            for (RequestRawRecord record : records) {
                stmt.setTimestamp(1, Timestamp.from(record.getTime()));
                stmt.setString(2, record.getTestRunId());
                stmt.setString(3, record.getSystemUnderTest());
                stmt.setString(4, record.getTestEnvironment());
                setNullableString(stmt, 5, record.getScenarioName());
                setNullableString(stmt, 6, record.getLocation());
                setNullableString(stmt, 7, record.getTransactionName());
                stmt.setString(8, record.getSamplerName());
                stmt.setBoolean(9, record.isSuccess());
                setNullableInt(stmt, 10, record.getRequestSize());
                setNullableInt(stmt, 11, record.getResponseSize());
                setNullableString(stmt, 12, record.getResponseCode());
                setNullableInt(stmt, 13, record.getResponseConnectTime());
                setNullableInt(stmt, 14, record.getResponseLatency());
                setNullableInt(stmt, 15, record.getResponseTime());
                setNullableString(stmt, 16, record.getUrlHash());
                stmt.addBatch();
            }

            stmt.executeBatch();
            connection.commit();
        }
    }

    private void writeTransactionBatch(List<TransactionRecord> records) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(insertTransactionSql)) {

            connection.setAutoCommit(false);

            for (TransactionRecord record : records) {
                stmt.setTimestamp(1, Timestamp.from(record.getTime()));
                stmt.setString(2, record.getTestRunId());
                stmt.setString(3, record.getSystemUnderTest());
                stmt.setString(4, record.getTestEnvironment());
                setNullableString(stmt, 5, record.getScenarioName());
                setNullableString(stmt, 6, record.getLocation());
                stmt.setString(7, record.getTransactionName());
                stmt.setBoolean(8, record.isSuccess());
                setNullableInt(stmt, 9, record.getRequestSize());
                setNullableInt(stmt, 10, record.getResponseSize());
                setNullableInt(stmt, 11, record.getResponseTime());
                stmt.addBatch();
            }

            stmt.executeBatch();
            connection.commit();
        }
    }

    private void writeRequestErrorBatch(List<RequestErrorRecord> records) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(insertRequestErrorSql)) {

            connection.setAutoCommit(false);

            for (RequestErrorRecord record : records) {
                stmt.setTimestamp(1, Timestamp.from(record.getTime()));
                stmt.setString(2, record.getTestRunId());
                stmt.setString(3, record.getSystemUnderTest());
                stmt.setString(4, record.getTestEnvironment());
                setNullableString(stmt, 5, record.getScenarioName());
                setNullableString(stmt, 6, record.getLocation());
                stmt.setString(7, record.getNodeName());
                stmt.setString(8, record.getTransactionName());
                stmt.setString(9, record.getSamplerName());
                setNullableString(stmt, 10, record.getResponseCode());
                setNullableInt(stmt, 11, record.getResponseTime());
                setNullableInt(stmt, 12, record.getConnectionTime());
                setNullableString(stmt, 13, record.getUrl());
                setNullableString(stmt, 14, record.getUrlHash());
                setNullableString(stmt, 15, record.getAssertions());
                setNullableString(stmt, 16, record.getResponseMessage());
                setNullableString(stmt, 17, record.getRequestHeaders());
                setNullableString(stmt, 18, record.getResponseHeaders());
                setNullableString(stmt, 19, record.getResponseData());
                setNullableInt(stmt, 20, record.getRandomId());
                stmt.addBatch();
            }

            stmt.executeBatch();
            connection.commit();
        }
    }

    private void writeVirtualUsersBatch(List<VirtualUsersRecord> records) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(insertVirtualUsersSql)) {

            connection.setAutoCommit(false);

            for (VirtualUsersRecord record : records) {
                stmt.setTimestamp(1, Timestamp.from(record.getTime()));
                stmt.setString(2, record.getTestRunId());
                stmt.setString(3, record.getSystemUnderTest());
                stmt.setString(4, record.getTestEnvironment());
                setNullableString(stmt, 5, record.getScenarioName());
                setNullableString(stmt, 6, record.getLocation());
                stmt.setString(7, record.getNodeName());
                setNullableInt(stmt, 8, record.getActiveThreads());
                setNullableInt(stmt, 9, record.getStartedThreads());
                setNullableInt(stmt, 10, record.getFinishedThreads());
                stmt.addBatch();
            }

            stmt.executeBatch();
            connection.commit();
        }
    }

    private void setNullableString(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value != null) {
            stmt.setString(index, value);
        } else {
            stmt.setNull(index, Types.VARCHAR);
        }
    }

    private void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value != null) {
            stmt.setInt(index, value);
        } else {
            stmt.setNull(index, Types.INTEGER);
        }
    }

    private void writeUrlPatternBatch(List<UrlPatternRecord> records) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(upsertUrlPatternSql)) {

            connection.setAutoCommit(false);

            for (UrlPatternRecord record : records) {
                stmt.setString(1, record.getUrlHash());
                stmt.setString(2, record.getSystemUnderTest());
                stmt.setString(3, record.getTestEnvironment());
                stmt.setString(4, record.getNormalizedUrl());
                setNullableString(stmt, 5, record.getOriginalExample());
                stmt.setTimestamp(6, Timestamp.from(record.getFirstSeen()));
                stmt.addBatch();
            }

            stmt.executeBatch();
            connection.commit();
        }
    }

    /**
     * Tests the database connection.
     *
     * @return true if connection is valid
     */
    public boolean testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            LOGGER.error("Connection test failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns the total buffer size across all tables.
     */
    public int getTotalBufferSize() {
        bufferLock.lock();
        try {
            return requestRawBuffer.size() + transactionBuffer.size() +
                    requestErrorBuffer.size() + virtualUsersBuffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Closes the writer, flushing any remaining records and shutting down the connection pool.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        LOGGER.info("Closing TimescaleDBWriter...");

        // Stop the scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Final flush
        flushAllBuffers();

        // Close connection pool
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        LOGGER.info("TimescaleDBWriter closed");
    }
}
