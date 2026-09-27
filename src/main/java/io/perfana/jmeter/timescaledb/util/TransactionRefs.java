package io.perfana.jmeter.timescaledb.util;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads the transaction a sample ran in, on engines that link a sample to its Transaction
 * Controller by reference instead of by nesting.
 *
 * <p>BreakTest 2026.09.25 removed the Transaction Controller's "Generate parent sample" mode. A
 * transaction sample no longer holds its samplers as sub-results: every sampler result is delivered
 * as soon as it completes with {@code SampleResult.getParentTransaction()} pointing at the
 * {@code TransactionRef} it ran in ({@code id}, {@code name}, enclosing {@code parent}), and the
 * transaction sample arrives last carrying only running totals. Attribution through the reference is
 * exact, where walking {@code getParent()} yields nothing at all on this engine and was never
 * reliable on the previous one.
 *
 * <p>The plugin compiles against stock Apache JMeter, which has neither the methods nor the
 * {@code TransactionRef} type, so everything here is read reflectively and an engine that supplies
 * nothing simply reports "not supported". Plain {@link Method} reflection rather than the
 * {@code MethodHandles} used by {@link SampleMetadata}: a method handle lookup needs the exact
 * return type, and {@code TransactionRef} does not exist here to name.
 */
public final class TransactionRefs {

    private static final Logger LOGGER = LogManager.getLogger(TransactionRefs.class);

    private static final String PARENT_TRANSACTION_METHOD = "getParentTransaction";
    private static final String PATH_METHOD = "getPath";

    /** Keyed by declaring class AND method, because a subclass may declare what the base class lacks. */
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private TransactionRefs() {
    }

    /**
     * @return whether the running engine links samples to their transaction at all, which decides
     *         between exact attribution and the sub-result parent chain
     */
    public static boolean isSupported() {
        return linksTransactions(SampleResult.class);
    }

    /**
     * Answered per sample rather than once per run: the concrete result class is what carries the
     * accessor, and resolving it is a cached map lookup.
     *
     * @param sample the sample to inspect, may be {@code null}
     * @return whether this sample can name the transaction it ran in
     */
    public static boolean linksTransactions(SampleResult sample) {
        return sample != null && linksTransactions(sample.getClass());
    }

    /**
     * The transaction this sample ran in.
     *
     * <p>Only the top-level result of a sampler is stamped with the reference, so a sub-result (an
     * HTTP embedded resource, a redirect step) is attributed through the sampler that owns it. That
     * parent link lives inside one result graph and is always intact, unlike the Transaction
     * Controller link the previous engine expected there.
     *
     * @param sample the sample to attribute, may be {@code null}
     * @return whether this sample ran inside a transaction
     */
    public static boolean isInsideTransaction(SampleResult sample) {
        return refOf(sample) != null;
    }

    /**
     * @param sample    the sample to attribute, may be {@code null}
     * @param outermost {@code true} to name the outermost enclosing transaction (what
     *                  {@code flattenNestedTransactions} asks for), {@code false} for the innermost
     * @return the transaction name, or {@code null} when this sample ran outside any transaction
     */
    public static String transactionName(SampleResult sample, boolean outermost) {
        Object ref = refOf(sample);
        if (ref == null) {
            return null;
        }
        List<?> path = read(ref, PATH_METHOD, List.class);
        if (path == null || path.isEmpty()) {
            return null;
        }
        Object name = outermost ? path.get(0) : path.get(path.size() - 1);
        return name == null ? null : name.toString();
    }

    /**
     * On a transaction sample {@code getParentTransaction()} returns the <em>enclosing</em>
     * transaction, not its own, so a non-null reference is exactly the nesting test that
     * {@code flattenNestedTransactions} needs.
     *
     * @param transactionSample a sample produced by a Transaction Controller
     * @return whether it is nested in another transaction
     */
    public static boolean isNestedTransaction(SampleResult transactionSample) {
        return transactionSample != null
                && read(transactionSample, PARENT_TRANSACTION_METHOD, Object.class) != null;
    }

    private static Object refOf(SampleResult sample) {
        for (SampleResult current = sample; current != null; current = current.getParent()) {
            Object ref = read(current, PARENT_TRANSACTION_METHOD, Object.class);
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    private static boolean linksTransactions(Class<?> type) {
        return resolve(type, PARENT_TRANSACTION_METHOD).isPresent();
    }

    private static <T> T read(Object target, String method, Class<T> type) {
        Optional<Method> resolved = resolve(target.getClass(), method);
        if (resolved.isEmpty()) {
            return null;
        }
        try {
            Object value = resolved.get().invoke(target);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (Exception e) { // NOSONAR - a reflective call must never break result handling
            LOGGER.debug("Could not read {} from {}: {}",
                    method, target.getClass().getName(), e.toString());
            return null;
        }
    }

    private static Optional<Method> resolve(Class<?> owner, String method) {
        return METHODS.computeIfAbsent(owner.getName() + '#' + method, key -> {
            try {
                return Optional.of(owner.getMethod(method));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
    }
}
