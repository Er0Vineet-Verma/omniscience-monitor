package com.omniscience.agent;

import java.util.List;

/** One collection cycle: bounded metric series plus an unbounded-but-snapshotted process list. */
public record HostReading(List<MetricPoint> metrics, List<ProcessInfo> processes) {
}
