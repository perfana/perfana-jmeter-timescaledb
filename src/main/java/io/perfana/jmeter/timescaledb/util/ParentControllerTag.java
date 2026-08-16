package io.perfana.jmeter.timescaledb.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Serialises the controllers a sample ran under to the JSON stored in
 * {@code requests_raw.parent_controllers}.
 *
 * <p>BreakTest stamps every result with its enclosing controllers, outermost first, each with the
 * name, the class and the iteration that controller was executing
 * ({@code SampleResult#getParentControllerExecutions()}, behind {@code sampleresult.parent_controllers}).
 * That is the runtime half of the sample's ancestry: which loop pass, which foreach element, which
 * concurrent branch produced this request. The Parallel Controller entry additionally carries the
 * pass id the engine hands out, because a parallel branch runs on cloned controllers whose own
 * iteration counts cannot identify the pass.
 *
 * <p>The plugin compiles against stock Apache JMeter, which has none of this, and must keep working
 * on BreakTest builds that predate it or run with the property off. So every accessor is resolved
 * reflectively per class, once, and a sample the engine did not tag simply produces no JSON.
 */
public final class ParentControllerTag {

    private static final Logger LOGGER = LogManager.getLogger(ParentControllerTag.class);

    private static final String CHAIN_METHOD = "getParentControllerExecutions";
    private static final String GROUP_METHOD = "getParallelGroup";
    private static final String EXECUTION_METHOD = "getParallelGroupExecution";

    /** Keyed by declaring class AND method, because subclasses may declare what the base class lacks. */
    private static final Map<String, Optional<MethodHandle>> HANDLES = new ConcurrentHashMap<>();

    /** Logged once, so an older engine does not produce a warning per sample. */
    private static volatile boolean unsupportedLogged;

    private ParentControllerTag() {
    }

    /**
     * @param sampleResult the sample to inspect, may be {@code null}
     * @return the enclosing controllers as a JSON array, outermost first, or {@code null} when the
     *         engine did not tag this sample (so the caller writes SQL NULL rather than {@code []})
     */
    public static String toJson(SampleResult sampleResult) {
        if (sampleResult == null) {
            return null;
        }
        List<?> chain = read(sampleResult, CHAIN_METHOD, List.class, List.of());
        if (chain.isEmpty()) {
            return null;
        }
        String execution = read(sampleResult, EXECUTION_METHOD, String.class, "");
        int parallelIndex = execution.isEmpty()
                ? -1
                : innermostNamed(chain, read(sampleResult, GROUP_METHOD, String.class, ""));

        StringBuilder sb = new StringBuilder(96).append('[');
        for (int i = 0; i < chain.size(); i++) {
            Object controller = chain.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":");
            SessionVariablesJson.appendString(sb, read(controller, "name", String.class, ""));
            sb.append(",\"class\":");
            SessionVariablesJson.appendString(sb, read(controller, "className", String.class, ""));
            sb.append(",\"iteration\":").append(read(controller, "iteration", Integer.class, -1));
            if (i == parallelIndex) {
                sb.append(",\"execution\":");
                SessionVariablesJson.appendString(sb, execution);
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * @return whether the running engine tags samples with their controllers at all, for one-time
     *         reporting at test start
     */
    public static boolean isSupported() {
        return resolve(SampleResult.class, CHAIN_METHOD, List.class).isPresent();
    }

    /**
     * The thread context knows only the innermost parallel group it is inside, so the execution id
     * belongs to the last entry carrying that name.
     */
    // ponytail: matched by name; two Parallel Controllers sharing a name in one chain would put the
    // id on the inner one. Match on class too if that ever shows up in a real script.
    private static int innermostNamed(List<?> chain, String name) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (name.equals(read(chain.get(i), "name", String.class, ""))) {
                return i;
            }
        }
        return -1;
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
            if (CHAIN_METHOD.equals(method) && !unsupportedLogged) {
                unsupportedLogged = true;
                LOGGER.info("This engine does not expose SampleResult.{}(); requests_raw.parent_controllers " +
                        "stays empty for this run. Requires a BreakTest build with " +
                        "sampleresult.parent_controllers enabled.", method);
            }
            return Optional.empty();
        }
    }

    /** Visible for testing: forget resolved accessors so a test can exercise both paths. */
    static void clearCache() {
        HANDLES.clear();
    }
}
