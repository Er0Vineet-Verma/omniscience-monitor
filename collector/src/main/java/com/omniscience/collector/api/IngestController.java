package com.omniscience.collector.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniscience.collector.auth.AgentAuthenticator;
import com.omniscience.collector.bus.MetricBus;
import com.omniscience.collector.model.Sample;
import com.omniscience.collector.series.CardinalityGuard;
import com.omniscience.collector.store.MetricStore;

/**
 * Stateless edge: authenticate, stamp tenancy from the credential (never the
 * payload), enforce cardinality, enqueue. Bus full -> 429 load-shed.
 *
 * Process lists arrive alongside metrics but are stored as a per-host snapshot,
 * not as series — see V4__host_snapshot.sql for why.
 */
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private static final int MAX_POINTS_PER_BATCH = 5000;

    private final AgentAuthenticator authenticator;
    private final MetricBus bus;
    private final CardinalityGuard guard;
    private final MetricStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public IngestController(AgentAuthenticator authenticator, MetricBus bus,
                            CardinalityGuard guard, MetricStore store) {
        this.authenticator = authenticator;
        this.bus = bus;
        this.guard = guard;
        this.store = store;
    }

    public record MetricPointDto(String name, double value, long ts, Map<String, String> tags) {
    }

    public record ProcessDto(int pid, String command, String user, double cpuPercent, long rssMb) {
    }

    public record IngestRequest(String agentId, String host, List<MetricPointDto> metrics,
                                List<ProcessDto> processes) {
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody IngestRequest request) {

        var principal = authenticator.authenticate(authorization);
        if (principal.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid or missing agent token"));
        }
        if (request.host() == null || request.host().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "host is required"));
        }

        String orgId = principal.get().orgId();
        List<Sample> samples = new ArrayList<>();

        if (request.metrics() != null) {
            if (request.metrics().size() > MAX_POINTS_PER_BATCH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "batch exceeds " + MAX_POINTS_PER_BATCH + " points"));
            }
            for (MetricPointDto m : request.metrics()) {
                Map<String, String> tags = new HashMap<>();
                if (m.tags() != null) {
                    tags.putAll(m.tags());
                }
                // host is a tag, and the agent-declared value always wins over a
                // payload-supplied one so an agent cannot write another host's series.
                tags.put("host", request.host());

                String violation = guard.check(orgId, m.name(), tags);
                if (violation != null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "cardinality: " + violation,
                                    "rejected", guard.rejectedCount()));
                }
                samples.add(new Sample(orgId, m.name(), tags, m.value(), Instant.ofEpochMilli(m.ts())));
            }
        }

        if (!samples.isEmpty() && !bus.publish(samples)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "10")
                    .body(Map.of("error", "ingest saturated — retry later"));
        }

        int processCount = 0;
        if (request.processes() != null && !request.processes().isEmpty()) {
            try {
                store.writeHostSnapshot(orgId, request.host(), Instant.now(),
                        mapper.writeValueAsString(request.processes()));
                processCount = request.processes().size();
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid process payload"));
            }
        }
        return ResponseEntity.accepted()
                .body(Map.of("accepted", samples.size(), "processes", processCount));
    }
}
