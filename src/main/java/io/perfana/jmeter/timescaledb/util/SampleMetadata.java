package io.perfana.jmeter.timescaledb.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads the optional metadata a BreakTest engine attaches to a {@link SampleResult}.
 *
 * <p>BreakTest lets a listener declare what it needs ({@code SampleResultMetadataConsumer}) and
 * then stamps it on every result before the listener is notified:
 *
 * <ul>
 *   <li>{@code getSourceTestElementPath()} — where in the test plan the sample came from, as
 *       {@code (className, name, occurrence)} entries from the root down to the sampler. The
 *       occurrence tells two identically named siblings apart, which nothing on the result
 *       otherwise can.</li>
 *   <li>{@code getJMeterVariables()} — the thread's variables at notification time, so the
 *       listener's worker thread can read session state it has no other access to.</li>
 * </ul>
 *
 * <p>The plugin compiles against stock Apache JMeter, which has neither, and must keep working on
 * engines that predate them or on a run that did not ask for them. So every accessor is resolved
 * reflectively per class, once, and an engine that supplies nothing simply yields nothing.
 */
public final class SampleMetadata {

    private static final Logger LOGGER = LogManager.getLogger(SampleMetadata.class);

    private static final String PATH_METHOD = "getSourceTestElementPath";
    private static final String VARIABLES_METHOD = "getJMeterVariables";

    /** Keyed by declaring class AND method, because subclasses may declare what the base class lacks. */
    private static final Map<String, Optional<MethodHandle>> HANDLES = new ConcurrentHashMap<>();

    /** Logged once, so an older engine does not produce a warning per sample. */
    private static volatile boolean unsupportedLogged;

    private SampleMetadata() {
    }

    /**
     * @param sampleResult the sample to inspect, may be {@code null}
     * @return the test plan path of this sample as a JSON array, outermost first, or {@code null}
     *         when the engine did not attach one (so the caller writes SQL NULL, never {@code []})
     */
    public static String sourceElementPathJson(SampleResult sampleResult) {
        if (sampleResult == null) {
            return null;
        }
        List<?> path = read(sampleResult, PATH_METHOD, List.class, List.of());
        if (path.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(96).append('[');
        for (int i = 0; i < path.size(); i++) {
            Object entry = path.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":");
            SessionVariablesJson.appendString(sb, read(entry, "name", String.class, ""));
            sb.append(",\"class\":");
            SessionVariablesJson.appendString(sb, read(entry, "className", String.class, ""));
            sb.append(",\"occurrence\":").append(read(entry, "occurrence", Integer.class, 0));
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * The thread variables the engine attached to this sample, as Strings.
     *
     * <p>Preferred over snapshotting on the sampler thread: the engine stamps the same snapshot on
     * every sub-result, so a failing leaf carries its own session state instead of the listener
     * having to walk a parent chain that is not reliably intact.
     *
     * @param sampleResult the sample to inspect, may be {@code null}
     * @return the variables, or an empty map when the engine attached none
     */
    public static Map<String, String> jmeterVariables(SampleResult sampleResult) {
        if (sampleResult == null) {
            return Collections.emptyMap();
        }
        Map<?, ?> variables = read(sampleResult, VARIABLES_METHOD, Map.class, Map.of());
        if (variables.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>(variables.size());
        for (Map.Entry<?, ?> entry : variables.entrySet()) {
            if (entry.getKey() != null) {
                Object value = entry.getValue();
                result.put(entry.getKey().toString(), value == null ? null : value.toString());
            }
        }
        return result;
    }

    /** @return whether the running engine can attach the test plan path at all */
    public static boolean isSourceElementPathSupported() {
        return resolve(SampleResult.class, PATH_METHOD, List.class).isPresent();
    }

    /** @return whether the running engine can attach the thread variables at all */
    public static boolean isJMeterVariablesSupported() {
        return resolve(SampleResult.class, VARIABLES_METHOD, Map.class).isPresent();
    }

    private static <T> T read(Object target, String method, Class<T> type, T fallback) {
        Optional<MethodHandle> handle = HANDLES.computeIfAbsent(
                target.getClass().getName() + '#' + method,
                key -> resolve(target.getClass(), method, type));
        if (handle.isEmpty()) {
            return fallback;
        }
        try {
            Object value = handle.get().invoke(target);
            return value == null ? fallback : type.cast(value);
        } catch (Throwable t) { // NOSONAR - a reflective call must never break result handling
            LOGGER.debug("Could not read {} from {}: {}",
                    method, target.getClass().getName(), t.toString());
            return fallback;
        }
    }

    private static Optional<MethodHandle> resolve(Class<?> owner, String method, Class<?> returnType) {
        try {
            Class<?> declared = returnType == Integer.class ? int.class : returnType;
            return Optional.of(MethodHandles.publicLookup()
                    .findVirtual(owner, method, MethodType.methodType(declared)));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            if (PATH_METHOD.equals(method) && !unsupportedLogged) {
                unsupportedLogged = true;
                LOGGER.info("This engine does not expose SampleResult.{}(); requests_raw." +
                        "source_element_path stays empty for this run. Requires a BreakTest build " +
                        "with listener sample metadata.", method);
            }
            return Optional.empty();
        }
    }

    /** Visible for testing: forget resolved accessors so a test can exercise both paths. */
    static void clearCache() {
        HANDLES.clear();
    }
}
