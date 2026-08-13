package com.omniscience.collector.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.omniscience.collector.model.Sample;
import com.omniscience.collector.model.StoredSample;

/**
 * The storage seam (ADR-001). Local: H2. Production: PostgreSQL + TimescaleDB.
 * Rollups are NOT this layer's job — the Interval Processor owns them (ADR-004);
 * the store only exposes the raw reads the processor needs.
 */
public interface MetricStore {

    void write(List<StoredSample> samples);

    void writeHostSnapshot(String orgId, String host, Instant capturedAt, String payloadJson);

    Optional<Sample> latest(String metric, String host);

    List<String> activeHosts(String metric, Instant since);

    /** Raw samples in [from, to) joined to their series — the processor's input. */
    List<RawPoint> rawBetween(Instant from, Instant to);

    /** All series for a metric within a tenant — the PromQL evaluator's entry point. */
    List<com.omniscience.collector.promql.Evaluator.DataSource.SeriesRef> findSeries(String orgId, String metric);

    /** Samples for specific series in [from, to]. */
    List<RawPoint> samplesFor(List<Long> seriesIds, Instant from, Instant to);

    /** Distinct hosts that reported anything for this tenant since the given instant. */
    List<String> activeHostsForOrg(String orgId, Instant since);

    /** Percentiles are computed over RAW data only; rollups cannot answer them. */
    Optional<Double> percentile(String metric, String host, Instant since, double q);

    Optional<Instant> oldestSampleAt();

    long totalSamples();

    long totalRollups();

    Optional<Instant> lastSampleAt();

    record RawPoint(long seriesId, Instant ts, double value) {
    }
}
