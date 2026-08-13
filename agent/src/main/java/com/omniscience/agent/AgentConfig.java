package com.omniscience.agent;

import java.net.InetAddress;

/**
 * All agent configuration comes from environment variables — the agent ships as a
 * single jar with no config file. OMNI_AGENT_TOKEN is the only required value
 * (static bearer token in week 1; replaced by the mTLS identity in week 4 behind
 * the same collector-side AgentAuthenticator seam).
 */
public record AgentConfig(
        String collectorUrl,
        String token,
        String agentId,
        String host,
        int intervalSeconds,
        int bufferCapacity,
        int maxBatchSize
) {

    public static AgentConfig fromEnv() {
        String token = System.getenv("OMNI_AGENT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("[agent] FATAL: OMNI_AGENT_TOKEN is not set — refusing to start (no anonymous ingest).");
            System.exit(1);
        }
        String hostname = System.getenv().getOrDefault("COMPUTERNAME", "");
        if (hostname.isBlank()) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                hostname = "unknown-host";
            }
        }
        return new AgentConfig(
                env("OMNI_COLLECTOR_URL", "http://localhost:8081"),
                token,
                env("OMNI_AGENT_ID", hostname),
                hostname,
                Integer.parseInt(env("OMNI_INTERVAL_SECONDS", "10")),
                Integer.parseInt(env("OMNI_BUFFER_CAPACITY", "3600")),
                Integer.parseInt(env("OMNI_MAX_BATCH", "500"))
        );
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
