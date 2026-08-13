package com.omniscience.collector.rule;

import java.time.Duration;

/** A stored alert rule. {@code ruleKey} is stable and forms part of the episode idempotency key. */
public record AlertRule(
        Long id,
        String orgId,
        String ruleKey,
        String name,
        String expression,
        String severity,
        int forSeconds,
        int resolveSeconds,
        int cooldownSeconds,
        boolean enabled) {

    public Duration forDuration() {
        return Duration.ofSeconds(forSeconds);
    }

    public Duration resolveDuration() {
        return Duration.ofSeconds(resolveSeconds);
    }

    public Duration cooldown() {
        return Duration.ofSeconds(cooldownSeconds);
    }
}
