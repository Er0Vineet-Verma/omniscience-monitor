package com.omniscience.collector.alert;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure per-(rule,host) alert state machine — no clock, no I/O, fully unit-testable.
 *
 * <pre>
 * OK --violation--> PENDING --sustained forDuration--> FIRING --sustained recovery resolveDuration--> OK (+cooldown)
 * </pre>
 *
 * Guards (SYSTEM-DESIGN.md slice 5):
 * - firing guard: violation must hold for {@code forDuration} before FIRING;
 * - resolve hysteresis: recovery must hold for {@code resolveDuration} before RESOLVED
 *   (89%↔91% oscillation must not churn tickets);
 * - re-fire cooldown: after RESOLVED, no new episode until {@code cooldown} passes.
 *
 * The episode start is the moment the violation began (not when FIRING was declared),
 * giving the stable episode identity used in the incident idempotency key.
 */
public final class AlertStateMachine {

    public enum State { OK, PENDING, FIRING }

    public sealed interface Transition {
    }

    public record Fired(Instant episodeStart) implements Transition {
    }

    public record Resolved(Instant episodeStart, Instant recoveredAt) implements Transition {
    }

    public record None() implements Transition {
    }

    private static final Transition NONE = new None();

    private final Duration forDuration;
    private final Duration resolveDuration;
    private final Duration cooldown;

    private State state = State.OK;
    private Instant violationSince;
    private Instant recoverySince;
    private Instant episodeStart;
    private Instant cooldownUntil = Instant.EPOCH;

    public AlertStateMachine(Duration forDuration, Duration resolveDuration, Duration cooldown) {
        this.forDuration = forDuration;
        this.resolveDuration = resolveDuration;
        this.cooldown = cooldown;
    }

    public Transition evaluate(Instant now, boolean violating) {
        switch (state) {
            case OK -> {
                if (violating && !now.isBefore(cooldownUntil)) {
                    state = State.PENDING;
                    violationSince = now;
                }
            }
            case PENDING -> {
                if (!violating) {
                    state = State.OK;
                    violationSince = null;
                } else if (Duration.between(violationSince, now).compareTo(forDuration) >= 0) {
                    state = State.FIRING;
                    episodeStart = violationSince;
                    recoverySince = null;
                    return new Fired(episodeStart);
                }
            }
            case FIRING -> {
                if (violating) {
                    recoverySince = null; // recovery interrupted — hysteresis restarts
                } else {
                    if (recoverySince == null) {
                        recoverySince = now;
                    }
                    if (Duration.between(recoverySince, now).compareTo(resolveDuration) >= 0) {
                        Instant start = episodeStart;
                        Instant recoveredAt = recoverySince;
                        state = State.OK;
                        cooldownUntil = now.plus(cooldown);
                        violationSince = null;
                        recoverySince = null;
                        episodeStart = null;
                        return new Resolved(start, recoveredAt);
                    }
                }
            }
        }
        return NONE;
    }

    public State state() {
        return state;
    }

    /** Non-null only while FIRING — stable for the whole episode. */
    public Instant episodeStart() {
        return episodeStart;
    }
}
