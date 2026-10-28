package io.perfana.jmeter.timescaledb.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Nothing on a SampleResult names the thread group, and the listener's worker thread cannot ask
 * JMeterContextService for it, so the name is peeled off the thread name JMeter composes as
 * {@code <thread group> <group>-<thread>}.
 */
class ThreadGroupNamesTest {

    @Test
    void readsTheGroupNameFromAThreadName() {
        assertEquals("TG_Zoeken", ThreadGroupNames.fromThreadName("TG_Zoeken 1-2"));
        assertEquals("TG_Solliciteren", ThreadGroupNames.fromThreadName("TG_Solliciteren 2-13"));
    }

    @Test
    void keepsSpacesAndDigitsInsideTheGroupName() {
        // JMeter's own default name has a space, and a name may legitimately end in a digit.
        assertEquals("Thread Group", ThreadGroupNames.fromThreadName("Thread Group 1-1"));
        assertEquals("Zoeken v2", ThreadGroupNames.fromThreadName("Zoeken v2 1-4"));
        assertEquals("Groep 3", ThreadGroupNames.fromThreadName("Groep 3 1-1"));
    }

    @Test
    void returnsNullWhenTheNameCarriesNoThreadSuffix() {
        // Guessing here would file rows under a scenario that does not exist.
        assertNull(ThreadGroupNames.fromThreadName("TimescaleDB-VU-Scheduler"));
        assertNull(ThreadGroupNames.fromThreadName("Zoeken"));
        assertNull(ThreadGroupNames.fromThreadName("Zoeken 1-"));
        assertNull(ThreadGroupNames.fromThreadName(""));
        assertNull(ThreadGroupNames.fromThreadName(null));
    }

    @Test
    void appendsTheGroupToTheScenarioName() {
        assertEquals("browseProducts_Zoeken",
                ThreadGroupNames.scenarioNameFor("browseProducts", "Zoeken"));
    }

    @Test
    void keepsTheScenarioNameWhenTheGroupIsUnknown() {
        assertEquals("browseProducts", ThreadGroupNames.scenarioNameFor("browseProducts", null));
        assertEquals("browseProducts", ThreadGroupNames.scenarioNameFor("browseProducts", ""));
    }

    @Test
    void doesNotRepeatAGroupNamedAfterItsScenario() {
        assertEquals("Zoeken", ThreadGroupNames.scenarioNameFor("Zoeken", "Zoeken"));
    }
}
