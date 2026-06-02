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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class SessionVariablesIntegrationTest {

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

    private void runMigrations(boolean includeV003) throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            // Drop all tables first so each test starts clean (shared container).
            // TimescaleDB requires hypertables to be dropped individually — a combined
            // DROP TABLE on multiple hypertables throws "cannot drop a hypertable along
            // with other objects", so we issue one statement per table.
            st.execute("DROP TABLE IF EXISTS url_patterns CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_error CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_raw CASCADE;");
            st.execute("DROP TABLE IF EXISTS transactions CASCADE;");
            st.execute("DROP TABLE IF EXISTS virtual_users CASCADE;");
            st.execute(readMigration("V001__initial_schema.sql"));
            st.execute(readMigration("V002__add_url_normalization.sql"));
            if (includeV003) {
                st.execute(readMigration("V003__add_session_variables.sql"));
            }
        }
    }

    private String readMigration(String name) throws IOException {
        return Files.readString(Path.of("migrations", name), StandardCharsets.UTF_8);
    }

    private TimescaleDBConfig config(boolean save) {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, String.valueOf(save));
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    private RequestErrorRecord errorRecord(Map<String, String> sessionVariables) {
        return RequestErrorRecord.builder()
                .time(Instant.now())
                .testRunId("run-1")
                .systemUnderTest("sut")
                .testEnvironment("test")
                .nodeName("controller")
                .transactionName("txn")
                .samplerName("sampler")
                .responseCode("500")
                .sessionVariables(sessionVariables)
                .build();
    }

    @Test
    void storesSessionVariablesAsQueryableJsonb() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config(true));
        assertTrue(writer.isSessionVariablesCaptureEnabled());

        writer.writeAllRequestErrors(List.of(errorRecord(Map.of("cartId", "xyz-123"))));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT session_variables->>'cartId' AS cart FROM requests_error " +
                     "WHERE session_variables->>'cartId' = 'xyz-123'")) {
            assertTrue(rs.next());
            assertEquals("xyz-123", rs.getString("cart"));
        }
    }

    @Test
    void writesNullWhenNoSessionVariables() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config(true));

        writer.writeAllRequestErrors(List.of(errorRecord(null)));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_variables FROM requests_error")) {
            assertTrue(rs.next());
            assertNull(rs.getString("session_variables"));
        }
    }

    @Test
    void degradesGracefullyWhenColumnAbsent() throws Exception {
        runMigrations(false); // no V003 -> column missing
        writer = new TimescaleDBWriter(config(true));
        assertFalse(writer.isSessionVariablesCaptureEnabled());

        // Insert must still succeed (column simply omitted from the INSERT)
        writer.writeAllRequestErrors(List.of(errorRecord(Map.of("cartId", "xyz-123"))));
        writer.flushAllBuffers();

        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM requests_error")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}
