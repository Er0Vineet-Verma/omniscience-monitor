-- Week-1 baseline. Existing databases are baselined at this version (flyway
-- baseline-on-migrate), so this script only runs on a fresh database.
CREATE TABLE IF NOT EXISTS metric_sample (
    id           IDENTITY PRIMARY KEY,
    org_id       VARCHAR(64)  NOT NULL,
    host         VARCHAR(255) NOT NULL,
    metric       VARCHAR(255) NOT NULL,
    metric_value DOUBLE       NOT NULL,
    ts           TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sample_lookup ON metric_sample (metric, host, ts);

CREATE TABLE IF NOT EXISTS alert_episode (
    id              IDENTITY PRIMARY KEY,
    episode_key     VARCHAR(512) NOT NULL UNIQUE,
    rule_id         VARCHAR(128) NOT NULL,
    org_id          VARCHAR(64)  NOT NULL,
    host            VARCHAR(255) NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    incident_id     BIGINT,
    incident_number VARCHAR(32),
    started_at      TIMESTAMP    NOT NULL,
    resolved_at     TIMESTAMP
);
