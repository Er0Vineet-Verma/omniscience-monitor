package com.omniscience.collector.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omniscience.collector.bridge.IncidentBridge;
import com.omniscience.collector.config.MonitorProps;
import com.omniscience.collector.promql.Evaluator;
import com.omniscience.collector.promql.Expr;
import com.omniscience.collector.promql.Parser;
import com.omniscience.collector.promql.PromQLException;
import com.omniscience.collector.rule.AlertRule;
import com.omniscience.collector.rule.AlertRuleService;
import com.omniscience.collector.store.MetricStore;

/**
 * Scheduled sweep over every enabled rule × every active host, each pair holding
 * its own state machine with that rule's firing guard, resolve hysteresis and
 * cooldown.
 *
 * Parsed expressions are cached by text, so a rule is lexed and parsed once rather
 * than on every sweep of every host.
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final MetricStore store;
    private final IncidentBridge bridge;
    private final AlertRuleService rules;
    private final Evaluator evaluator;
    private final String orgId;

    private final Map<String, AlertStateMachine> machines = new ConcurrentHashMap<>();
    private final Map<String, Expr.Comparison> parsed = new ConcurrentHashMap<>();

    public AlertEvaluator(MetricStore store, IncidentBridge bridge, AlertRuleService rules, MonitorProps props) {
        this.store = store;
        this.bridge = bridge;
        this.rules = rules;
        this.orgId = props.orgId();
        this.evaluator = new Evaluator(new StoreDataSource(store), Duration.ofSeconds(90));
    }

    @Scheduled(fixedDelayString = "${monitor.alert.evaluate-ms}", initialDelay = 20000)
    public void evaluate() {
        Instant now = Instant.now();
        List<AlertRule> enabled = rules.findEnabled(orgId);
        if (enabled.isEmpty()) {
            return;
        }
        List<String> hosts = store.activeHostsForOrg(orgId, now.minus(Duration.ofMinutes(5)));
        for (AlertRule rule : enabled) {
            for (String host : hosts) {
                try {
                    evaluateOne(rule, host, now);
                } catch (PromQLException e) {
                    log.error("rule '{}' is not evaluable: {}", rule.ruleKey(), e.getMessage());
                } catch (Exception e) {
                    log.error("rule '{}' failed on host {}: {}", rule.ruleKey(), host, e.getMessage());
                }
            }
        }
    }

    /** Visible for tests: evaluates one rule/host pair at an injected instant. */
    public void evaluateOne(AlertRule rule, String host, Instant now) {
        Expr.Comparison condition = parsed.computeIfAbsent(rule.expression(), Parser::parseCondition);
        Optional<Boolean> violating = evaluator.matches(condition, rule.orgId(), host, now);
        if (violating.isEmpty()) {
            return; // no data — never treated as a breach
        }

        AlertStateMachine machine = machines.computeIfAbsent(key(rule, host), k -> new AlertStateMachine(
                rule.forDuration(), rule.resolveDuration(), rule.cooldown()));

        AlertStateMachine.Transition transition = machine.evaluate(now, violating.get());
        double observed = evaluator.leftValue(condition, rule.orgId(), host, now).orElse(Double.NaN);

        if (transition instanceof AlertStateMachine.Fired fired) {
            log.warn("ALERT FIRING: {} on {} — {} (observed {}, episode start {})",
                    rule.ruleKey(), host, rule.expression(), observed, fired.episodeStart());
        } else if (transition instanceof AlertStateMachine.Resolved resolved) {
            log.info("ALERT RESOLVED: {} on {} — sustained recovery (episode start {})",
                    rule.ruleKey(), host, resolved.episodeStart());
            bridge.resolveEpisode(rule, host, resolved.episodeStart(), observed);
            return;
        }

        // While FIRING, ensureOpen runs every sweep: idempotent via the episode key,
        // and doubling as the retry path for a downstream that was unreachable.
        if (machine.state() == AlertStateMachine.State.FIRING) {
            bridge.ensureOpen(rule, host, machine.episodeStart(), observed);
        }
    }

    public List<Map<String, Object>> snapshot() {
        return machines.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "rule", e.getKey().substring(0, e.getKey().indexOf('|')),
                        "host", e.getKey().substring(e.getKey().indexOf('|') + 1),
                        "state", e.getValue().state().name(),
                        "episodeStart", String.valueOf(e.getValue().episodeStart())))
                .toList();
    }

    private static String key(AlertRule rule, String host) {
        return rule.ruleKey() + "|" + host;
    }

    /** Adapts the storage seam to the evaluator's data needs. */
    private record StoreDataSource(MetricStore store) implements Evaluator.DataSource {

        @Override
        public List<SeriesRef> findSeries(String orgId, String metric) {
            return store.findSeries(orgId, metric);
        }

        @Override
        public List<MetricStore.RawPoint> samples(List<Long> seriesIds, Instant from, Instant to) {
            return store.samplesFor(seriesIds, from, to);
        }
    }
}
