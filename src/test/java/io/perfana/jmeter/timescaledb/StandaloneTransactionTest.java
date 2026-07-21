package io.perfana.jmeter.timescaledb;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.TransactionRecord;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JMeter plan with no Transaction Controllers must still populate the transactions
 * table — each sampler is recorded as its own single-step transaction — so Perfana's
 * Performance Analysis view has data.
 */
class StandaloneTransactionTest {

    private static TimescaleDBConfig config() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_RUN_ID, "run-7");
        args.addArgument(TimescaleDBConfig.KEY_SYSTEM_UNDER_TEST, "PerfanaWebshop");
        args.addArgument(TimescaleDBConfig.KEY_TEST_ENVIRONMENT, "acc");
        args.addArgument(TimescaleDBConfig.KEY_SCENARIO_NAME, "loadTest");
        args.addArgument(TimescaleDBConfig.KEY_LOCATION, "local");
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    private static SampleResult sampler(String label, long elapsedMs, boolean ok) {
        SampleResult r = SampleResult.createTestSample(elapsedMs);
        r.setSampleLabel(label);
        r.setSuccessful(ok);
        return r;
    }

    @Test
    void standaloneSamplerBecomesItsOwnTransaction() {
        SampleResult login = sampler("GET /login", 123, true);

        TransactionRecord record = JMeterTimescaleDBBackendListenerClient
                .standaloneTransactionRecord(login, login.getSampleLabel(), config(), false);

        assertNotNull(record, "a sampler with no Transaction Controller must produce a transaction row");
        assertEquals("GET /login", record.getTransactionName());
        assertEquals(123, record.getResponseTime());
        assertTrue(record.isSuccess());
        assertEquals("run-7", record.getTestRunId());
        assertEquals("PerfanaWebshop", record.getSystemUnderTest());
    }

    @Test
    void everySamplerInATransactionControllerlessPlanProducesOneTransactionRow() {
        SampleResult[] plan = {
                sampler("GET /home", 10, true),
                sampler("GET /search", 20, true),
                sampler("POST /cart", 30, false),
        };

        long transactionRows = Arrays.stream(plan)
                .map(s -> JMeterTimescaleDBBackendListenerClient
                        .standaloneTransactionRecord(s, s.getSampleLabel(), config(), false))
                .filter(Objects::nonNull)
                .count();

        assertEquals(plan.length, transactionRows,
                "each sampler in a Transaction-Controller-less plan must yield one transaction row");
    }

    @Test
    void samplerUnderTransactionControllerIsNotDoubleCounted() {
        SampleResult tc = SampleResult.createTestSample(40);
        tc.setSampleLabel("Checkout");
        tc.setResponseMessage("Number of samples in transaction : 1, number of failing samples : 0");

        SampleResult child = sampler("POST /checkout", 40, true);
        child.setParent(tc);

        TransactionRecord record = JMeterTimescaleDBBackendListenerClient
                .standaloneTransactionRecord(child, child.getSampleLabel(), config(), false);

        assertNull(record, "a sampler nested under a Transaction Controller must not emit its own transaction row");
    }

    @Test
    void samplerDetachedFromItsTransactionControllerIsNotFabricatedIntoATransaction() {
        // Reproduces the MijnDoc-00002 leak: a plan that uses Transaction Controllers, but a
        // child sampler reaches the listener with a broken getParent() chain (getParent() == null),
        // so hasTransactionAncestor() wrongly reports "standalone". With planUsesTransactionControllers
        // latched, we must not fabricate a transaction row for it.
        SampleResult orphanChild = sampler("MijnDoc_05_Mijn_documenten_02_GET_/mijn-documenten", 40, true);
        assertNull(orphanChild.getParent(), "test setup: parent chain is broken");

        TransactionRecord record = JMeterTimescaleDBBackendListenerClient
                .standaloneTransactionRecord(orphanChild, orphanChild.getSampleLabel(), config(), true);

        assertNull(record,
                "in a plan that uses Transaction Controllers, a child with a broken parent chain "
                        + "must not be fabricated into its own transaction");
    }
}
