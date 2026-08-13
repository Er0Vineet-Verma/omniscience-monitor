package com.omniscience.collector.rollup;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omniscience.collector.config.MonitorProps;
import com.omniscience.collector.store.MetricStore;
import com.omniscience.collector.store.MetricStore.RawPoint;

/**
 * ADR-004: downsampling lives here, in portable Java + SQL, rather than in
 * TimescaleDB continuous aggregates. The same code runs identically on H2 and
 * Postgres, which is what keeps the local and production profiles honest.
 *
 * Each resolution keeps a watermark. Only closed buckets are processed — a bucket
 * is closed once the clock has moved a full lag period past its end, so late
 * arrivals inside the lag window are still counted. Reprocessing a range is safe:
 * rows for the range are deleted before insert, so the job is idempotent.
 */
@Component
public class IntervalProcessor {

    private static final Logger log = LoggerFactory.getLogger(IntervalProcessor.class);

    private final MetricStore store;
    private final JdbcTemplate jdbc;
    private final MonitorProps.Rollup config;

    public IntervalProcessor(MetricStore store, JdbcTemplate jdbc, MonitorProps props) {
        this.store = store;
        this.jdbc = jdbc;
        this.config = props.rollup();
    }

    @Scheduled(fixedDelayString = "${monitor.rollup.interval-ms}", initialDelay = 30000)
    public void run() {
        for (String resolution : config.resolutions()) {
            try {
                process(resolution, Instant.now());
            } catch (Exception e) {
                log.error("rollup {} failed: {}", resolution, e.getMessage());
            }
        }
    }

    /** Visible for tests: processes one resolution against an injected clock reading. */
    public int process(String resolution, Instant now) {
        Duration step = parse(resolution);
        Instant cutoff = Rollups.floor(now.minus(Duration.ofSeconds(config.lagSeconds())), step);
        Instant from = watermark(resolution, step, cutoff);
        if (!from.isBefore(cutoff)) {
            return 0;
        }

        List<RawPoint> raw = store.rawBetween(from, cutoff);
        List<Rollups.Bucket> buckets = Rollups.aggregate(raw, step);

        // Idempotent: clear anything already written for this window before inserting.
        jdbc.update("DELETE FROM metric_rollup WHERE resolution = ? AND bucket_ts >= ? AND bucket_ts < ?",
                resolution, Timestamp.from(from), Timestamp.from(cutoff));

        if (!buckets.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO metric_rollup
                      (series_id, resolution, bucket_ts, sample_count, sum_value, min_value, max_value, last_value)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    buckets.stream().map(b -> new Object[]{
                            b.seriesId(), resolution, Timestamp.from(b.bucketTs()),
                            b.count(), b.sum(), b.min(), b.max(), b.last()}).toList());
        }

        setWatermark(resolution, cutoff);
        if (!buckets.isEmpty()) {
            log.info("rollup {} — {} raw points into {} buckets up to {}",
                    resolution, raw.size(), buckets.size(), cutoff);
        }
        return buckets.size();
    }

    private Instant watermark(String resolution, Duration step, Instant cutoff) {
        Timestamp ts = jdbc.query("SELECT processed_to FROM rollup_watermark WHERE resolution = ?",
                rs -> rs.next() ? rs.getTimestamp(1) : null, resolution);
        if (ts != null) {
            return ts.toInstant();
        }
        // First run: start at the oldest sample we hold rather than the epoch.
        Instant start = store.oldestSampleAt().map(i -> Rollups.floor(i, step)).orElse(cutoff);
        jdbc.update("INSERT INTO rollup_watermark (resolution, processed_to) VALUES (?, ?)",
                resolution, Timestamp.from(start));
        return start;
    }

    private void setWatermark(String resolution, Instant to) {
        jdbc.update("UPDATE rollup_watermark SET processed_to = ? WHERE resolution = ?",
                Timestamp.from(to), resolution);
    }

    private static Duration parse(String resolution) {
        char unit = resolution.charAt(resolution.length() - 1);
        long n = Long.parseLong(resolution.substring(0, resolution.length() - 1));
        return switch (unit) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("unsupported resolution: " + resolution);
        };
    }
}
