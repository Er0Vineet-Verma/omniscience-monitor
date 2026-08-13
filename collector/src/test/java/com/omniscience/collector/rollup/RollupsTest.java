package com.omniscience.collector.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.omniscience.collector.store.MetricStore.RawPoint;

/** Rollup arithmetic, including the trap that ADR-004 exists to avoid. */
class RollupsTest {

    private static final Instant T0 = Instant.parse("2026-08-02T10:00:00Z");

    private static RawPoint p(long series, int secondsIn, double value) {
        return new RawPoint(series, T0.plusSeconds(secondsIn), value);
    }

    @Test
    void floorsToBucketStart() {
        assertEquals(T0, Rollups.floor(T0.plusSeconds(59), Duration.ofMinutes(1)));
        assertEquals(T0.plusSeconds(60), Rollups.floor(T0.plusSeconds(60), Duration.ofMinutes(1)));
        assertEquals(T0, Rollups.floor(T0.plusSeconds(299), Duration.ofMinutes(5)));
    }

    @Test
    void aggregatesCountSumMinMaxLastPerBucket() {
        List<Rollups.Bucket> buckets = Rollups.aggregate(
                List.of(p(1, 0, 10), p(1, 30, 30), p(1, 45, 20)), Duration.ofMinutes(1));

        assertEquals(1, buckets.size());
        Rollups.Bucket b = buckets.get(0);
        assertEquals(T0, b.bucketTs());
        assertEquals(3, b.count());
        assertEquals(60.0, b.sum());
        assertEquals(10.0, b.min());
        assertEquals(30.0, b.max());
        assertEquals(20.0, b.last()); // newest timestamp, not largest value
        assertEquals(20.0, b.mean());
    }

    @Test
    void separatesSeriesAndBuckets() {
        List<Rollups.Bucket> buckets = Rollups.aggregate(
                List.of(p(1, 0, 1), p(2, 0, 5), p(1, 61, 3)), Duration.ofMinutes(1));
        assertEquals(3, buckets.size());
    }

    @Test
    void outOfOrderArrivalStillPicksTrueLast() {
        List<Rollups.Bucket> buckets = Rollups.aggregate(
                List.of(p(1, 50, 99), p(1, 10, 1)), Duration.ofMinutes(1));
        assertEquals(99.0, buckets.get(0).last());
    }

    /**
     * The reason rollups store sum+count instead of a mean: re-averaging bucket
     * means gives the wrong answer whenever the buckets hold different counts.
     */
    @Test
    void meanOfMeansIsWrong_soWeKeepSumAndCount() {
        List<Rollups.Bucket> buckets = Rollups.aggregate(
                List.of(p(1, 0, 10), p(1, 10, 10), p(1, 20, 10), p(1, 61, 100)),
                Duration.ofMinutes(1));

        double meanOfMeans = buckets.stream().mapToDouble(Rollups.Bucket::mean).average().orElseThrow();
        double trueMean = buckets.stream().mapToDouble(Rollups.Bucket::sum).sum()
                / buckets.stream().mapToLong(Rollups.Bucket::count).sum();

        assertEquals(32.5, trueMean, 1e-9);   // (10+10+10+100)/4
        assertEquals(55.0, meanOfMeans, 1e-9); // (10 + 100)/2 — the wrong answer
        assertNotEquals(meanOfMeans, trueMean);
    }
}
