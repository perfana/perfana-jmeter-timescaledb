package io.perfana.jmeter.timescaledb;

import io.perfana.jmeter.timescaledb.TransactionRefAttributionTest.FakeTransactionRef;
import io.perfana.jmeter.timescaledb.TransactionRefAttributionTest.LinkedSampleResult;
import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the whole listener over the sample stream BreakTest 2026.09.25 produces, because the
 * failure this replaces was silent: every leaf arrives unnested, the parent-chain walk called it
 * standalone, the drop rule added in 1.5.0 discarded it, and {@code requests_raw} simply stayed
 * empty while the transactions table looked healthy. Asserting the rows is the only check that
 * notices that.
 *
 * <p>Samplers are sent before their transaction sample, as the engine sends them: the transaction
 * only completes once its last sampler has.
 */
@Testcontainers
class TransactionStreamIntegrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jmeter")
            .withUsername("jmeter")
            .withPassword("jmeter");

    @BeforeEach
    void freshSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS url_patterns CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_error CASCADE;");
            st.execute("DROP TABLE IF EXISTS requests_raw CASCADE;");
            st.execute("DROP TABLE IF EXISTS transactions CASCADE;");
            st.execute("DROP TABLE IF EXISTS virtual_users CASCADE;");
            st.execute(Files.readString(Path.of("migrations", "V001__initial_schema.sql"), StandardCharsets.UTF_8));
            st.execute(Files.readString(Path.of("migrations", "V002__add_url_normalization.sql"), StandardCharsets.UTF_8));
        }
    }

    private BackendListenerContext context() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, db.getHost());
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(db.getFirstMappedPort()));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, db.getDatabaseName());
        args.addArgument(TimescaleDBConfig.KEY_USER, db.getUsername());
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, db.getPassword());
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        args.addArgument(TimescaleDBConfig.KEY_RUN_ID, "run-tx");
        args.addArgument(TimescaleDBConfig.KEY_SYSTEM_UNDER_TEST, "PerfanaWebshop");
        args.addArgument(TimescaleDBConfig.KEY_TEST_ENVIRONMENT, "acc");
        args.addArgument(TimescaleDBConfig.KEY_SCENARIO_NAME, "loadTest");
        args.addArgument(TimescaleDBConfig.KEY_NORMALIZE_URLS, "false");
        return new BackendListenerContext(args);
    }

    private static LinkedSampleResult sample(String label) {
        LinkedSampleResult result = new LinkedSampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(true);
        result.sampleStart();
        result.sampleEnd();
        return result;
    }

    private static LinkedSampleResult transactionSample(String label, int samples) {
        LinkedSampleResult result = sample(label);
        result.setResponseMessage(
                "Number of samples in transaction : " + samples + ", number of failing samples : 0");
        return result;
    }

    @Test
    void nestedTransactionPlanWritesEveryRequestAndOneTransactionPerOutermostController() throws Exception {
        FakeTransactionRef checkout = new FakeTransactionRef("Checkout");
        FakeTransactionRef payment = new FakeTransactionRef("Checkout", "Payment");

        JMeterTimescaleDBBackendListenerClient client = new JMeterTimescaleDBBackendListenerClient();
        client.setupTest(context());
        try {
            // Inside the nested transaction, then the transaction samples innermost first, then a
            // sampler that runs outside any transaction.
            client.handleSampleResults(List.of(sample("POST /pay").inside(payment)), context());
            client.handleSampleResults(List.of(sample("GET /receipt").inside(payment)), context());
            client.handleSampleResults(List.of(transactionSample("Payment", 2).inside(checkout)), context());
            client.handleSampleResults(List.of(transactionSample("Checkout", 2)), context());
            client.handleSampleResults(List.of(sample("GET /health")), context());
        } finally {
            client.teardownTest(context());
        }

        assertEquals(
                Map.of("Checkout", List.of("GET /receipt", "POST /pay"),
                        "GET /health", List.of("GET /health")),
                rows("SELECT transaction_name, sampler_name FROM requests_raw "
                        + "ORDER BY transaction_name, sampler_name"),
                "every sampler must be stored under its outermost Transaction Controller, and a "
                        + "sampler outside every transaction under its own name");

        assertEquals(
                Map.of("Checkout", List.of("1"), "GET /health", List.of("1")),
                rows("SELECT transaction_name, count(*)::text FROM transactions "
                        + "GROUP BY transaction_name ORDER BY transaction_name"),
                "the nested transaction is flattened away, and the standalone sampler still gets "
                        + "its own single-step transaction row");
    }

    /** @return the first column mapped to the second, in query order */
    private Map<String, List<String>> rows(String sql) throws Exception {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.computeIfAbsent(rs.getString(1), key -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return rows;
    }

    /** Guards the fixture: the production code must see a linking engine, or this proves nothing. */
    @Test
    void theFixtureLooksLikeAnEngineThatLinksTransactions() {
        SampleResult leaf = sample("POST /pay");
        assertEquals(true, io.perfana.jmeter.timescaledb.util.TransactionRefs.linksTransactions(leaf));
    }
}
