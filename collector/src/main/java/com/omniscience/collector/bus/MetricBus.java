package com.omniscience.collector.bus;

import java.util.List;

import com.omniscience.collector.model.Sample;

/**
 * The transport seam (ADR-001). Local: bounded in-memory queue. Production: Kafka
 * topic partitioned by (org_id, host). publish() returning false means the bus is
 * saturated — the caller load-sheds with 429 (SYSTEM-DESIGN.md slice 3).
 */
public interface MetricBus {

    boolean publish(List<Sample> batch);

    long shedCount();

    int depth();
}
