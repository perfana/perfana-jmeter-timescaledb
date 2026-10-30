package io.perfana.jmeter.timescaledb.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;

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
        String name = space <= 0 ? key : key.substring(0, space);
        // The key keeps the generator's address so two engines running the same thread group are
        // counted separately; the name it maps to must not, or the virtual users rows would carry a
        // scenario name the request rows never use.
        return withoutRemoteHostPrefix(name, remoteHosts());
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
        if (group.isEmpty() || group.length() == threadName.length()) {
            return null;
        }
        return withoutRemoteHostPrefix(group, remoteHosts());
    }

    /**
     * Removes the load generator's address that a distributed run puts in front of the thread group
     * name: on a remote engine the thread name is {@code <host>-<thread group> <group>-<thread>},
     * for instance {@code 10.0.0.1:1099-Zoeken 1-2}. Left in, one thread group would become a
     * different scenario on every generator, so a four-generator run would split one metric four
     * ways. Which generator a sample came from is already in {@code node_name} and {@code location}.
     *
     * <p>Matched against the configured hosts rather than cut at the first dash, so a thread group
     * legitimately named {@code order-entry} keeps its name.
     *
     * @param group       the thread group name, possibly prefixed
     * @param remoteHosts the configured remote engines, exactly as JMeter was given them
     */
    static String withoutRemoteHostPrefix(String group, List<String> remoteHosts) {
        for (String host : remoteHosts) {
            // The two spellings disagree: JMeter drops the port from remote_hosts but keeps it in
            // the thread name, so "remote_hosts=10.0.0.1" has to match the prefix
            // "10.0.0.1:1099-". Matched with the port optional on either side.
            String address = hostWithoutPort(host);
            Matcher prefix = Pattern
                    .compile("^" + Pattern.quote(address) + "(:\\d+)?-")
                    .matcher(group);
            if (prefix.find() && prefix.end() < group.length()) {
                return group.substring(prefix.end());
            }
        }
        return group;
    }

    private static String hostWithoutPort(String host) {
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    /**
     * The {@code remote_hosts} property, which {@code -R} sets and which holds the engine addresses
     * in the same form the thread name prefix uses, port included. Read once: it is fixed for the
     * run, and this is on the path of every sample.
     */
    private static List<String> remoteHosts() {
        List<String> hosts = remoteHosts;
        if (hosts == null) {
            hosts = parseHosts(JMeterUtils.getPropDefault("remote_hosts", ""));
            remoteHosts = hosts;
        }
        return hosts;
    }

    static List<String> parseHosts(String property) {
        if (property == null || property.isBlank()) {
            return List.of();
        }
        List<String> hosts = new ArrayList<>();
        for (String host : property.split(",")) {
            String trimmed = host.trim();
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return List.copyOf(hosts);
    }

    private static volatile List<String> remoteHosts;

    /** Visible for testing: forget the cached host list so a test can set the property. */
    static void clearRemoteHostsCache() {
        remoteHosts = null;
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
