package io.perfana.jmeter.timescaledb.util;

import org.apache.jmeter.samplers.SampleResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries a per-sample session-variable snapshot from the sampler thread
 * ({@code createSampleResult}) to the listener worker thread ({@code handleSampleResults}).
 *
 * <p>Keyed on {@link SampleResult} identity ({@code SampleResult} does not override
 * {@code equals}/{@code hashCode}). Only failed samples ever populate it, and the worker
 * removes each entry on lookup, so it normally self-cleans. A bounded eldest-eviction
 * policy caps memory if some entries never reach the worker (e.g. dropped phantom samples).
 *
 * <p>Thread-safe: all access is synchronized on the backing map.
 */
public final class SessionVariableCarrier {

    private final Map<SampleResult, Map<String, String>> store;

    public SessionVariableCarrier(int maxEntries) {
        this.store = Collections.synchronizedMap(
                new LinkedHashMap<SampleResult, Map<String, String>>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<SampleResult, Map<String, String>> eldest) {
                        return size() > maxEntries;
                    }
                });
    }

    /** Stashes a snapshot for the given top-level result. No-op when snapshot is null. */
    public void put(SampleResult key, Map<String, String> snapshot) {
        if (snapshot == null) {
            return;
        }
        store.put(key, snapshot);
    }

    /** Removes and returns the snapshot for the given result, or null if absent. */
    public Map<String, String> removeAndGet(SampleResult key) {
        return store.remove(key);
    }

    public int size() {
        return store.size();
    }
}
