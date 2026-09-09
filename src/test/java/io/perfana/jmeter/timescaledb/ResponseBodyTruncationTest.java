package io.perfana.jmeter.timescaledb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseBodyTruncationTest {

    @Test
    void keepsShortBodiesVerbatim() {
        assertNull(JMeterTimescaleDBBackendListenerClient.truncateBody(null, 100));
        assertEquals("boom", JMeterTimescaleDBBackendListenerClient.truncateBody("boom", 100));
        // Exactly at the limit is not truncated.
        assertEquals("abcd", JMeterTimescaleDBBackendListenerClient.truncateBody("abcd", 4));
    }

    @Test
    void keepsTheHeadAndSaysWhatWasDropped() {
        String body = "S".repeat(100);

        String truncated = JMeterTimescaleDBBackendListenerClient.truncateBody(body, 10);

        assertTrue(truncated.startsWith("S".repeat(10)), "The head carries the stack trace");
        assertTrue(truncated.endsWith("...[truncated 90 chars]"), "was: " + truncated);
    }

    @Test
    void zeroKeepsNothingButStillReportsTheSize() {
        assertEquals("...[truncated 5 chars]",
                JMeterTimescaleDBBackendListenerClient.truncateBody("12345", 0));
    }
}
