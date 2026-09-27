package io.perfana.jmeter.timescaledb;

import io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.SampleNames;
import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import io.perfana.jmeter.timescaledb.model.TransactionRecord;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.determineNames;
import static io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.isDetachedFromTransactionController;
import static io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.isNestedTransaction;
import static io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.standaloneTransactionRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BreakTest 2026.09.25 removed the Transaction Controller's "Generate parent sample" mode: a
 * sampler result is delivered on its own, linked to its transaction by
 * {@code SampleResult.getParentTransaction()}, and the transaction sample arrives last with no
 * sub-results. Every leaf therefore reaches the listener with {@code getParent() == null}, which
 * the parent-chain attribution reads as "standalone" — and the drop rule from 1.5.0 then discards
 * it. These tests cover the reference-based path that replaces that walk.
 *
 * <p>The plugin compiles against stock Apache JMeter, which has neither the accessor nor the
 * {@code TransactionRef} type, so the engine is stood in for by a {@link SampleResult} subclass and
 * a reference class that carry the same method names. That is exactly what the production code
 * reads: the accessors are resolved by name, never by type.
 */
public class TransactionRefAttributionTest {

    /** Stands in for BreakTest's {@code TransactionRef}, outermost transaction name first. */
    public static final class FakeTransactionRef {
        private final List<String> path;

        FakeTransactionRef(String... names) {
            this.path = List.of(names);
        }

        public List<String> getPath() {
            return path;
        }
    }

    /** Stands in for a {@link SampleResult} on an engine that links samples to their transaction. */
    public static class LinkedSampleResult extends SampleResult {
        private FakeTransactionRef parentTransaction;

        public FakeTransactionRef getParentTransaction() {
            return parentTransaction;
        }

        LinkedSampleResult inside(FakeTransactionRef ref) {
            this.parentTransaction = ref;
            return this;
        }
    }

    private static LinkedSampleResult sample(String label) {
        LinkedSampleResult result = new LinkedSampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(true);
        result.sampleStart();
        result.sampleEnd();
        return result;
    }

    private static TimescaleDBConfig config() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_RUN_ID, "run-9");
        args.addArgument(TimescaleDBConfig.KEY_SYSTEM_UNDER_TEST, "PerfanaWebshop");
        args.addArgument(TimescaleDBConfig.KEY_TEST_ENVIRONMENT, "acc");
        args.addArgument(TimescaleDBConfig.KEY_SCENARIO_NAME, "loadTest");
        args.addArgument(TimescaleDBConfig.KEY_LOCATION, "local");
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }

    @Test
    void flattenedNamesTheOutermostTransaction() {
        SampleResult leaf = sample("POST /pay").inside(new FakeTransactionRef("Checkout", "Payment"));

        SampleNames names = determineNames(leaf, true);

        assertEquals("Checkout", names.transactionName);
        assertEquals("POST /pay", names.samplerName);
        assertFalse(names.standalone);
    }

    @Test
    void unflattenedNamesTheInnermostTransaction() {
        SampleResult leaf = sample("POST /pay").inside(new FakeTransactionRef("Checkout", "Payment"));

        SampleNames names = determineNames(leaf, false);

        assertEquals("Payment", names.transactionName);
        assertEquals("POST /pay", names.samplerName);
    }

    @Test
    void samplerOutsideAnyTransactionIsStandaloneAndSurvivesAPlanFullOfTransactionControllers() {
        SampleResult leaf = sample("GET /health");

        SampleNames names = determineNames(leaf, true);

        assertTrue(names.standalone);
        assertEquals("GET /health", names.transactionName);
        // The 1.5.0 drop rule exists because a parent-chain walk cannot tell a broken link from a
        // genuine standalone. The reference can, so the leaf is kept and gets its own transaction
        // row even though the run has seen Transaction Controller samples.
        assertFalse(isDetachedFromTransactionController(names, true, true));
        assertNotNull(standaloneTransactionRecord(leaf, names.transactionName, config(), true, true),
                "a sampler the engine says ran outside any transaction must still produce a transaction row");
    }

    @Test
    void attributedSamplerProducesNoStandaloneTransactionRow() {
        SampleResult leaf = sample("POST /pay").inside(new FakeTransactionRef("Checkout"));

        assertNull(standaloneTransactionRecord(leaf, "Checkout", config(), false, true),
                "the Transaction Controller already produced the transaction row");
    }

    @Test
    void nestedTransactionSampleIsFlattenedAwayAndTheOutermostIsKept() {
        // On a transaction sample the reference is the ENCLOSING transaction, so a non-null one is
        // the nesting test itself.
        SampleResult inner = sample("Payment").inside(new FakeTransactionRef("Checkout"));
        SampleResult outer = sample("Checkout");

        assertTrue(isNestedTransaction(inner));
        assertFalse(isNestedTransaction(outer));
    }

    @Test
    void embeddedResourceInheritsTheTransactionOfItsSampler() {
        // Only the top-level result of a sampler is stamped (JMeterThread), so an embedded resource
        // or redirect step is attributed through the sampler that owns it. Built in engine order,
        // sub-result first, because SampleResult's copy constructor propagates the reference: build
        // it after stamping the owner and it would inherit one, hiding the walk this test covers.
        LinkedSampleResult image = sample("GET /logo.png");
        LinkedSampleResult page = sample("GET /checkout");
        image.setParent(page);
        page.inside(new FakeTransactionRef("Checkout", "Payment"));

        assertNull(image.getParentTransaction(),
                "test setup: a sub-result carries no reference of its own");

        SampleNames names = determineNames(image, true);

        assertEquals("Checkout", names.transactionName);
        assertEquals("GET /logo.png", names.samplerName);
        assertFalse(names.standalone);
    }

    @Test
    void explicitSeparatorLabelStillWins() {
        SampleResult leaf = sample("Checkout::POST /pay");

        SampleNames names = determineNames(leaf, true);

        assertEquals("Checkout", names.transactionName);
        assertEquals("POST /pay", names.samplerName);
        // Not inside a transaction as far as the engine is concerned, so the row the "::" label
        // names is still the only place this sampler is counted.
        assertNotNull(standaloneTransactionRecord(leaf, names.transactionName, config(), true, true));
    }
}
