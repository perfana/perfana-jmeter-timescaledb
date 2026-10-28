package io.perfana.jmeter.timescaledb.util;

import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Reads the name of the Thread Group a sample ran in.
 *
 * <p>Nothing on a {@link SampleResult} names the thread group directly, and the listener's worker
 * thread cannot ask {@code JMeterContextService} either: it is not the sampler thread, so the
 * context there belongs to no thread group at all. What every sample does carry is the thread name,
 * which JMeter composes as {@code <thread group name> <group index>-<thread index>} (for example
 * {@code Zoeken 1-2}), including on a Transaction Controller's own sample: the engine stamps it in
 * {@code fillThreadInformation} before notifying listeners.
 *
 * <p>Peeling that suffix off is therefore the only way to group rows per thread group without an
 * engine-side addition, and it is exactly what {@code SampleEvent} does with its own thread group
 * field, which {@code BackendListener} does not pass on to a client.
 */
public final class ThreadGroupNames {

    /**
     * The {@code <group>-<thread>} tail JMeter appends, anchored at the end and preceded by the one
     * space JMeter puts there. Thread group names may themselves contain spaces and digits, so only
     * this exact trailing shape is removed.
     */
    private static final Pattern THREAD_SUFFIX = Pattern.compile(" \\d+-\\d+$");

    private ThreadGroupNames() {
    }

    /** Only the per-thread part, leaving the thread group's own index in place. */
    private static final Pattern THREAD_INDEX = Pattern.compile("-\\d+$");

    /**
     * @param sample the sample to inspect, may be {@code null}
     * @return the thread group name, or {@code null} when the thread name is absent or does not
     *         carry the expected suffix, in which case the caller keeps the unsuffixed scenario
     *         name rather than inventing a group
     */
    public static String of(SampleResult sample) {
        return sample == null ? null : fromThreadName(sample.getThreadName());
    }

    /**
     * Identifies one thread group within a run, as opposed to {@link #of(SampleResult)} which names
     * it. Nothing stops a plan from giving two thread groups the same name, JMeter's default
     * {@code Thread Group} most of all, and then the name alone cannot tell their thread counts
     * apart. JMeter's thread name keeps the thread group's own index, so {@code Thread Group 1-2}
     * and {@code Thread Group 2-1} yield the distinct keys {@code Thread Group 1} and
     * {@code Thread Group 2}.
     *
     * @param sample the sample to inspect, may be {@code null}
     * @return a key unique to this sample's thread group, or {@code null} when the thread name does
     *         not carry the expected suffix
     */
    public static String keyOf(SampleResult sample) {
        if (sample == null || fromThreadName(sample.getThreadName()) == null) {
            return null;
        }
        return THREAD_INDEX.matcher(sample.getThreadName()).replaceFirst("").trim();
    }

    /**
     * @param key a key from {@link #keyOf(SampleResult)}, such as {@code Thread Group 2}
     * @return the thread group name it belongs to, such as {@code Thread Group}
     */
    public static String nameOfKey(String key) {
        if (key == null) {
            return null;
        }
        int space = key.lastIndexOf(' ');
        return space <= 0 ? key : key.substring(0, space);
    }

    /**
     * @param threadName a JMeter thread name such as {@code Zoeken 1-2}
     * @return the thread group name, or {@code null} when the name does not carry the suffix
     */
    static String fromThreadName(String threadName) {
        if (threadName == null || threadName.isEmpty()) {
            return null;
        }
        String group = THREAD_SUFFIX.matcher(threadName).replaceFirst("").trim();
        // No suffix means this is not a sampler thread name (a renamed thread, or an engine that
        // composes it differently). Guessing would put rows under a scenario that does not exist.
        return group.isEmpty() || group.length() == threadName.length() ? null : group;
    }

    /**
     * Appends the thread group to a scenario name, so two thread groups that share a transaction
     * name stop collapsing into one metric.
     *
     * @param scenarioName the configured scenario name
     * @param threadGroup  the thread group name, or {@code null} when it could not be determined
     * @return the scenario name for this sample's rows
     */
    public static String scenarioNameFor(String scenarioName, String threadGroup) {
        if (threadGroup == null || threadGroup.isEmpty()) {
            return scenarioName;
        }
        if (scenarioName == null || scenarioName.isEmpty()) {
            return threadGroup;
        }
        // Already suffixed: a thread group named after its scenario must not yield "Zoeken_Zoeken".
        if (scenarioName.equals(threadGroup)) {
            return scenarioName;
        }
        return scenarioName + SEPARATOR + threadGroup;
    }

    /** Underscore, as in {@code browseProducts_Zoeken}: legible in a Perfana metric name. */
    private static final String SEPARATOR = "_";
}
