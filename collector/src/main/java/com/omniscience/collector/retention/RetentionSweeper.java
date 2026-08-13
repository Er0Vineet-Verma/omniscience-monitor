package com.omniscience.collector.retention;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omniscience.collector.config.MonitorProps;

/**
 * Raw samples age out quickly; rollups persist. This is the trade the whole
 * storage slice exists to make — full fidelity for recent debugging, cheap shape
 * for history — and it is why percentiles are only answerable inside the raw window.
 */
@Component
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    private final JdbcTemplate jdbc;
    private final MonitorProps.Retention config;

    public RetentionSweeper(JdbcTemplate jdbc, MonitorProps props) {
        this.jdbc = jdbc;
        this.config = props.retention();
    }

    @Scheduled(fixedDelayString = "${monitor.retention.interval-ms}", initialDelay = 120000)
    public void sweep() {
        Instant now = Instant.now();
        int rawDeleted = jdbc.update("DELETE FROM metric_sample WHERE ts < ?",
                Timestamp.from(now.minus(Duration.ofDays(config.rawDays()))));
        int rollupDeleted = jdbc.update("DELETE FROM metric_rollup WHERE bucket_ts < ?",
                Timestamp.from(now.minus(Duration.ofDays(config.rollupDays()))));
        // A series with no remaining data is dead weight in the cardinality budget.
        int seriesDeleted = jdbc.update("""
                DELETE FROM metric_series WHERE id NOT IN (SELECT DISTINCT series_id FROM metric_sample)
                  AND id NOT IN (SELECT DISTINCT series_id FROM metric_rollup)
                """);
        if (rawDeleted + rollupDeleted + seriesDeleted > 0) {
            log.info("retention sweep — {} raw, {} rollup, {} empty series removed",
                    rawDeleted, rollupDeleted, seriesDeleted);
        }
    }
}
