package io.perfana.jmeter.timescaledb;

import io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.SampleNames;
import org.junit.jupiter.api.Test;

import static io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient.isDetachedFromTransactionController;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a plan that uses Transaction Controllers, a leaf sampler whose getParent() chain broke
 * reaches the listener with no TC ancestor and resolves to the standalone (label, label)
 * shape. Its TC already produced the transaction row, and writing the raw row that way makes
 * Perfana show it as a separate one-bucket metric (seen on SONAR-00010/00014: two rows per
 * sampler in the last seconds of the run, each becoming an ADAPT "regression" on one sample).
 * Such leaves are dropped; plans without any Transaction Controller are untouched.
 */
class DetachedLeafTest {

    @Test
    void standaloneShapeInATransactionControllerPlanIsDropped() {
        SampleNames names = new SampleNames("AG_04_Nieuwe_afspraak_01-0", "AG_04_Nieuwe_afspraak_01-0", true);
        assertTrue(isDetachedFromTransactionController(names, true));
    }

    @Test
    void standaloneShapeInAPlanWithoutTransactionControllersIsKept() {
        SampleNames names = new SampleNames("GET /login", "GET /login", true);
        assertFalse(isDetachedFromTransactionController(names, false),
                "an unwrapped plan never latches planUsesTransactionControllers, so nothing is dropped");
    }

    @Test
    void attributedLeafIsKeptEvenWhenItsLabelEqualsItsTransactionController() {
        // A TC "Login" whose only child is also labelled "Login" resolves through the parent
        // chain, so it is attributed — name equality alone must not classify it as detached.
        SampleNames names = new SampleNames("Login", "Login", false);
        assertFalse(isDetachedFromTransactionController(names, true));
    }

    @Test
    void attributedLeafIsKept() {
        SampleNames names = new SampleNames("AG_04_Nieuwe_afspraak", "AG_04_Nieuwe_afspraak_01-0", false);
        assertFalse(isDetachedFromTransactionController(names, true));
    }
}
