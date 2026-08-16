package io.perfana.jmeter.timescaledb.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plugin is compiled against stock Apache JMeter, which has no controller tagging, and must
 * keep working on BreakTest builds that predate it or run with the property off. These test doubles
 * mirror the BreakTest API shape ({@code SampleResult.ParentControllerExecution}) rather than
 * importing it, so both paths stay pinned.
 */
class ParentControllerTagTest {

    /** Same shape as BreakTest's SampleResult.ParentControllerExecution record. */
    public record Execution(String name, String className, int iteration) {
    }

    /** Stands in for a BreakTest SampleResult carrying the controller chain. */
    public static class TaggedSampleResult extends SampleResult {
        private static final long serialVersionUID = 1L;

        private final List<Execution> chain;

        public TaggedSampleResult(List<Execution> chain) {
            this.chain = chain;
        }

        public List<Execution> getParentControllerExecutions() {
            return chain;
        }
    }

    /** Stands in for a sample that also ran inside a Parallel Controller. */
    public static class ParallelSampleResult extends TaggedSampleResult {
        private static final long serialVersionUID = 1L;

        private final String parallelGroup;
        private final String parallelGroupExecution;

        public ParallelSampleResult(List<Execution> chain, String group, String execution) {
            super(chain);
            this.parallelGroup = group;
            this.parallelGroupExecution = execution;
        }

        public String getParallelGroup() {
            return parallelGroup;
        }

        public String getParallelGroupExecution() {
            return parallelGroupExecution;
        }
    }

    private static final Execution THREAD_GROUP =
            new Execution("Thread Group", "org.apache.jmeter.threads.ThreadGroup", -1);
    private static final Execution LOOP =
            new Execution("loop", "org.apache.jmeter.control.LoopController", 2);
    private static final Execution PARALLEL =
            new Execution("par", "org.apache.jmeter.control.ParallelController", 1);

    @BeforeEach
    void resetCache() {
        ParentControllerTag.clearCache();
    }

    @Test
    void returnsNullOnAnEngineWithoutTheTag() {
        // Stock SampleResult: the method does not exist. This is the older JMeter/BreakTest case.
        assertNull(ParentControllerTag.toJson(new SampleResult()));
        assertFalse(ParentControllerTag.isSupported(),
                "Stock Apache JMeter on the test classpath must report the tag as unsupported");
    }

    @Test
    void returnsNullWhenTheEngineTagsNothing() {
        // The property is off: the method exists but hands back an empty list. NULL, not "[]",
        // so the column stays empty rather than filling with meaningless arrays.
        assertNull(ParentControllerTag.toJson(new TaggedSampleResult(List.of())));
        assertNull(ParentControllerTag.toJson(null));
    }

    @Test
    void serialisesTheChainOutermostFirst() {
        String json = ParentControllerTag.toJson(new TaggedSampleResult(List.of(THREAD_GROUP, LOOP)));

        assertEquals("[{\"name\":\"Thread Group\",\"class\":\"org.apache.jmeter.threads.ThreadGroup\","
                        + "\"iteration\":-1},"
                        + "{\"name\":\"loop\",\"class\":\"org.apache.jmeter.control.LoopController\","
                        + "\"iteration\":2}]",
                json);
    }

    @Test
    void putsTheExecutionIdOnTheParallelControllerEntry() {
        String json = ParentControllerTag.toJson(new ParallelSampleResult(
                List.of(THREAD_GROUP, LOOP, PARALLEL), "par", "Thread Group 1-3-par-1"));

        assertTrue(json.endsWith("{\"name\":\"par\",\"class\":\"org.apache.jmeter.control.ParallelController\","
                        + "\"iteration\":1,\"execution\":\"Thread Group 1-3-par-1\"}]"),
                "the pass id belongs to the parallel entry, not the sample: " + json);
        assertFalse(json.contains("\"name\":\"loop\",\"class\":\"org.apache.jmeter.control.LoopController\","
                        + "\"iteration\":2,\"execution\""),
                "no other entry may carry it");
    }

    @Test
    void omitsTheExecutionIdWhenTheSampleRanSequentially() {
        String json = ParentControllerTag.toJson(
                new ParallelSampleResult(List.of(THREAD_GROUP, LOOP), "", ""));

        assertFalse(json.contains("execution"), json);
    }

    @Test
    void escapesNamesSoAQuoteCannotBreakTheJson() {
        String json = ParentControllerTag.toJson(new TaggedSampleResult(
                List.of(new Execution("say \"hi\"\n", "C", 0))));

        assertEquals("[{\"name\":\"say \\\"hi\\\"\\n\",\"class\":\"C\",\"iteration\":0}]", json);
    }
}
