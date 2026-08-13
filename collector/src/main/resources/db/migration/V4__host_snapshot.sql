-- Top Processes (Host Detail screen) is deliberately NOT modelled as time series.
-- Process names and PIDs are unbounded, so one series per process is exactly the
-- cardinality explosion slice 4 exists to prevent. It is a point-in-time snapshot:
-- one row per host, replaced on every agent cycle.
CREATE TABLE host_snapshot (
    id           IDENTITY PRIMARY KEY,
    org_id       VARCHAR(64)  NOT NULL,
    host         VARCHAR(255) NOT NULL,
    captured_at  TIMESTAMP    NOT NULL,
    payload_json CLOB         NOT NULL,
    CONSTRAINT uq_host_snapshot UNIQUE (org_id, host)
);
