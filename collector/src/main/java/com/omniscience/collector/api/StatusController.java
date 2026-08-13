package com.omniscience.collector.api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.omniscience.collector.alert.AlertEvaluator;
import com.omniscience.collector.bridge.IncidentBridge;
import com.omniscience.collector.bus.MetricBus;
import com.omniscience.collector.series.CardinalityGuard;
import com.omniscience.collector.series.SeriesRegistry;
import com.omniscience.collector.store.MetricStore;

/**
 * Operational status for verification and demos. Unauthenticated on the local
 * profile (localhost only); locked down with the rest of the internal API in week 4.
 */
@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final MetricStore store;
    private final MetricBus bus;
    private final AlertEvaluator evaluator;
    private final IncidentBridge bridge;
    private final SeriesRegistry registry;
    private final CardinalityGuard guard;

    public StatusController(MetricStore store, MetricBus bus, AlertEvaluator evaluator,
                            IncidentBridge bridge, SeriesRegistry registry, CardinalityGuard guard) {
        this.store = store;
        this.bus = bus;
        this.evaluator = evaluator;
        this.bridge = bridge;
        this.registry = registry;
        this.guard = guard;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", store.totalSamples());
        out.put("rollups", store.totalRollups());
        out.put("series", registry.seriesCount());
        out.put("cardinalityRejected", guard.rejectedCount());
        out.put("lastSampleAt", store.lastSampleAt().map(Object::toString).orElse(null));
        out.put("busDepth", bus.depth());
        out.put("busShed", bus.shedCount());
        out.put("alerts", evaluator.snapshot());
        out.put("episodes", bridge.recentEpisodes());
        return out;
    }

    /** Percentiles come from raw samples only — see Percentiles for why. */
    @GetMapping("/percentile")
    public Map<String, Object> percentile(@RequestParam String metric,
                                          @RequestParam String host,
                                          @RequestParam(defaultValue = "0.95") double q,
                                          @RequestParam(defaultValue = "60") int minutes) {
        Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", metric);
        out.put("host", host);
        out.put("q", q);
        out.put("windowMinutes", minutes);
        out.put("value", store.percentile(metric, host, since, q).orElse(null));
        out.put("source", "raw");
        return out;
    }
}
