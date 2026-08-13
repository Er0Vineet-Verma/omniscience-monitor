-- Slice 4: normalize (metric name + tag set) into a series; samples reference it.
-- `host` stops being a column and becomes an ordinary tag, which is what makes
-- the model extensible without schema changes — and what makes cardinality the
-- thing that must be governed.
--
-- series_key is the canonical string "org|metric|k=v,k=v" rather than a hash:
-- portable across H2/Postgres (no digest() / HASH() divergence) and debuggable
-- by eye. Tag pairs are sorted at write time so the key is stable.

CREATE TABLE metric_series (
    id         IDENTITY PRIMARY KEY,
    org_id     VARCHAR(64)   NOT NULL,
    metric     VARCHAR(255)  NOT NULL,
    tags       VARCHAR(1024) NOT NULL,
    series_key VARCHAR(1400) NOT NULL,
    first_seen TIMESTAMP     NOT NULL,
    last_seen  TIMESTAMP     NOT NULL,
    CONSTRAINT uq_series_key UNIQUE (series_key)
);
CREATE INDEX idx_series_org_metric ON metric_series (org_id, metric);

-- Backfill one series per distinct (org, metric, host) already in the table.
INSERT INTO metric_series (org_id, metric, tags, series_key, first_seen, last_seen)
SELECT org_id,
       metric,
       'host=' || host,
       org_id || '|' || metric || '|host=' || host,
       MIN(ts),
       MAX(ts)
FROM metric_sample
GROUP BY org_id, metric, host;

ALTER TABLE metric_sample ADD COLUMN series_id BIGINT;

UPDATE metric_sample s
SET series_id = (SELECT x.id FROM metric_series x
                 WHERE x.org_id = s.org_id
                   AND x.metric = s.metric
                   AND x.tags = 'host=' || s.host);

DELETE FROM metric_sample WHERE series_id IS NULL;

ALTER TABLE metric_sample ALTER COLUMN series_id SET NOT NULL;
DROP INDEX IF EXISTS idx_sample_lookup;
ALTER TABLE metric_sample DROP COLUMN org_id;
ALTER TABLE metric_sample DROP COLUMN host;
ALTER TABLE metric_sample DROP COLUMN metric;

CREATE INDEX idx_sample_series_ts ON metric_sample (series_id, ts);
CREATE INDEX idx_sample_ts ON metric_sample (ts);
