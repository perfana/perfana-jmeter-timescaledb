package io.perfana.jmeter.timescaledb.writer;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.RequestRawRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parent_controllers column is optional: the plugin must write it as jsonb when the migration
 * has been applied and stay silent when it has not, so an un-migrated database keeps recording
 * requests.
 */
@Testcontainers
class ParentControllersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jmeter")
            .withUsername("jmeter")
            .withPassword("jmeter");

    private static final String CHAIN_TEMPLATE =
            "[{\"name\":\"Thread Group\",\"class\":\"org.apache.jmeter.threads.ThreadGroup\",\"iteration\":-1},"
            + "{\"name\":\"loop\",\"class\":\"org.apache.jmeter.control.LoopController\",\"iteration\":%d},"
            + "{\"name\":\"par\",\"class\":\"org.apache.jmeter.control.ParallelController\","
            + "\"iteration\":%d,\"execution\":\"%s\"}]";

    private TimescaleDBWriter writer;

    @AfterEach
    void closeWriter() {
        if (writer != null) {
            writer.close();
        }
    }

    private void runMigrations(boolean includeV004) throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            // Hypertables must be dropped one at a time (see SessionVariablesIntegrationTest).
            st.execute("DROP TABLE IF EXISTS url_patterns CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_error CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_raw CASCADE;");
            st.execute("DROP TABLE IF EXISTS transactions CASCADE;");
            st.execute("DROP TABLE IF EXISTS virtual_users CASCADE;");
            st.execute(readMigration("V001__initial_schema.sql"));
            st.execute(readMigration("V002__add_url_normalization.sql"));
            st.execute(readMigration("V003__add_session_variables.sql"));
            if (includeV004) {
                st.execute(readMigration("V004__add_parent_controllers.sql"));
            }
        }
    }

    private String readMigration(String name) throws IOException {
        return Files.readString(Path.of("migrations", name), StandardCharsets.UTF_8);
    }

    private TimescaleDBConfig config() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    private RequestRawRecord rawRecord(String samplerName, String parentControllers) {
        return RequestRawRecord.builder()
                .time(Instant.now())
                .testRunId("run-1")
                .systemUnderTest("sut")
                .testEnvironment("test")
                .transactionName("T01")
                .samplerName(samplerName)
                .success(true)
                .responseCode("200")
                .responseTime(42)
                .parentControllers(parentControllers)
                .build();
    }

    /** A sample inside a Parallel Controller on the given loop pass and parallel pass. */
    private RequestRawRecord parallelRecord(String samplerName, int loopPass, String execution) {
        return rawRecord(samplerName, String.format(CHAIN_TEMPLATE, loopPass, loopPass, execution));
    }

    private String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "expected a row for: " + sql);
            return rs.getString(1);
        }
    }

    @Test
    void storesTheChainAsQueryableJsonb() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config());
        assertTrue(writer.isParentControllersCaptureEnabled());

        // The shape that is otherwise indistinguishable: both inside the same transaction.
        writer.writeAllRequestRaw(List.of(
                parallelRecord("grouped", 1, "Thread Group 1-1-par-1"),
                rawRecord("sequential", null)));
        writer.flushAllBuffers();

        // The UI's breadcrumb: names outermost first, with the pass each controller was on.
        assertEquals("Thread Group -1 > loop 1 > par 1", query(
                "SELECT string_agg(c->>'name' || ' ' || (c->>'iteration'), ' > ' ORDER BY ord) "
                + "FROM requests_raw, jsonb_array_elements(parent_controllers) WITH ORDINALITY AS t(c, ord) "
                + "WHERE sampler_name = 'grouped'"));
        assertNull(query("SELECT parent_controllers FROM requests_raw WHERE sampler_name = 'sequential'"),
                "An untagged request must be NULL, not an empty array");
    }

    @Test
    void groupsTheRequestsOfOneConcurrentPass() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config());

        writer.writeAllRequestRaw(List.of(
                parallelRecord("one", 1, "user-1-par-1"),
                parallelRecord("two", 1, "user-1-par-1"),
                parallelRecord("one", 2, "user-1-par-2"),
                parallelRecord("two", 2, "user-1-par-2")));
        writer.flushAllBuffers();

        // Two passes, each shared by both of its requests: the shape a duration query needs.
        assertEquals("2 4", query(
                "SELECT count(DISTINCT c->>'execution') || ' ' || count(*) "
                + "FROM requests_raw, jsonb_array_elements(parent_controllers) c "
                + "WHERE c->>'class' = 'org.apache.jmeter.control.ParallelController'"));
    }

    @Test
    void degradesGracefullyWhenColumnAbsent() throws Exception {
        runMigrations(false); // no V004 -> column missing, as on an un-migrated database
        writer = new TimescaleDBWriter(config());
        assertFalse(writer.isParentControllersCaptureEnabled());

        // The insert must still succeed with the column simply omitted.
        writer.writeAllRequestRaw(List.of(
                parallelRecord("grouped", 1, "user-1-par-1"),
                rawRecord("sequential", null)));
        writer.flushAllBuffers();

        assertEquals("2", query("SELECT count(*)::text FROM requests_raw"),
                "Requests must still be recorded without the column");
    }
}
