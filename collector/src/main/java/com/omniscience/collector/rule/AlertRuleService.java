package com.omniscience.collector.rule;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.omniscience.collector.promql.Parser;
import com.omniscience.collector.promql.PromQLException;

/**
 * Rule CRUD. Every write parses the expression first, so a rule that cannot be
 * evaluated can never be stored — the failure surfaces to the author immediately
 * instead of at 3am inside a scheduled sweep.
 */
@Service
public class AlertRuleService {

    private static final RowMapper<AlertRule> MAPPER = (rs, n) -> new AlertRule(
            rs.getLong("id"),
            rs.getString("org_id"),
            rs.getString("rule_key"),
            rs.getString("name"),
            rs.getString("expression"),
            rs.getString("severity"),
            rs.getInt("for_seconds"),
            rs.getInt("resolve_seconds"),
            rs.getInt("cooldown_seconds"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public AlertRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlertRule> findAll(String orgId) {
        return jdbc.query("SELECT * FROM alert_rule WHERE org_id = ? ORDER BY rule_key", MAPPER, orgId);
    }

    public List<AlertRule> findEnabled(String orgId) {
        return jdbc.query("SELECT * FROM alert_rule WHERE org_id = ? AND enabled = TRUE ORDER BY rule_key",
                MAPPER, orgId);
    }

    public Optional<AlertRule> find(String orgId, String ruleKey) {
        return jdbc.query("SELECT * FROM alert_rule WHERE org_id = ? AND rule_key = ?", MAPPER, orgId, ruleKey)
                .stream().findFirst();
    }

    public AlertRule create(AlertRule rule) {
        validate(rule);
        if (find(rule.orgId(), rule.ruleKey()).isPresent()) {
            throw new IllegalArgumentException("rule '" + rule.ruleKey() + "' already exists");
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO alert_rule (org_id, rule_key, name, expression, severity,
                    for_seconds, resolve_seconds, cooldown_seconds, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rule.orgId(), rule.ruleKey(), rule.name(), rule.expression(), rule.severity(),
                rule.forSeconds(), rule.resolveSeconds(), rule.cooldownSeconds(), rule.enabled(), now, now);
        return find(rule.orgId(), rule.ruleKey()).orElseThrow();
    }

    public AlertRule update(String orgId, String ruleKey, AlertRule patch) {
        AlertRule existing = find(orgId, ruleKey)
                .orElseThrow(() -> new IllegalArgumentException("no such rule: " + ruleKey));
        AlertRule merged = new AlertRule(
                existing.id(), orgId, ruleKey,
                patch.name() != null ? patch.name() : existing.name(),
                patch.expression() != null ? patch.expression() : existing.expression(),
                patch.severity() != null ? patch.severity() : existing.severity(),
                patch.forSeconds() > 0 ? patch.forSeconds() : existing.forSeconds(),
                patch.resolveSeconds() > 0 ? patch.resolveSeconds() : existing.resolveSeconds(),
                patch.cooldownSeconds() > 0 ? patch.cooldownSeconds() : existing.cooldownSeconds(),
                patch.enabled());
        validate(merged);
        jdbc.update("""
                UPDATE alert_rule SET name = ?, expression = ?, severity = ?, for_seconds = ?,
                    resolve_seconds = ?, cooldown_seconds = ?, enabled = ?, updated_at = ?
                WHERE org_id = ? AND rule_key = ?
                """,
                merged.name(), merged.expression(), merged.severity(), merged.forSeconds(),
                merged.resolveSeconds(), merged.cooldownSeconds(), merged.enabled(),
                Timestamp.from(Instant.now()), orgId, ruleKey);
        return find(orgId, ruleKey).orElseThrow();
    }

    public boolean delete(String orgId, String ruleKey) {
        return jdbc.update("DELETE FROM alert_rule WHERE org_id = ? AND rule_key = ?", orgId, ruleKey) > 0;
    }

    /** Parses without storing — backs the "validate" affordance in the rule editor. */
    public void validateExpression(String expression) {
        Parser.parseCondition(expression);
    }

    private void validate(AlertRule rule) {
        if (rule.ruleKey() == null || rule.ruleKey().isBlank()) {
            throw new IllegalArgumentException("ruleKey is required");
        }
        if (rule.name() == null || rule.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!List.of("P1", "P2", "P3", "P4").contains(rule.severity())) {
            throw new IllegalArgumentException("severity must be one of P1, P2, P3, P4");
        }
        if (rule.forSeconds() <= 0 || rule.resolveSeconds() <= 0 || rule.cooldownSeconds() < 0) {
            throw new IllegalArgumentException("for/resolve must be positive and cooldown non-negative");
        }
        try {
            Parser.parseCondition(rule.expression());
        } catch (PromQLException e) {
            throw new IllegalArgumentException("invalid expression: " + e.getMessage());
        }
    }
}
