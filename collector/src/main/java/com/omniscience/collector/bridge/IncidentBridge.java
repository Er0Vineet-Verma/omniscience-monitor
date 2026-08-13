package com.omniscience.collector.bridge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omniscience.collector.rule.AlertRule;

/**
 * Integration contract v1: episode-keyed idempotency, max one open incident per
 * (rule, host), auto-resolve with a recovery comment, and the incident system is
 * never load-bearing — failures queue in alert_episode and retry.
 *
 * Episode key = rule_key + host + episode_start_epoch. Stable across POST retries
 * and across every sweep of a long firing episode; only a genuinely new episode
 * (after hysteresis and cooldown) mints a new key.
 */
@Component
public class IncidentBridge {

    private static final Logger log = LoggerFactory.getLogger(IncidentBridge.class);

    private final JdbcTemplate jdbc;
    private final IncidentClient ims;

    public IncidentBridge(JdbcTemplate jdbc, IncidentClient ims) {
        this.jdbc = jdbc;
        this.ims = ims;
    }

    /** Called every sweep while FIRING — inserts the episode once, then retries incident creation until it lands. */
    public void ensureOpen(AlertRule rule, String host, Instant episodeStart, double observed) {
        String key = episodeKey(rule, host, episodeStart);
        Map<String, Object> row = findEpisode(key);
        if (row == null) {
            jdbc.update("INSERT INTO alert_episode (episode_key, rule_id, org_id, host, state, started_at) "
                            + "VALUES (?, ?, ?, ?, 'OPEN', ?)",
                    key, rule.ruleKey(), rule.orgId(), host, Timestamp.from(episodeStart));
            row = findEpisode(key);
        }
        if (row.get("incident_id") != null || !ims.enabled()) {
            return;
        }
        try {
            var created = ims.createIncident(
                    "[MONITOR] " + rule.name() + " on " + host,
                    """
                    Automated alert from Omniscience Monitor.

                    Rule: %s (%s)
                    Expression: %s
                    Host: %s
                    Observed: %s
                    Sustained: %ds before firing
                    Episode started: %s
                    Idempotency key: %s
                    Source: omniscience-monitor
                    """.formatted(rule.name(), rule.ruleKey(), rule.expression(), host,
                            format(observed), rule.forSeconds(), episodeStart, key),
                    rule.severity());
            jdbc.update("UPDATE alert_episode SET incident_id = ?, incident_number = ? WHERE episode_key = ?",
                    created.id(), created.number(), key);
            log.warn("incident created in IMS: {} (id {}) for episode {}", created.number(), created.id(), key);
        } catch (Exception e) {
            log.warn("IMS unreachable — incident creation for {} will retry next sweep: {}", key, e.getMessage());
        }
    }

    /** Called once on the RESOLVED transition; failures retry via the pending sweep. */
    public void resolveEpisode(AlertRule rule, String host, Instant episodeStart, double observed) {
        String key = episodeKey(rule, host, episodeStart);
        Map<String, Object> row = findEpisode(key);
        if (row == null) {
            return;
        }
        if (row.get("incident_id") == null) {
            // The episode came and went while the IMS was down — nothing to resolve.
            jdbc.update("UPDATE alert_episode SET state = 'RESOLVED', resolved_at = ? WHERE episode_key = ?",
                    Timestamp.from(Instant.now()), key);
            return;
        }
        jdbc.update("UPDATE alert_episode SET state = 'RESOLVE_PENDING', resolved_at = ? WHERE episode_key = ?",
                Timestamp.from(Instant.now()), key);
        tryResolveInIms(key, ((Number) row.get("incident_id")).longValue(), rule, observed);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void retryPendingResolves() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT episode_key, incident_id FROM alert_episode WHERE state = 'RESOLVE_PENDING'");
        for (Map<String, Object> row : pending) {
            tryResolveInIms((String) row.get("episode_key"),
                    ((Number) row.get("incident_id")).longValue(), null, Double.NaN);
        }
    }

    private void tryResolveInIms(String key, long incidentId, AlertRule rule, double observed) {
        try {
            String detail = rule != null
                    ? rule.expression() + " no longer satisfied (latest " + format(observed)
                      + "), sustained " + rule.resolveSeconds() + "s"
                    : "condition no longer satisfied";
            ims.addComment(incidentId, "[Omniscience Monitor] Recovery confirmed: " + detail
                    + ". Auto-resolving. Episode: " + key);
            ims.resolve(incidentId, "Auto-resolved by Omniscience Monitor after sustained recovery.");
            jdbc.update("UPDATE alert_episode SET state = 'RESOLVED' WHERE episode_key = ?", key);
            log.info("incident {} auto-resolved in IMS (episode {})", incidentId, key);
        } catch (Exception e) {
            log.warn("resolve of incident {} failed — will retry: {}", incidentId, e.getMessage());
        }
    }

    public List<Map<String, Object>> recentEpisodes() {
        return jdbc.queryForList(
                "SELECT episode_key, host, rule_id, state, incident_id, incident_number, started_at, resolved_at "
                        + "FROM alert_episode ORDER BY id DESC FETCH FIRST 10 ROWS ONLY");
    }

    private Map<String, Object> findEpisode(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM alert_episode WHERE episode_key = ?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String episodeKey(AlertRule rule, String host, Instant episodeStart) {
        return rule.ruleKey() + "|" + host + "|" + episodeStart.getEpochSecond();
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "n/a" : String.format("%.4f", value);
    }
}
