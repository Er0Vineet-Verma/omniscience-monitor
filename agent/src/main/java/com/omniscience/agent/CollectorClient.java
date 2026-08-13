package com.omniscience.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** POSTs metric batches to the collector's ingest endpoint with the agent bearer token. */
public final class CollectorClient {

    public enum Result { ACCEPTED, RETRY, REJECTED }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentConfig config;
    private final URI ingestUri;

    public CollectorClient(AgentConfig config) {
        this.config = config;
        this.ingestUri = URI.create(config.collectorUrl() + "/api/v1/ingest");
    }

    /** Processes ride along with the batch: they are a snapshot, so only the newest matters. */
    public Result send(List<MetricPoint> batch, List<ProcessInfo> processes) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "agentId", config.agentId(),
                    "host", config.host(),
                    "metrics", batch,
                    "processes", processes == null ? List.of() : processes));
            HttpRequest request = HttpRequest.newBuilder(ingestUri)
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.ACCEPTED;
            }
            if (status == 401 || status == 403) {
                System.err.println("[agent] collector rejected token (" + status + ") — check OMNI_AGENT_TOKEN; retrying");
                return Result.RETRY;
            }
            if (status == 429 || status >= 500) {
                return Result.RETRY; // load-shed or server trouble: back off, keep buffering
            }
            System.err.println("[agent] batch rejected (" + status + "): " + response.body());
            return Result.REJECTED; // malformed batch — discarding is safer than poisoning the buffer
        } catch (Exception e) {
            return Result.RETRY; // collector unreachable
        }
    }
}
