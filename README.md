# Omniscience Monitor

**A metrics-first observability platform that files its own tickets.**

An agent collects host metrics, a stateless collector authenticates and ingests them, an interval
processor rolls them up, and an alert engine evaluates rules written in a PromQL subset built here
from scratch — lexer, parser and evaluator, with no parsing dependency. When a rule fires, the
incident bridge opens a ticket in
[Omniscience Incident](https://github.com/Er0Vineet-Verma/omniscience-incident-management) with a
priority, an owner and a running SLA clock, and resolves it again when the metric recovers.

```
  agent ──▶ collector ──▶ bus ──▶ interval processor ──▶ metric store
 (OSHI)    (stateless)                                        │
                                                              ▼
                                                        alert engine
                                                              │  episode
                                                              ▼
                                                       incident bridge ──▶ INC-xxxxx
```

**Measured, end to end:** burning CPU on the development machine produced an assigned P1 incident
with the SLA clock running in **89 seconds**, and the incident auto-resolved with a recovery
comment once the load stopped. Left running unattended, the platform caught organic CPU spikes with
nobody present.

---

## Why it exists

A dashboard only tells you something is wrong while somebody is looking at it. Detection has to be
unattended, and it has to end in a ticket with an owner and a deadline, or nothing happens at three
in the morning. This project is the detection half of that; the incident platform is the
management half.

---

## How it works

### Ingestion
On the metrics path the collector is stateless: authenticate, stamp identity, validate, enqueue. It does no
database work there, which is what lets it scale horizontally (the per-host process snapshot is still written synchronously on the request thread — a known inconsistency). Identity is taken from the credential and
**never from the payload** — the `host` tag is overwritten with the agent's declared identity, so a
payload cannot write into another host's series.

Backpressure is explicit: the collector load-sheds with `429` when the bounded bus is full, and
rejects any batch over 5,000 points with `400`. The agent keeps a bounded ring buffer and **drops the
oldest** sample when the collector is unreachable, because for alerting, recent data is worth more
than old data.

### Storage and cardinality
A metric name plus a canonicalised, sorted tag set resolves to a `series_id`; samples reference the
series. Cardinality guards reject at ingest with a `400` that names the problem — a per-metric
tag-key limit and a per-org active-series budget — because one tag with a million values is a
million series. Per-process data is stored as a **snapshot**, one row per host, not as time series,
for the same reason.

Rollups store `count / sum / min / max / last` — never an average, because a mean of means weights
buckets equally regardless of how many samples they hold. Percentiles are therefore computed over
raw samples inside the raw retention window; they cannot be rolled up.

### Alerting
```
OK ──violation──▶ PENDING ──held for `for`──▶ FIRING ──recovery held──▶ RESOLVED ──cooldown──▶ OK
```
Three per-rule timers, each preventing a specific failure:

| Guard | Prevents |
|---|---|
| Firing guard (`for`) | one unlucky sample paging somebody |
| Resolve hysteresis | a metric oscillating around the threshold churning tickets open and closed |
| Re-fire cooldown | a second ticket opening while the first recovery is still being written |

The **episode** starts when the violation began, not when FIRING was declared. That is what makes
the idempotency key — `rule + host + episode_start` — stable across every sweep of a long incident.
A window-based key was rejected in design review because it would mint one ticket per evaluation
window. A flap-simulation test runs the real evaluator, the real state machine and the real bridge
and asserts exactly one ticket across sixteen sweeps of oscillation.

The incident platform is downstream and **never load-bearing**: if it is unreachable, episodes queue
and retry on the next evaluation sweep, and alert evaluation carries on.

### Rule language
A genuine PromQL subset — metric selectors, label matchers (`= != =~ !~`), `rate()`/`increase()`
over a range, the five aggregators, and comparison predicates:

```promql
sum(rate(http_requests_total{status=~"5.."}[5m])) / sum(rate(http_requests_total[5m])) > 0.05
```

Three semantics were chosen deliberately, and each has a test:

1. **No data is not a breach.** A missing series, or samples older than the lookback, yield no
   decision. Absence of signal is not evidence of failure.
2. **Ambiguity is an error.** More than one series in a scalar position throws, naming the fix.
3. **Division by zero yields NaN, not Infinity** — Infinity would compare as a breach against any
   threshold.

Every rule write parses first, so an unevaluable rule can never be stored.

---

## Running it locally

Requires JDK 21 and Maven. No Docker, no Kafka, no Postgres — see ADR-001 below.

```bash
mvn -q package
```

```bash
# 1. the incident platform on :8080 (the bridge's downstream)
cd ../../Incident\ Management/incident-management-system/backend && java -jar target/ims-backend-1.0.0.jar

# 2. the collector on :8081 — run from collector/, the working directory is load-bearing
cd collector && java -jar target/monitor-collector-0.1.0.jar

# 3. the agent — reads OMNI_AGENT_TOKEN, which must match MONITOR_AGENT_TOKEN in collector/.env
cd agent && OMNI_AGENT_TOKEN=<token> java -jar target/monitor-agent-0.1.0.jar
```

Copy `collector/.env.example` to `collector/.env` first. There are no committed defaults: the
collector fails fast on a missing secret, and the agent refuses to start without a token — there is
no anonymous ingest.

Check ingestion with `GET http://localhost:8081/api/v1/status`. Rules are managed through
`/api/v1/rules`, with a `POST /validate` endpoint that parses an expression without saving it.

---

## Architecture decisions

Recorded as ADRs, each with the alternative that was rejected.

| # | Decision |
|---|---|
| 001 | **Interface seams before infrastructure.** `MetricBus`, `AgentAuthenticator` and `MetricStore` each have a local implementation (in-memory queue, bearer token, H2) and a designed production one (Kafka, mTLS, Postgres/TimescaleDB) that is not yet built. The local profile cold-boots in under five seconds. |
| 002 | **Metrics only in v1.** Logs already live in the incident platform; traces are deferred. |
| 003 | OTLP-compatible collector plus one thin agent, rather than a proprietary agent protocol. |
| 004 | **The interval processor owns downsampling**, not the database — one owner, and portable SQL that behaves identically on H2 and Postgres. |
| 005 | Heavy infrastructure is exercised in CI rather than installed locally. |
| 006 | The console is its own frontend application, reusing conventions but not the sibling product's theme. |
| 007 | **The incident platform is the suite's identity provider**, moving from symmetric HS512 to **RS256** so the collector validates with the public key only and can never mint a token. Sharing a symmetric secret was rejected: either service could then forge the other's tokens. |
| 008 | **A hand-written PromQL subset** rather than a dependency or a structured rule builder. |
| 009 | **Fleet scale comes from synthetic agents through the real authenticated path.** Fabricated rows are never inserted to populate a display. |

---

## Status

Built on a 90-hour budget across six weeks. Weeks 1–3 are complete and verified against the running
stack; weeks 4–6 are not.

| Slice | State |
|---|---|
| Agent — OSHI collection, ring buffer, backoff, heartbeat | ✅ |
| Ingestion — authentication, validation, load shedding | ✅ |
| Storage — series model, rollups, cardinality guards, retention, percentiles | ✅ |
| Alert engine — state machine, PromQL subset, rule CRUD | ✅ |
| Incident bridge — episode keys, auto-resolve | ✅ |
| Security & tenancy — enrollment, mTLS, row-level security | ⬜ not built |
| Console & published benchmarks | ⬜ not built |

**46 tests**, green in CI on every push. Rollup correctness was proven by full reconciliation rather
than sampling: across all 2,414 one-minute buckets, count, sum and min/max matched raw aggregation
with zero mismatches.

### Known gaps

Stated plainly, because a monitoring system's limits are part of its specification.

- **No throughput benchmark exists.** The load generator is not built, so no metrics-per-second
  figure is published here or anywhere else.
- Ingest is JSON rather than OTLP protobuf, so the compatibility claim in ADR-003 is unproven.
- Alert state is in memory, so a restart can re-open an episode and duplicate an incident.
- `agent.up` is constant, and the evaluator correctly refuses to fire on absent data — so an agent
  going offline is currently not alertable. It needs a staleness rule type.
- Two store queries lack a tenant predicate and host matching is a substring comparison. The
  cross-tenant leak test is to be written **red** against both before they are fixed.
- There is no boot test and there are no controller tests, so the build passes even if the
  application cannot start.

The CI workflow deliberately omits the testcontainers job the architecture calls for: the Kafka and
Postgres adapters do not exist yet, and a job that goes green without exercising anything is worse
than no job at all.

---

## Licence

MIT
