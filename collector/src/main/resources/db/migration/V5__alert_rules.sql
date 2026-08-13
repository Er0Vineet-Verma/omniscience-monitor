-- Slice 5: rules become data, not configuration. Each rule carries its own
-- firing guard, resolve hysteresis and cooldown, because "sustained for 5 minutes"
-- means something different for a CPU spike than for a certificate expiry.
CREATE TABLE alert_rule (
    id               IDENTITY PRIMARY KEY,
    org_id           VARCHAR(64)   NOT NULL,
    rule_key         VARCHAR(128)  NOT NULL,   -- stable; forms part of the episode idempotency key
    name             VARCHAR(255)  NOT NULL,
    expression       VARCHAR(2000) NOT NULL,   -- PromQL subset
    severity         VARCHAR(8)    NOT NULL,   -- P1..P4, mapped to incident priority
    for_seconds      INT           NOT NULL,
    resolve_seconds  INT           NOT NULL,
    cooldown_seconds INT           NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    CONSTRAINT uq_alert_rule UNIQUE (org_id, rule_key)
);

-- Seed the week-1 hardcoded rule so behaviour is unchanged across the upgrade and
-- existing open episodes keep matching their key ("cpu-high|<host>|<epoch>").
INSERT INTO alert_rule
  (org_id, rule_key, name, expression, severity, for_seconds, resolve_seconds, cooldown_seconds,
   enabled, created_at, updated_at)
VALUES
  ('org-demo', 'cpu-high', 'High CPU', 'system.cpu.load > 0.9', 'P1', 60, 60, 120,
   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
