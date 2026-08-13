-- ADR-004: the Interval Processor owns downsampling, not the database.
-- Rollup rows store count/sum/min/max/last — never an average, because averaging
-- averages is wrong and percentiles do not aggregate at all. P95/P99 are computed
-- over raw samples inside the raw retention window.

CREATE TABLE metric_rollup (
    id           IDENTITY PRIMARY KEY,
    series_id    BIGINT     NOT NULL,
    resolution   VARCHAR(8) NOT NULL,   -- 1m | 5m | 1h
    bucket_ts    TIMESTAMP  NOT NULL,   -- inclusive start of the bucket
    sample_count BIGINT     NOT NULL,
    sum_value    DOUBLE     NOT NULL,
    min_value    DOUBLE     NOT NULL,
    max_value    DOUBLE     NOT NULL,
    last_value   DOUBLE     NOT NULL,
    CONSTRAINT uq_rollup UNIQUE (series_id, resolution, bucket_ts)
);
CREATE INDEX idx_rollup_lookup ON metric_rollup (resolution, bucket_ts);
CREATE INDEX idx_rollup_series ON metric_rollup (series_id, resolution, bucket_ts);

-- One watermark per resolution: how far the processor has consumed raw samples.
CREATE TABLE rollup_watermark (
    resolution   VARCHAR(8) PRIMARY KEY,
    processed_to TIMESTAMP  NOT NULL
);
