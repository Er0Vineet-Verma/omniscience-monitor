package com.omniscience.collector.series;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.omniscience.collector.config.MonitorProps;

/**
 * High cardinality is what kills a metrics platform: one careless tag (a user id,
 * a request id, a PID) multiplies series without bound until ingest, storage and
 * queries all degrade. The guard rejects at the edge with 400 rather than
 * accepting data it cannot serve later.
 *
 * Cheap checks only — tag count, key/value shape and length, plus the org's active
 * series budget read from an in-memory counter. No database work on the hot path.
 */
@Component
public class CardinalityGuard {

    private final MonitorProps.Cardinality limits;
    private final SeriesRegistry registry;
    private final AtomicLong rejected = new AtomicLong();

    public CardinalityGuard(MonitorProps props, SeriesRegistry registry) {
        this.limits = props.cardinality();
        this.registry = registry;
    }

    /** Returns null when acceptable, otherwise a human-readable reason. */
    public String check(String orgId, String metric, Map<String, String> tags) {
        if (metric == null || metric.isBlank()) {
            return reject("metric name is required");
        }
        if (metric.length() > limits.maxNameLength()) {
            return reject("metric name exceeds " + limits.maxNameLength() + " chars");
        }
        if (tags.size() > limits.maxTagKeys()) {
            return reject("metric '" + metric + "' has " + tags.size()
                    + " tags, limit is " + limits.maxTagKeys());
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey().isBlank()) {
                return reject("empty tag key on metric '" + metric + "'");
            }
            if (e.getKey().length() > limits.maxTagKeyLength()) {
                return reject("tag key '" + e.getKey() + "' exceeds "
                        + limits.maxTagKeyLength() + " chars");
            }
            if (e.getValue() == null) {
                return reject("tag '" + e.getKey() + "' has a null value");
            }
            if (e.getValue().length() > limits.maxTagValueLength()) {
                return reject("tag '" + e.getKey() + "' value exceeds "
                        + limits.maxTagValueLength() + " chars");
            }
        }
        // Budget is only consulted for series we have not seen — existing series
        // keep flowing even once an org sits at its ceiling.
        if (!registry.isKnown(orgId, metric, tags) && registry.seriesCount() >= limits.maxSeriesPerOrg()) {
            return reject("active series budget reached (" + limits.maxSeriesPerOrg()
                    + ") — refusing to create new series for '" + metric + "'");
        }
        return null;
    }

    private String reject(String reason) {
        rejected.incrementAndGet();
        return reason;
    }

    public long rejectedCount() {
        return rejected.get();
    }
}
