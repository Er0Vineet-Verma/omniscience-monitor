package com.omniscience.collector.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.omniscience.collector.bridge.IncidentBridge;
import com.omniscience.collector.bridge.IncidentClient;
import com.omniscience.collector.config.MonitorProps;
import com.omniscience.collector.model.Sample;
import com.omniscience.collector.model.StoredSample;
import com.omniscience.collector.promql.Evaluator;
import com.omniscience.collector.rule.AlertRule;
import com.omniscience.collector.rule.AlertRuleService;
import com.omniscience.collector.store.MetricStore;

/**
 * The contract this whole design exists to protect: a metric oscillating around
 * its threshold must produce exactly ONE incident, not one per crossing.
 *
 * Runs the real AlertEvaluator, the real state machine and the real IncidentBridge
 * against a real (in-memory) database, with only the downstream incident system
 * faked so creations can be counted.
 */
class FlapSimulationTest {

    private static final String ORG = "org-demo";
    private static final String HOST = "test-host";
    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    /** Counts what the bridge actually sent downstream. */
    private static final class FakeIms implements IncidentClient {
        final List<String> created = new ArrayList<>();
        final List<Long> resolved = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        private long nextId = 5000;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CreatedIncident createIncident(String title, String description, String priority) {
            created.add(title);
            long id = nextId++;
            return new CreatedIncident(id, "INC-" + id);
        }

        @Override
        public void addComment(long incidentId, String body) {
            comments.add(body);
        }

        @Override
        public void resolve(long incidentId, String notes) {
            resolved.add(incidentId);
        }
    }

    /** Serves one series whose current value the test drives directly. */
    private static final class DrivenStore implements MetricStore {
        double current;

        @Override
        public List<Evaluator.DataSource.SeriesRef> findSeries(String orgId, String metric) {
            return ORG.equals(orgId) && "system.cpu.load".equals(metric)
                    ? List.of(new Evaluator.DataSource.SeriesRef(1L, Map.of("host", HOST)))
                    : List.of();
        }

        @Override
        public List<RawPoint> samplesFor(List<Long> seriesIds, Instant from, Instant to) {
            return seriesIds.isEmpty() ? List.of() : List.of(new RawPoint(1L, to, current));
        }

        @Override
        public List<String> activeHostsForOrg(String orgId, Instant since) {
            return List.of(HOST);
        }

        // Unused by this test.
        @Override public void write(List<StoredSample> samples) { }
        @Override public void writeHostSnapshot(String o, String h, Instant a, String p) { }
        @Override public Optional<Sample> latest(String metric, String host) { return Optional.empty(); }
        @Override public List<String> activeHosts(String metric, Instant since) { return List.of(); }
        @Override public List<RawPoint> rawBetween(Instant from, Instant to) { return List.of(); }
        @Override public Optional<Double> percentile(String m, String h, Instant s, double q) { return Optional.empty(); }
        @Override public Optional<Instant> oldestSampleAt() { return Optional.empty(); }
        @Override public long totalSamples() { return 0; }
        @Override public long totalRollups() { return 0; }
        @Override public Optional<Instant> lastSampleAt() { return Optional.empty(); }
    }

    private JdbcTemplate jdbc;
    private DrivenStore store;
    private FakeIms ims;
    private AlertEvaluator evaluator;
    private AlertRule rule;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("flap-" + System.nanoTime())
                .build();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(ds);
        store = new DrivenStore();
        ims = new FakeIms();

        AlertRuleService rules = new AlertRuleService(jdbc);
        MonitorProps props = new MonitorProps(null, ORG, 100, new MonitorProps.Alert(15000),
                null, null, null, null);
        evaluator = new AlertEvaluator(store, new IncidentBridge(jdbc, ims), rules, props);
        rule = rules.find(ORG, "cpu-high").orElseThrow(); // seeded by V5: for=60s, resolve=60s, cooldown=120s
    }

    private void tick(int secondsFromStart, double value) {
        store.current = value;
        evaluator.evaluateOne(rule, HOST, T0.plusSeconds(secondsFromStart));
    }

    @Test
    void oscillatingMetricProducesExactlyOneIncident() {
        tick(0, 0.95);    // violation begins
        tick(30, 0.96);   // still pending
        tick(60, 0.97);   // sustained 60s -> FIRING, incident created
        assertEquals(1, ims.created.size());

        tick(75, 0.85);   // dips below threshold — recovery starts
        tick(105, 0.95);  // spikes again before hysteresis completes
        tick(120, 0.88);  // dips again
        tick(150, 0.87);  // only 30s of recovery so far
        assertTrue(ims.resolved.isEmpty(), "must not resolve before sustained recovery");

        tick(180, 0.86);  // 60s of sustained recovery -> RESOLVED

        assertEquals(1, ims.created.size(), "flapping must never open a second incident");
        assertEquals(1, ims.resolved.size());
        assertEquals(1, ims.comments.size(), "exactly one recovery comment");

        Integer episodes = jdbc.queryForObject("SELECT COUNT(*) FROM alert_episode", Integer.class);
        assertEquals(1, episodes);
        assertEquals("RESOLVED",
                jdbc.queryForObject("SELECT state FROM alert_episode", String.class));
    }

    @Test
    void repeatedSweepsWhileFiringDoNotDuplicateTheIncident() {
        tick(0, 0.95);
        tick(60, 0.95); // fires
        for (int t = 75; t <= 300; t += 15) {
            tick(t, 0.95); // sixteen more sweeps, still firing
        }
        assertEquals(1, ims.created.size());
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(*) FROM alert_episode", Integer.class));
    }

    @Test
    void aGenuinelyNewEpisodeAfterCooldownOpensASecondIncident() {
        tick(0, 0.95);
        tick(60, 0.95);    // FIRING (episode 1)
        tick(120, 0.10);
        tick(180, 0.10);   // RESOLVED at 180, cooldown until 300

        tick(200, 0.99);   // inside cooldown — ignored
        assertEquals(1, ims.created.size());

        tick(300, 0.99);   // cooldown over, new violation begins
        tick(360, 0.99);   // sustained -> FIRING (episode 2)

        assertEquals(2, ims.created.size(), "a distinct outage deserves a distinct ticket");
        List<String> keys = jdbc.queryForList("SELECT episode_key FROM alert_episode ORDER BY id", String.class);
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1));
        assertTrue(keys.get(0).startsWith("cpu-high|" + HOST + "|"));
    }

    @Test
    void noDataDoesNotFire() {
        store.current = Double.NaN;
        // A NaN observation yields *no decision* (not false), so nothing fires and
        // the sweep completes without creating anything.
        for (int t = 0; t <= 300; t += 30) {
            tick(t, Double.NaN);
        }
        assertTrue(ims.created.isEmpty());
    }
}
