package com.omniscience.collector.model;

import java.time.Instant;

/** A sample after series resolution — what actually hits the samples table. */
public record StoredSample(long seriesId, Instant ts, double value) {
}
