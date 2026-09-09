package io.perfana.jmeter.timescaledb.writer;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.RequestErrorRecord;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database that stays down must not turn a write outage into an OOM: failed records are
 * re-added to the buffer, so without a ceiling the buffer grows for as long as the outage lasts.
 */
@Testcontainers
class BufferCapIntegrationTest {

    /** Mirrors TimescaleDBWriter.BUFFER_MAX_RECORDS (2 x the 50 000 high-water mark). */
    private static final int BUFFER_MAX_RECORDS = 100_000;

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jmeter")
            .withUsername("jmeter")
            .withPassword("jmeter");

    private TimescaleDBWriter writer;

    @AfterEach
    void closeWriter() {
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void dropsTheOldestRecordsInsteadOfGrowingWithoutLimit() throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS url_patterns CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_error CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_raw CASCADE;");
            st.execute("DROP TABLE IF EXISTS transactions CASCADE;");
            st.execute("DROP TABLE IF EXISTS virtual_users CASCADE;");
            st.execute(Files.readString(Path.of("migrations", "V001__initial_schema.sql"), StandardCharsets.UTF_8));
            // Stand in for a database that is down: the table this buffer inserts into is gone,
            // so every flush fails and every record is re-added.
            st.execute("DROP TABLE requests_error CASCADE;");
        }
        writer = new TimescaleDBWriter(config());

        List<RequestErrorRecord> overflow = new ArrayList<>(BUFFER_MAX_RECORDS + 20_000);
        for (int i = 0; i < BUFFER_MAX_RECORDS + 20_000; i++) {
            overflow.add(RequestErrorRecord.builder()
                    .time(Instant.now())
                    .testRunId("cap")
                    .samplerName("s" + i)
                    .build());
        }
        writer.writeAllRequestErrors(overflow);
        writer.flushAllBuffers(); // fails, re-adds, and must then trim

        assertTrue(writer.getTotalBufferSize() <= BUFFER_MAX_RECORDS,
                "Buffer must be capped, was " + writer.getTotalBufferSize());
    }

    private TimescaleDBConfig config() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        // Large batch size so the write itself does not flush before the test does.
        args.addArgument(TimescaleDBConfig.KEY_BATCH_SIZE, "1000000");
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }
}
