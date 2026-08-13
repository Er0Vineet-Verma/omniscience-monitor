package com.omniscience.collector.rollup;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.omniscience.collector.store.MetricStore.RawPoint;

/**
 * Pure rollup arithmetic — no clock, no database, fully unit-testable.
 *
 * Buckets hold count/sum/min/max/last. Deliberately NOT an average: averaging
 * averages across buckets is wrong whenever bucket counts differ, so the average
 * is derived at read time as sum/count. See ADR-004.
 */
public final class Rollups {

    private Rollups() {
    }

    public record Bucket(long seriesId, Instant bucketTs, long count, double sum,
                         double min, double max, double last) {

        public double mean() {
            return count == 0 ? 0 : sum / count;
        }
    }

    /** Floors an instant to the start of its bucket. Resolution must divide evenly into an hour or day. */
    public static Instant floor(Instant ts, Duration resolution) {
        long millis = resolution.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(ts.toEpochMilli(), millis) * millis);
    }

    /** Groups raw points into buckets, preserving encounter order of (series, bucket). */
    public static List<Bucket> aggregate(List<RawPoint> points, Duration resolution) {
        record Key(long seriesId, Instant bucket) {
        }
        Map<Key, double[]> acc = new LinkedHashMap<>();
        // slot layout: [count, sum, min, max, last, lastTsMillis]
        for (RawPoint p : points) {
            Key k = new Key(p.seriesId(), floor(p.ts(), resolution));
            double[] a = acc.get(k);
            if (a == null) {
                acc.put(k, new double[]{1, p.value(), p.value(), p.value(), p.value(), p.ts().toEpochMilli()});
                continue;
            }
            a[0] += 1;
            a[1] += p.value();
            a[2] = Math.min(a[2], p.value());
            a[3] = Math.max(a[3], p.value());
            if (p.ts().toEpochMilli() >= a[5]) {
                a[4] = p.value();
                a[5] = p.ts().toEpochMilli();
            }
        }
        return acc.entrySet().stream()
                .map(e -> new Bucket(e.getKey().seriesId(), e.getKey().bucket(),
                        (long) e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], e.getValue()[4]))
                .toList();
    }
}
