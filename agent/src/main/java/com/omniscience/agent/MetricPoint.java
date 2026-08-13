package com.omniscience.agent;

import java.util.Map;

/**
 * One sample: metric name, value, epoch-millis timestamp, and optional tags.
 * Tags stay deliberately few — the collector rejects high-cardinality tag sets,
 * and the agent has no business arguing with that.
 */
public record MetricPoint(String name, double value, long ts, Map<String, String> tags) {

    public MetricPoint(String name, double value, long ts) {
        this(name, value, ts, null);
    }
}
