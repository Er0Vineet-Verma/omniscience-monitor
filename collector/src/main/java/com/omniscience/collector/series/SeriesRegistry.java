package com.omniscience.collector.series;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Maps (org, metric, tags) to a numeric series id, cached in memory so the ingest
 * path does not hit the database per sample. The cache is warmed at startup and is
 * also what makes the cardinality budget check free.
 *
 * last_seen is not written per sample — that would double the write volume for no
 * benefit. Timestamps accumulate in memory and flush on a timer.
 */
@Component
public class SeriesRegistry {

    private static final Logger log = LoggerFactory.getLogger(SeriesRegistry.class);

    private final JdbcTemplate jdbc;
    private final Map<String, Long> idByKey = new ConcurrentHashMap<>();
    private final Map<Long, Instant> pendingLastSeen = new ConcurrentHashMap<>();

    public SeriesRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void warmCache() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, series_key FROM metric_series");
        for (Map<String, Object> row : rows) {
            idByKey.put((String) row.get("series_key"), ((Number) row.get("id")).longValue());
        }
        log.info("series cache warmed with {} series", idByKey.size());
    }

    public boolean isKnown(String orgId, String metric, Map<String, String> tags) {
        return idByKey.containsKey(SeriesKey.of(orgId, metric, SeriesKey.canonicalTags(tags)));
    }

    /** Resolves, creating the series row on first sight. Called on the drain path, not on ingest. */
    public long resolve(String orgId, String metric, Map<String, String> tags, Instant ts) {
        String canonical = SeriesKey.canonicalTags(tags);
        String key = SeriesKey.of(orgId, metric, canonical);
        Long cached = idByKey.get(key);
        if (cached != null) {
            pendingLastSeen.put(cached, ts);
            return cached;
        }
        long id = insertSeries(orgId, metric, canonical, key, ts);
        idByKey.put(key, id);
        pendingLastSeen.put(id, ts);
        return id;
    }

    private long insertSeries(String orgId, String metric, String canonical, String key, Instant ts) {
        try {
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO metric_series (org_id, metric, tags, series_key, first_seen, last_seen) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        new String[]{"id"});
                ps.setString(1, orgId);
                ps.setString(2, metric);
                ps.setString(3, canonical);
                ps.setString(4, key);
                ps.setTimestamp(5, Timestamp.from(ts));
                ps.setTimestamp(6, Timestamp.from(ts));
                return ps;
            }, holder);
            log.debug("new series #{} {}", holder.getKey(), key);
            return holder.getKey().longValue();
        } catch (Exception e) {
            // Lost a race with another writer — the unique constraint is the arbiter.
            Long existing = jdbc.queryForObject(
                    "SELECT id FROM metric_series WHERE series_key = ?", Long.class, key);
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void flushLastSeen() {
        if (pendingLastSeen.isEmpty()) {
            return;
        }
        Map<Long, Instant> batch = new HashMap<>(pendingLastSeen);
        pendingLastSeen.keySet().removeAll(batch.keySet());
        jdbc.batchUpdate("UPDATE metric_series SET last_seen = ? WHERE id = ?",
                batch.entrySet().stream()
                        .map(e -> new Object[]{Timestamp.from(e.getValue()), e.getKey()})
                        .toList());
    }

    public int seriesCount() {
        return idByKey.size();
    }
}
