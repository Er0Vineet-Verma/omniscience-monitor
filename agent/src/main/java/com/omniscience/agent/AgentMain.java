package com.omniscience.agent;

import java.util.List;

/**
 * Omniscience Agent — one loop: collect real host metrics every interval, buffer,
 * ship to the collector, back off exponentially (max 5 min) when it is unreachable.
 * Runs without admin rights; needs only OMNI_AGENT_TOKEN.
 */
public final class AgentMain {

    private static final long MAX_BACKOFF_SECONDS = 300;

    public static void main(String[] args) throws InterruptedException {
        AgentConfig config = AgentConfig.fromEnv();
        System.out.println("[agent] starting — id=" + config.agentId()
                + " host=" + config.host()
                + " collector=" + config.collectorUrl()
                + " interval=" + config.intervalSeconds() + "s");

        MetricCollector collector = new MetricCollector();
        MetricBuffer buffer = new MetricBuffer(config.bufferCapacity());
        CollectorClient client = new CollectorClient(config);

        long backoffSeconds = 0;
        long nextAttemptAtMillis = 0;
        List<ProcessInfo> latestProcesses = List.of();

        while (true) {
            Thread.sleep(config.intervalSeconds() * 1000L);

            HostReading reading = collector.collect();
            List<MetricPoint> points = reading.metrics();
            latestProcesses = reading.processes();
            int dropped = buffer.addAll(points);
            if (dropped > 0) {
                System.out.println("[agent] buffer full — dropped " + dropped
                        + " oldest points (total dropped " + buffer.totalDropped() + ")");
            }

            long now = System.currentTimeMillis();
            if (buffer.isEmpty() || now < nextAttemptAtMillis) {
                continue;
            }

            List<MetricPoint> batch = buffer.snapshot(config.maxBatchSize());
            switch (client.send(batch, latestProcesses)) {
                case ACCEPTED -> {
                    buffer.removeFirst(batch.size());
                    if (backoffSeconds > 0) {
                        System.out.println("[agent] collector back — flushed " + batch.size()
                                + " points, buffer=" + buffer.size());
                    }
                    backoffSeconds = 0;
                    nextAttemptAtMillis = 0;
                }
                case RETRY -> {
                    backoffSeconds = backoffSeconds == 0
                            ? config.intervalSeconds()
                            : Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
                    nextAttemptAtMillis = now + backoffSeconds * 1000;
                    System.out.println("[agent] send failed — backing off " + backoffSeconds
                            + "s (buffered=" + buffer.size() + ")");
                }
                case REJECTED -> buffer.removeFirst(batch.size());
            }
        }
    }
}
