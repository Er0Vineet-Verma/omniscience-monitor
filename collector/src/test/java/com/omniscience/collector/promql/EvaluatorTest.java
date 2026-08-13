package com.omniscience.collector.promql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.omniscience.collector.store.MetricStore.RawPoint;

class EvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final String ORG = "org-demo";

    /** In-memory data source: series definitions plus their points. */
    private static final class FakeData implements Evaluator.DataSource {
        private final Map<String, List<SeriesRef>> byMetric = new HashMap<>();
        private final Map<Long, List<RawPoint>> pointsBySeries = new HashMap<>();
        private long nextId = 1;

        long addSeries(String metric, Map<String, String> tags) {
            long id = nextId++;
            byMetric.computeIfAbsent(metric, k -> new ArrayList<>()).add(new SeriesRef(id, tags));
            pointsBySeries.put(id, new ArrayList<>());
            return id;
        }

        void addPoint(long seriesId, int secondsBeforeNow, double value) {
            pointsBySeries.get(seriesId).add(
                    new RawPoint(seriesId, NOW.minusSeconds(secondsBeforeNow), value));
        }

        @Override
        public List<SeriesRef> findSeries(String orgId, String metric) {
            return ORG.equals(orgId) ? byMetric.getOrDefault(metric, List.of()) : List.of();
        }

        @Override
        public List<RawPoint> samples(List<Long> seriesIds, Instant from, Instant to) {
            List<RawPoint> out = new ArrayList<>();
            for (long id : seriesIds) {
                for (RawPoint p : pointsBySeries.getOrDefault(id, List.of())) {
                    if (!p.ts().isBefore(from) && !p.ts().isAfter(to)) {
                        out.add(p);
                    }
                }
            }
            return out;
        }
    }

    private FakeData data;
    private Evaluator evaluator;

    @BeforeEach
    void setUp() {
        data = new FakeData();
        evaluator = new Evaluator(data, Duration.ofSeconds(90));
    }

    private boolean matches(String expression, String host) {
        return evaluator.matches(Parser.parseCondition(expression), ORG, host, NOW).orElseThrow();
    }

    @Test
    void thresholdUsesMostRecentSample() {
        long id = data.addSeries("system.cpu.load", Map.of("host", "h1"));
        data.addPoint(id, 60, 0.10);
        data.addPoint(id, 10, 0.95); // newest

        assertTrue(matches("system.cpu.load > 0.9", "h1"));
        assertFalse(matches("system.cpu.load > 0.99", "h1"));
    }

    @Test
    void hostScopingKeepsTenantsHostsApart() {
        long h1 = data.addSeries("system.cpu.load", Map.of("host", "h1"));
        long h2 = data.addSeries("system.cpu.load", Map.of("host", "h2"));
        data.addPoint(h1, 10, 0.95);
        data.addPoint(h2, 10, 0.10);

        assertTrue(matches("system.cpu.load > 0.9", "h1"));
        assertFalse(matches("system.cpu.load > 0.9", "h2"));
    }

    @Test
    void noDataYieldsNoDecisionRatherThanABreach() {
        data.addSeries("system.cpu.load", Map.of("host", "h1"));
        assertTrue(evaluator.matches(Parser.parseCondition("system.cpu.load > 0.9"), ORG, "h1", NOW).isEmpty());
    }

    @Test
    void staleSamplesOutsideLookbackAreIgnored() {
        long id = data.addSeries("system.cpu.load", Map.of("host", "h1"));
        data.addPoint(id, 600, 0.99); // 10 minutes old, lookback is 90s
        assertTrue(evaluator.matches(Parser.parseCondition("system.cpu.load > 0.9"), ORG, "h1", NOW).isEmpty());
    }

    @Test
    void labelMatchersFilterSeries() {
        long ok = data.addSeries("http_requests", Map.of("host", "h1", "status", "500"));
        long ignored = data.addSeries("http_requests", Map.of("host", "h1", "status", "200"));
        data.addPoint(ok, 10, 7);
        data.addPoint(ignored, 10, 1000);

        assertTrue(matches("http_requests{status=~\"5..\"} > 5", "h1"));

        // A matcher that selects nothing is absence of data, not a false condition —
        // so it yields no decision rather than "not breaching".
        assertTrue(evaluator.matches(
                Parser.parseCondition("http_requests{status=\"404\"} > 5"), ORG, "h1", NOW).isEmpty());
    }

    @Test
    void aggregationCollapsesMultipleSeries() {
        long a = data.addSeries("http_requests", Map.of("host", "h1", "route", "/a"));
        long b = data.addSeries("http_requests", Map.of("host", "h1", "route", "/b"));
        data.addPoint(a, 10, 4);
        data.addPoint(b, 10, 6);

        assertTrue(matches("sum(http_requests) > 9", "h1"));
        assertTrue(matches("max(http_requests) > 5", "h1"));
        assertFalse(matches("min(http_requests) > 5", "h1"));
        assertTrue(matches("avg(http_requests) == 5", "h1"));
        assertTrue(matches("count(http_requests) == 2", "h1"));
    }

    /** Ambiguity is an error, not a coin flip. */
    @Test
    void multipleSeriesWithoutAggregationIsRejected() {
        long a = data.addSeries("http_requests", Map.of("host", "h1", "route", "/a"));
        long b = data.addSeries("http_requests", Map.of("host", "h1", "route", "/b"));
        data.addPoint(a, 10, 4);
        data.addPoint(b, 10, 6);

        PromQLException e = assertThrows(PromQLException.class, () -> matches("http_requests > 1", "h1"));
        assertTrue(e.getMessage().contains("wrap it in sum()"));
    }

    @Test
    void rateComputesPerSecondIncreaseOverTheWindow() {
        long id = data.addSeries("http_total", Map.of("host", "h1"));
        data.addPoint(id, 300, 0);
        data.addPoint(id, 0, 600); // +600 over a 5m window = 2/sec

        assertTrue(matches("rate(http_total[5m]) > 1.9", "h1"));
        assertFalse(matches("rate(http_total[5m]) > 2.1", "h1"));
    }

    @Test
    void rateToleratesCounterResets() {
        long id = data.addSeries("http_total", Map.of("host", "h1"));
        data.addPoint(id, 300, 100);
        data.addPoint(id, 200, 200);  // +100
        data.addPoint(id, 100, 50);   // reset: counts as +50
        data.addPoint(id, 0, 150);    // +100
        // total increase 250 over 300s ≈ 0.833/sec — a naive last-minus-first would give 0.167
        assertTrue(matches("rate(http_total[5m]) > 0.8", "h1"));
        assertFalse(matches("rate(http_total[5m]) > 0.9", "h1"));
    }

    /** The design's error-ratio expression, evaluated end to end. */
    @Test
    void errorRatioExpressionFromTheDesign() {
        long errors = data.addSeries("http_requests_total", Map.of("host", "h1", "status", "500"));
        long ok = data.addSeries("http_requests_total", Map.of("host", "h1", "status", "200"));
        data.addPoint(errors, 300, 0);
        data.addPoint(errors, 0, 60);   // 60 errors
        data.addPoint(ok, 300, 0);
        data.addPoint(ok, 0, 540);      // 540 successes

        String expr = "sum(rate(http_requests_total{status=~\"5..\"}[5m])) "
                + "/ sum(rate(http_requests_total[5m])) > 0.05";
        assertTrue(matches(expr, "h1")); // 60/600 = 10%

        String stricter = expr.replace("0.05", "0.2");
        assertFalse(matches(stricter, "h1"));
    }

    /** Infinity would compare as a breach against any threshold, so division by zero must not decide. */
    @Test
    void divisionByZeroYieldsNoDecision() {
        long id = data.addSeries("errors", Map.of("host", "h1"));
        data.addPoint(id, 10, 5);
        assertTrue(evaluator.matches(
                Parser.parseCondition("errors / 0 > 0.05"), ORG, "h1", NOW).isEmpty());
    }

    @Test
    void reportsTheObservedLeftHandValueForIncidentContext() {
        long id = data.addSeries("system.cpu.load", Map.of("host", "h1"));
        data.addPoint(id, 5, 0.97);
        assertEquals(0.97,
                evaluator.leftValue(Parser.parseCondition("system.cpu.load > 0.9"), ORG, "h1", NOW).orElseThrow(),
                1e-9);
    }
}
