package io.perfana.jmeter.timescaledb.config;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimescaleDBConfigSessionVariablesTest {

    private BackendListenerContext context(Arguments args) {
        return new BackendListenerContext(args);
    }

    @Test
    void defaultsDisableCaptureWithSecretDenyList() {
        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(new Arguments()));
        assertFalse(config.isSaveSessionVariables());
        assertEquals(2048, config.getSessionVariablesMaxValueLength());
        assertEquals(16384, config.getSessionVariablesMaxTotalBytes());
        assertTrue(config.getSessionVariablesExclude().contains("password"));
        assertTrue(config.getSessionVariablesExclude().contains("jsessionid"));
    }

    @Test
    void parsesEnabledFlagAndCustomDenyList() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, "true");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_EXCLUDE, "Foo, BAR ,baz");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, "10");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, "100");

        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(args));

        assertTrue(config.isSaveSessionVariables());
        assertEquals(10, config.getSessionVariablesMaxValueLength());
        assertEquals(100, config.getSessionVariablesMaxTotalBytes());
        assertTrue(config.getSessionVariablesExclude().contains("foo"));
        assertTrue(config.getSessionVariablesExclude().contains("bar"));
        assertTrue(config.getSessionVariablesExclude().contains("baz"));
        assertFalse(config.getSessionVariablesExclude().contains("password"));
    }

    @Test
    void blankExcludeFallsBackToSecureDefault() {
        // The GUI default is ${__P(sessionVariablesExclude,)}, which evaluates to "" when the
        // property is unset. Blank must fall back to the built-in deny-list, not deny nothing.
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_EXCLUDE, "");

        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(args));

        assertTrue(config.getSessionVariablesExclude().contains("password"));
        assertTrue(config.getSessionVariablesExclude().contains("jsessionid"));
        assertTrue(config.getSessionVariablesExclude().contains("bearer"));
    }
}
