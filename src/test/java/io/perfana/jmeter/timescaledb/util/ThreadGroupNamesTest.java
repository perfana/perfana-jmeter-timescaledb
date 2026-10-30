package io.perfana.jmeter.timescaledb.util;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void keyTellsIdenticallyNamedThreadGroupsApart() {
        // Two thread groups both called "Thread Group": the name cannot separate their thread
        // counts, but JMeter keeps the thread group's own index in the thread name.
        assertEquals("Thread Group 1", keyOf("Thread Group 1-2"));
        assertEquals("Thread Group 2", keyOf("Thread Group 2-1"));
        assertEquals("Thread Group", ThreadGroupNames.nameOfKey("Thread Group 1"));
        assertEquals("Thread Group", ThreadGroupNames.nameOfKey("Thread Group 2"));
    }

    @Test
    void keyAndNameAgreeForAnOrdinaryGroup() {
        assertEquals("TG_Zoeken 1", keyOf("TG_Zoeken 1-2"));
        assertEquals("TG_Zoeken", ThreadGroupNames.nameOfKey("TG_Zoeken 1"));
        assertEquals("TG_Zoeken", ThreadGroupNames.fromThreadName("TG_Zoeken 1-2"));
    }

    @Test
    void keyIsNullWhenTheThreadNameHasNoSuffix() {
        assertNull(keyOf("TimescaleDB-VU-Scheduler"));
        assertNull(keyOf("Zoeken"));
    }

    @Test
    void stripsTheLoadGeneratorAddressADistributedRunPutsInFront() {
        // A remote engine names its threads "<host>-<thread group> <group>-<thread>". Left in, one
        // thread group becomes a different scenario on every generator.
        List<String> hosts = List.of("10.0.0.1:1099", "10.0.0.2:1099");
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix("10.0.0.1:1099-Zoeken", hosts));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix("10.0.0.2:1099-Zoeken", hosts));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix("Zoeken", hosts));
    }

    @Test
    void matchesWhetherOrNotEitherSideCarriesThePort() {
        // JMeter drops the port from remote_hosts but keeps it in the thread name, so the two
        // spellings disagree and both have to match.
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.0.0.1:1099-Zoeken", List.of("10.0.0.1")));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.0.0.1-Zoeken", List.of("10.0.0.1:1099")));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.0.0.1:1099-Zoeken", List.of("10.0.0.1:1099")));
    }

    @Test
    void stripsAnAddressWithAPortEvenWhenTheHostListCannotConfirmIt() {
        // remote_hosts may be unset by the time this runs, or -R may be given a host name while
        // the thread name carries the resolved address. "<address>:<port>-" is conclusive anyway:
        // a standalone run never puts that in a thread name.
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.178.135.133:1093-Zoeken", List.of()));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.9.9.9:1099-Zoeken", List.of("10.0.0.1")));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "uwva3vwpapp0109:1093-Zoeken", List.of("10.178.135.133")));
    }

    @Test
    void doesNotMistakeAThreadGroupNameForAnAddress() {
        // No port, and not a configured host, so nothing is stripped.
        assertEquals("order-entry", ThreadGroupNames.withoutRemoteHostPrefix(
                "order-entry", List.of("10.0.0.1")));
        assertEquals("10 of 20-Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10 of 20-Zoeken", List.of("10.0.0.1")));
        // A colon with no digits after it is not an address either.
        assertEquals("stage:prod-Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "stage:prod-Zoeken", List.of()));
    }

    @Test
    void keepsAThreadGroupNameThatMerelyContainsADash() {
        // Cutting at the first dash would mangle this; matching the configured hosts does not.
        List<String> hosts = List.of("10.0.0.1");
        assertEquals("order-entry", ThreadGroupNames.withoutRemoteHostPrefix("order-entry", hosts));
        assertEquals("order-entry",
                ThreadGroupNames.withoutRemoteHostPrefix("10.0.0.1-order-entry", hosts));
    }

    @Test
    void stripsOnlyOnePrefixEvenWhenTheGroupStartsWithTheHostName() {
        List<String> hosts = List.of("web01");
        assertEquals("web01-foo", ThreadGroupNames.withoutRemoteHostPrefix("web01-web01-foo", hosts));
    }

    @Test
    void theVirtualUsersKeySeparatesGeneratorsButItsNameDoesNot() {
        // Two engines running the same thread group must be counted separately and then reported
        // under one scenario name, which is the concurrency of that thread group across the run.
        assertEquals("10.0.0.1:1099-Zoeken 1", keyOf("10.0.0.1:1099-Zoeken 1-2"));
        assertEquals("10.0.0.2:1099-Zoeken 1", keyOf("10.0.0.2:1099-Zoeken 1-2"));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix(
                "10.0.0.1:1099-Zoeken", List.of("10.0.0.1:1099", "10.0.0.2:1099")));
    }

    @Test
    void standaloneRunsHaveNoHostsToStrip() {
        assertEquals(List.of(), ThreadGroupNames.parseHosts(""));
        assertEquals(List.of(), ThreadGroupNames.parseHosts(null));
        assertEquals("Zoeken", ThreadGroupNames.withoutRemoteHostPrefix("Zoeken", List.of()));
    }

    @Test
    void parsesTheRemoteHostsProperty() {
        assertEquals(List.of("10.0.0.1:1099", "10.0.0.2:1099"),
                ThreadGroupNames.parseHosts(" 10.0.0.1:1099 , 10.0.0.2:1099 "));
    }

    /** Exercises keyOf through a SampleResult, the way the listener calls it. */
    private static String keyOf(String threadName) {
        SampleResult result = new SampleResult();
        result.setThreadName(threadName);
        return ThreadGroupNames.keyOf(result);
    }
}
