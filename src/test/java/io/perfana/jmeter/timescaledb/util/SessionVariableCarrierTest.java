package io.perfana.jmeter.timescaledb.util;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionVariableCarrierTest {

    @Test
    void putThenRemoveReturnsSnapshotByIdentity() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, Map.of("a", "b"));
        assertEquals(Map.of("a", "b"), carrier.removeAndGet(key));
    }

    @Test
    void removeIsOneShot() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, Map.of("a", "b"));
        carrier.removeAndGet(key);
        assertNull(carrier.removeAndGet(key));
        assertEquals(0, carrier.size());
    }

    @Test
    void unknownKeyReturnsNull() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        assertNull(carrier.removeAndGet(new SampleResult()));
    }

    @Test
    void distinctSampleResultsAreDistinctKeysEvenWhenEqualByContent() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult a = new SampleResult();
        SampleResult b = new SampleResult();
        carrier.put(a, Map.of("k", "1"));
        carrier.put(b, Map.of("k", "2"));
        assertEquals(Map.of("k", "1"), carrier.removeAndGet(a));
        assertEquals(Map.of("k", "2"), carrier.removeAndGet(b));
    }

    @Test
    void evictsEldestBeyondCapacity() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(2);
        SampleResult first = new SampleResult();
        SampleResult second = new SampleResult();
        SampleResult third = new SampleResult();
        carrier.put(first, Map.of("n", "1"));
        carrier.put(second, Map.of("n", "2"));
        carrier.put(third, Map.of("n", "3")); // evicts "first"
        assertEquals(2, carrier.size());
        assertNull(carrier.removeAndGet(first));
        assertEquals(Map.of("n", "2"), carrier.removeAndGet(second));
        assertEquals(Map.of("n", "3"), carrier.removeAndGet(third));
    }

    @Test
    void toleratesNullSnapshotWithoutStoring() {
        SessionVariableCarrier carrier = new SessionVariableCarrier(10);
        SampleResult key = new SampleResult();
        carrier.put(key, null);
        assertTrue(carrier.size() == 0);
        assertNull(carrier.removeAndGet(key));
    }
}
