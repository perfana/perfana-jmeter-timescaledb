package io.perfana.jmeter.timescaledb.writer;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pooler under a starting test wave can refuse logins for a while, so startup must retry the
 * first connection rather than aborting the whole test run on one refusal.
 */
class ConnectionRetryTest {

    /** A port nothing listens on, so every connection attempt fails immediately. */
    private static int deadPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void keepsRetryingForTheConfiguredWindowBeforeGivingUp() throws Exception {
        TimescaleDBConfig config = config(deadPort(), "2000");

        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class, () -> new TimescaleDBWriter(config));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 2000,
                "Must keep retrying for the whole window, gave up after " + elapsed + " ms");
    }

    @Test
    void oneKeepsTheOldFailFastBehaviour() throws Exception {
        // 1 ms is HikariCP's own default: attempt once, throw on failure. (0 would start the pool
        // without throwing, and a negative value skips the initial check altogether.)
        TimescaleDBConfig config = config(deadPort(), "1");

        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class, () -> new TimescaleDBWriter(config));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "Should fail on the first attempt, took " + elapsed + " ms");
    }

    @Test
    void attemptCountDependsOnHowFastOneAttemptFails() {
        // The WERKNL case: a 60 s window over a 30 s per-attempt cap is two tries, not "60
        // seconds of retrying".
        assertEquals(2, TimescaleDBWriter.fewestAttempts(60000, 30000, 1000));
        // Raising the window is one lever.
        assertEquals(10, TimescaleDBWriter.fewestAttempts(300000, 30000, 1000));
        // Lowering the per-attempt cap is the other.
        assertEquals(6, TimescaleDBWriter.fewestAttempts(60000, 10000, 1000));
        // A port that refuses outright fails in milliseconds, so the same window buys dozens.
        assertEquals(60, TimescaleDBWriter.fewestAttempts(60000, 0, 1000));
        // Fail-fast still reports one attempt rather than zero.
        assertEquals(1, TimescaleDBWriter.fewestAttempts(1, 30000, 1000));
    }

    private TimescaleDBConfig config(int port, String initTimeout) {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_HOST, "localhost");
        args.addArgument(TimescaleDBConfig.KEY_PORT, String.valueOf(port));
        args.addArgument(TimescaleDBConfig.KEY_DATABASE, "nope");
        args.addArgument(TimescaleDBConfig.KEY_USER, "nope");
        args.addArgument(TimescaleDBConfig.KEY_PASSWORD, "nope");
        args.addArgument(TimescaleDBConfig.KEY_SSL_MODE, "disable");
        args.addArgument(TimescaleDBConfig.KEY_CONNECTION_TIMEOUT, "1000");
        args.addArgument(TimescaleDBConfig.KEY_CONNECTION_INIT_TIMEOUT, initTimeout);
        return TimescaleDBConfig.fromContext(new BackendListenerContext(args));
    }
}
