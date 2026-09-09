package io.perfana.jmeter.timescaledb.writer;

import io.perfana.jmeter.timescaledb.config.TimescaleDBConfig;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

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
