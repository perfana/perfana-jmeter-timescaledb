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
 * The source_element_path column is optional: the plugin must write it as jsonb when the migration
 * has been applied and stay silent when it has not, so an un-migrated database keeps recording
 * requests.
 */
@Testcontainers
class SourceElementPathIntegrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jmeter")
            .withUsername("jmeter")
            .withPassword("jmeter");

    /** Two identically named transactions in different branches: occurrence is what tells them apart. */
    private static final String PATH_TEMPLATE =
            "[{\"name\":\"Shoppers\",\"class\":\"org.apache.jmeter.threads.ThreadGroup\",\"occurrence\":0},"
            + "{\"name\":\"checkout\",\"class\":\"org.apache.jmeter.control.TransactionController\","
            + "\"occurrence\":%d},"
            + "{\"name\":\"%s\",\"class\":\"org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy\","
            + "\"occurrence\":0}]";

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
                st.execute(readMigration("V004__add_source_element_path.sql"));
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
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SOURCE_ELEMENT_PATH, "true");
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    private RequestRawRecord rawRecord(String samplerName, String sourceElementPath) {
        return RequestRawRecord.builder()
                .time(Instant.now())
                .testRunId("run-1")
                .systemUnderTest("sut")
                .testEnvironment("test")
                .transactionName("checkout")
                .samplerName(samplerName)
                .success(true)
                .responseCode("200")
                .responseTime(42)
                .sourceElementPath(sourceElementPath)
                .build();
    }

    private RequestRawRecord pathRecord(String samplerName, int transactionOccurrence) {
        return rawRecord(samplerName, String.format(PATH_TEMPLATE, transactionOccurrence, samplerName));
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
    void storesThePathAsQueryableJsonb() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config());
        assertTrue(writer.isSourceElementPathCaptureEnabled());

        writer.writeAllRequestRaw(List.of(
                pathRecord("cart", 0),
                rawRecord("untagged", null)));
        writer.flushAllBuffers();

        // The UI's breadcrumb: names outermost first.
        assertEquals("Shoppers > checkout > cart", query(
                "SELECT string_agg(e->>'name', ' > ' ORDER BY ord) "
                + "FROM requests_raw, jsonb_array_elements(source_element_path) WITH ORDINALITY AS t(e, ord) "
                + "WHERE sampler_name = 'cart'"));
        assertNull(query("SELECT source_element_path FROM requests_raw WHERE sampler_name = 'untagged'"),
                "An untagged request must be NULL, not an empty array");
    }

    @Test
    void tellsIdenticallyNamedTransactionsApartByOccurrence() throws Exception {
        runMigrations(true);
        writer = new TimescaleDBWriter(config());

        // Same transaction name, same sampler name, different place in the plan.
        writer.writeAllRequestRaw(List.of(pathRecord("cart", 0), pathRecord("cart", 1)));
        writer.flushAllBuffers();

        assertEquals("2", query(
                "SELECT count(DISTINCT e->>'occurrence')::text "
                + "FROM requests_raw, jsonb_array_elements(source_element_path) e "
                + "WHERE e->>'class' = 'org.apache.jmeter.control.TransactionController'"));
    }

    @Test
    void staysOffUntilExplicitlyEnabled() throws Exception {
        runMigrations(true); // column present, but capture not requested
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        writer = new TimescaleDBWriter(TimescaleDBConfig.fromContext(new BackendListenerContext(args)));

        assertFalse(writer.isSourceElementPathCaptureEnabled(), "Capture must be opt-in");

        writer.writeAllRequestRaw(List.of(pathRecord("cart", 0)));
        writer.flushAllBuffers();

        assertNull(query("SELECT source_element_path FROM requests_raw WHERE sampler_name = 'cart'"),
                "Nothing may be written to the column while the toggle is off");
    }

    @Test
    void degradesGracefullyWhenColumnAbsent() throws Exception {
        runMigrations(false); // no V004 -> column missing, as on an un-migrated database
        writer = new TimescaleDBWriter(config());
        assertFalse(writer.isSourceElementPathCaptureEnabled());

        // The insert must still succeed with the column simply omitted.
        writer.writeAllRequestRaw(List.of(pathRecord("cart", 0), rawRecord("untagged", null)));
        writer.flushAllBuffers();

        assertEquals("2", query("SELECT count(*)::text FROM requests_raw"),
                "Requests must still be recorded without the column");
    }
}
