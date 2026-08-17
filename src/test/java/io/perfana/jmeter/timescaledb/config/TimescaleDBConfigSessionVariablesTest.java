package io.perfana.jmeter.timescaledb.config;

import io.perfana.jmeter.timescaledb.util.SessionVariableFilter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimescaleDBConfigSessionVariablesTest {

    private BackendListenerContext context(Arguments args) {
        return new BackendListenerContext(args);
    }

    @Test
    void defaultsDisableCaptureAndAllowNothing() {
        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(new Arguments()));
        assertFalse(config.isSaveSessionVariables());
        assertEquals(2048, config.getSessionVariablesMaxValueLength());
        assertEquals(16384, config.getSessionVariablesMaxTotalBytes());
        assertTrue(config.getSessionVariablesInclude().isEmpty(),
                "Capture is opt-in per variable: nothing is allowed until names are configured");
    }

    @Test
    void parsesEnabledFlagAndAllowList() {
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, "true");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_INCLUDE, "Foo, BAR ,baz*");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_VALUE_LENGTH, "10");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_MAX_TOTAL_BYTES, "100");

        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(args));

        assertTrue(config.isSaveSessionVariables());
        assertEquals(10, config.getSessionVariablesMaxValueLength());
        assertEquals(100, config.getSessionVariablesMaxTotalBytes());
        assertEquals(3, config.getSessionVariablesInclude().size());

        Map<String, String> kept = SessionVariableFilter.filter(
                Map.of("foo", "1", "bar", "2", "bazinga", "3", "password", "4"),
                config.getSessionVariablesInclude(), 2048, 16384);
        assertEquals(3, kept.size());
        assertFalse(kept.containsKey("password"));
    }

    @Test
    void blankAllowListCapturesNothing() {
        // The GUI default is ${__P(sessionVariablesInclude,)}, which evaluates to "" when the
        // property is unset. Blank must mean "store nothing", never "store everything".
        Arguments args = new Arguments();
        args.addArgument(TimescaleDBConfig.KEY_SAVE_SESSION_VARIABLES, "true");
        args.addArgument(TimescaleDBConfig.KEY_SESSION_VARIABLES_INCLUDE, "");

        TimescaleDBConfig config = TimescaleDBConfig.fromContext(context(args));

        assertTrue(config.getSessionVariablesInclude().isEmpty());
    }
}
