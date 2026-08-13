package com.omniscience.collector.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All collector configuration. Secrets (agent token, IMS credentials) resolve from
 * the environment / .env only — application.yml carries no fallback values, so the
 * app fails fast when they are missing (IMS discipline).
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProps(String agentToken, String orgId, int busCapacity,
                           Alert alert, Ims ims, Cardinality cardinality,
                           Rollup rollup, Retention retention) {

    /**
     * Only the sweep interval remains here — thresholds, windows and severities moved
     * into the alert_rule table in V5, because rules are data that operators change,
     * not configuration that requires a redeploy.
     */
    public record Alert(long evaluateMs) {
    }

    public record Ims(boolean enabled, String baseUrl, String email, String password) {
    }

    /** Limits that keep series growth bounded — see CardinalityGuard. */
    public record Cardinality(int maxTagKeys, int maxTagKeyLength, int maxTagValueLength,
                              int maxNameLength, int maxSeriesPerOrg) {
    }

    /** {@code lagSeconds} is how long to wait past a bucket's end before sealing it. */
    public record Rollup(List<String> resolutions, int lagSeconds, long intervalMs) {
    }

    public record Retention(int rawDays, int rollupDays, long intervalMs) {
    }
}
