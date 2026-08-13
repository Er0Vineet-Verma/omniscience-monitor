package com.omniscience.collector.store;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.omniscience.collector.model.Sample;
import com.omniscience.collector.model.StoredSample;

/** H2/Postgres-portable store: plain SQL, batched inserts, no vendor extensions. */
@Component
public class JdbcMetricStore implements MetricStore {

    private final JdbcTemplate jdbc;

    public JdbcMetricStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(List<StoredSample> samples) {
        jdbc.batchUpdate(
                "INSERT INTO metric_sample (series_id, ts, metric_value) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        StoredSample s = samples.get(i);
                        ps.setLong(1, s.seriesId());
                        ps.setTimestamp(2, Timestamp.from(s.ts()));
                        ps.setDouble(3, s.value());
                    }

                    @Override
                    public int getBatchSize() {
                        return samples.size();
                    }
                });
    }

    @Override
    public void writeHostSnapshot(String orgId, String host, Instant capturedAt, String payloadJson) {
        int updated = jdbc.update(
                "UPDATE host_snapshot SET captured_at = ?, payload_json = ? WHERE org_id = ? AND host = ?",
                Timestamp.from(capturedAt), payloadJson, orgId, host);
        if (updated == 0) {
            jdbc.update("INSERT INTO host_snapshot (org_id, host, captured_at, payload_json) VALUES (?, ?, ?, ?)",
                    orgId, host, Timestamp.from(capturedAt), payloadJson);
        }
    }

    @Override
    public Optional<Sample> latest(String metric, String host) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT se.org_id, se.metric, se.tags, sa.metric_value, sa.ts
                    FROM metric_sample sa
                    JOIN metric_series se ON se.id = sa.series_id
                    WHERE se.metric = ? AND se.tags LIKE ?
                    ORDER BY sa.ts DESC FETCH FIRST 1 ROWS ONLY
                    """,
                    (rs, rowNum) -> new Sample(
                            rs.getString("org_id"),
                            rs.getString("metric"),
                            Map.of("host", host),
                            rs.getDouble("metric_value"),
                            rs.getTimestamp("ts").toInstant()),
                    metric, hostTagPattern(host)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> activeHosts(String metric, Instant since) {
        return jdbc.queryForList("""
                SELECT DISTINCT se.tags FROM metric_series se
                WHERE se.metric = ? AND se.last_seen >= ?
                """, String.class, metric, Timestamp.from(since))
                .stream()
                .map(JdbcMetricStore::hostFromTags)
                .filter(h -> !h.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    public List<RawPoint> rawBetween(Instant from, Instant to) {
        return jdbc.query(
                "SELECT series_id, ts, metric_value FROM metric_sample WHERE ts >= ? AND ts < ? ORDER BY ts",
                (rs, rowNum) -> new RawPoint(
                        rs.getLong("series_id"),
                        rs.getTimestamp("ts").toInstant(),
                        rs.getDouble("metric_value")),
                Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public List<com.omniscience.collector.promql.Evaluator.DataSource.SeriesRef> findSeries(String orgId, String metric) {
        return jdbc.query("SELECT id, tags FROM metric_series WHERE org_id = ? AND metric = ?",
                (rs, rowNum) -> new com.omniscience.collector.promql.Evaluator.DataSource.SeriesRef(
                        rs.getLong("id"), parseTags(rs.getString("tags"))),
                orgId, metric);
    }

    @Override
    public List<RawPoint> samplesFor(List<Long> seriesIds, Instant from, Instant to) {
        if (seriesIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", seriesIds.stream().map(id -> "?").toList());
        Object[] args = new Object[seriesIds.size() + 2];
        for (int i = 0; i < seriesIds.size(); i++) {
            args[i] = seriesIds.get(i);
        }
        args[seriesIds.size()] = Timestamp.from(from);
        args[seriesIds.size() + 1] = Timestamp.from(to);
        return jdbc.query(
                "SELECT series_id, ts, metric_value FROM metric_sample WHERE series_id IN (" + placeholders
                        + ") AND ts >= ? AND ts <= ? ORDER BY ts",
                (rs, rowNum) -> new RawPoint(rs.getLong("series_id"), rs.getTimestamp("ts").toInstant(),
                        rs.getDouble("metric_value")),
                args);
    }

    @Override
    public List<String> activeHostsForOrg(String orgId, Instant since) {
        return jdbc.queryForList(
                "SELECT DISTINCT tags FROM metric_series WHERE org_id = ? AND last_seen >= ?",
                String.class, orgId, Timestamp.from(since))
                .stream()
                .map(JdbcMetricStore::hostFromTags)
                .filter(h -> !h.isEmpty())
                .distinct()
                .toList();
    }

    private static Map<String, String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : tags.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    @Override
    public Optional<Double> percentile(String metric, String host, Instant since, double q) {
        List<Double> values = jdbc.queryForList("""
                SELECT sa.metric_value FROM metric_sample sa
                JOIN metric_series se ON se.id = sa.series_id
                WHERE se.metric = ? AND se.tags LIKE ? AND sa.ts >= ?
                ORDER BY sa.metric_value
                """, Double.class, metric, hostTagPattern(host), Timestamp.from(since));
        return Percentiles.nearestRank(values, q);
    }

    @Override
    public Optional<Instant> oldestSampleAt() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MIN(ts) FROM metric_sample", Timestamp.class))
                .map(Timestamp::toInstant);
    }

    @Override
    public long totalSamples() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM metric_sample", Long.class);
        return count != null ? count : 0;
    }

    @Override
    public long totalRollups() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM metric_rollup", Long.class);
        return count != null ? count : 0;
    }

    @Override
    public Optional<Instant> lastSampleAt() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(ts) FROM metric_sample", Timestamp.class))
                .map(Timestamp::toInstant);
    }

    private static String hostTagPattern(String host) {
        return "%host=" + host + "%";
    }

    private static String hostFromTags(String tags) {
        for (String pair : tags.split(",")) {
            if (pair.startsWith("host=")) {
                return pair.substring("host=".length());
            }
        }
        return "";
    }
}
