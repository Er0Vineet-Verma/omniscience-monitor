package com.omniscience.collector.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.omniscience.collector.alert.AlertStateMachine.Fired;
import com.omniscience.collector.alert.AlertStateMachine.None;
import com.omniscience.collector.alert.AlertStateMachine.Resolved;
import com.omniscience.collector.alert.AlertStateMachine.State;

/** Pure tests, injected time — for=60s, resolve hysteresis=60s, cooldown=120s. */
class AlertStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-07-19T10:00:00Z");

    private AlertStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new AlertStateMachine(
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(120));
    }

    private Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void firesOnlyAfterSustainedViolation_episodeStartIsViolationStart() {
        assertInstanceOf(None.class, machine.evaluate(at(0), true));   // violation begins
        assertEquals(State.PENDING, machine.state());
        assertInstanceOf(None.class, machine.evaluate(at(30), true));  // not sustained yet

        var transition = machine.evaluate(at(60), true);
        Fired fired = assertInstanceOf(Fired.class, transition);
        assertEquals(at(0), fired.episodeStart()); // identity = when the violation BEGAN
        assertEquals(State.FIRING, machine.state());
    }

    @Test
    void briefBlipNeverFires() {
        machine.evaluate(at(0), true);
        machine.evaluate(at(30), false); // recovered before forDuration
        assertEquals(State.OK, machine.state());

        machine.evaluate(at(40), true);  // new violation — clock restarts
        assertInstanceOf(None.class, machine.evaluate(at(90), true)); // only 50s in
        Fired fired = assertInstanceOf(Fired.class, machine.evaluate(at(100), true));
        assertEquals(at(40), fired.episodeStart());
    }

    @Test
    void episodeStartStableAcrossSweepsWhileFiring() {
        machine.evaluate(at(0), true);
        machine.evaluate(at(60), true); // fires
        Instant start = machine.episodeStart();
        machine.evaluate(at(75), true);
        machine.evaluate(at(90), true);
        assertEquals(start, machine.episodeStart()); // same episode key every sweep
    }

    @Test
    void oscillationDoesNotResolve_recoveryMustBeSustained() {
        machine.evaluate(at(0), true);
        machine.evaluate(at(60), true); // FIRING

        machine.evaluate(at(75), false);  // dips below…
        machine.evaluate(at(105), true);  // …but spikes again — hysteresis restarts
        machine.evaluate(at(120), false);
        assertInstanceOf(None.class, machine.evaluate(at(150), false)); // only 30s of recovery
        assertEquals(State.FIRING, machine.state()); // 89↔91 flapping keeps ONE episode open

        Resolved resolved = assertInstanceOf(Resolved.class, machine.evaluate(at(180), false));
        assertEquals(at(0), resolved.episodeStart());
        assertEquals(State.OK, machine.state());
        assertNull(machine.episodeStart());
    }

    @Test
    void cooldownBlocksImmediateRefire() {
        machine.evaluate(at(0), true);
        machine.evaluate(at(60), true);   // FIRING
        machine.evaluate(at(120), false);
        machine.evaluate(at(180), false); // RESOLVED at t=180, cooldown until t=300

        machine.evaluate(at(200), true);  // violation during cooldown — ignored
        assertEquals(State.OK, machine.state());

        machine.evaluate(at(300), true);  // cooldown over — tracking resumes
        assertEquals(State.PENDING, machine.state());
        Fired fired = assertInstanceOf(Fired.class, machine.evaluate(at(360), true));
        assertEquals(at(300), fired.episodeStart()); // NEW episode, new key
    }
}
