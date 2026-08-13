package com.omniscience.collector.store;

import java.util.List;
import java.util.Optional;

/**
 * Nearest-rank percentile over a sorted list.
 *
 * Percentiles are the reason rollups store count/sum/min/max and nothing more:
 * a P95 cannot be reconstructed from per-bucket aggregates, so it is only ever
 * answered from raw samples inside the raw retention window. The scale-out answer
 * is a mergeable sketch (DDSketch / t-digest) — documented as post-v1, not faked here.
 */
public final class Percentiles {

    private Percentiles() {
    }

    /** {@code sorted} must be ascending. {@code q} is a fraction, e.g. 0.95. */
    public static Optional<Double> nearestRank(List<Double> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) {
            return Optional.empty();
        }
        if (q <= 0) {
            return Optional.of(sorted.get(0));
        }
        if (q >= 1) {
            return Optional.of(sorted.get(sorted.size() - 1));
        }
        int rank = (int) Math.ceil(q * sorted.size());
        int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return Optional.of(sorted.get(index));
    }
}
