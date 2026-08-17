package io.perfana.jmeter.timescaledb.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plugin is compiled against stock Apache JMeter, which attaches no sample metadata, and must
 * keep working on BreakTest builds that predate it or on a run that did not ask for it. These test
 * doubles mirror the BreakTest API shape ({@code SampleResult.TestElementPathEntry},
 * {@code getJMeterVariables()}) rather than importing it, so both paths stay pinned.
 */
class SampleMetadataTest {

    /** Same shape as BreakTest's SampleResult.TestElementPathEntry record. */
    public record PathEntry(String className, String name, int occurrence) {
    }

    /** Stands in for a BreakTest SampleResult carrying the test plan path. */
    public static class PathTaggedSampleResult extends SampleResult {
        private static final long serialVersionUID = 1L;

        private final List<PathEntry> path;

        public PathTaggedSampleResult(List<PathEntry> path) {
            this.path = path;
        }

        public List<PathEntry> getSourceTestElementPath() {
            return path;
        }
    }

    /** Stands in for a BreakTest SampleResult carrying the thread variables. */
    public static class VariableTaggedSampleResult extends SampleResult {
        private static final long serialVersionUID = 1L;

        private final Map<String, Object> variables;

        public VariableTaggedSampleResult(Map<String, Object> variables) {
            this.variables = variables;
        }

        public Map<String, Object> getJMeterVariables() {
            return variables;
        }
    }

    private static final PathEntry GROUP =
            new PathEntry("org.apache.jmeter.threads.ThreadGroup", "Shoppers", 0);
    private static final PathEntry TRANSACTION =
            new PathEntry("org.apache.jmeter.control.TransactionController", "T02_Checkout", 1);

    @BeforeEach
    void resetCache() {
        SampleMetadata.clearCache();
    }

    @Test
    void yieldsNothingOnAnEngineWithoutMetadata() {
        // Stock SampleResult: neither method exists. This is the older JMeter/BreakTest case.
        assertNull(SampleMetadata.sourceElementPathJson(new SampleResult()));
        assertTrue(SampleMetadata.jmeterVariables(new SampleResult()).isEmpty());
        assertFalse(SampleMetadata.isSourceElementPathSupported(),
                "Stock Apache JMeter on the test classpath must report the path as unsupported");
        assertFalse(SampleMetadata.isJMeterVariablesSupported());
    }

    @Test
    void yieldsNothingWhenTheListenerDidNotAskForMetadata() {
        // The methods exist but the engine attached nothing. NULL, not "[]", so the column stays
        // empty rather than filling with meaningless arrays.
        assertNull(SampleMetadata.sourceElementPathJson(new PathTaggedSampleResult(List.of())));
        assertNull(SampleMetadata.sourceElementPathJson(null));
        assertTrue(SampleMetadata.jmeterVariables(new VariableTaggedSampleResult(Map.of())).isEmpty());
        assertTrue(SampleMetadata.jmeterVariables(null).isEmpty());
    }

    @Test
    void serialisesThePathOutermostFirst() {
        String json = SampleMetadata.sourceElementPathJson(
                new PathTaggedSampleResult(List.of(GROUP, TRANSACTION)));

        assertEquals("[{\"name\":\"Shoppers\",\"class\":\"org.apache.jmeter.threads.ThreadGroup\","
                        + "\"occurrence\":0},"
                        + "{\"name\":\"T02_Checkout\",\"class\":\"org.apache.jmeter.control.TransactionController\","
                        + "\"occurrence\":1}]",
                json);
    }

    @Test
    void escapesNamesSoAQuoteCannotBreakTheJson() {
        String json = SampleMetadata.sourceElementPathJson(
                new PathTaggedSampleResult(List.of(new PathEntry("C", "say \"hi\"\n", 0))));

        assertEquals("[{\"name\":\"say \\\"hi\\\"\\n\",\"class\":\"C\",\"occurrence\":0}]", json);
    }

    @Test
    void readsVariablesAsStringsPreservingOrder() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("cartId", "c-8841");
        variables.put("itemCount", 3); // JMeter variables hold Objects, not only Strings
        variables.put("missing", null);

        Map<String, String> read = SampleMetadata.jmeterVariables(
                new VariableTaggedSampleResult(variables));

        assertEquals(List.of("cartId", "itemCount", "missing"), List.copyOf(read.keySet()));
        assertEquals("c-8841", read.get("cartId"));
        assertEquals("3", read.get("itemCount"));
        assertNull(read.get("missing"));
    }
}
