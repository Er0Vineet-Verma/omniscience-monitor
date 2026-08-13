package com.omniscience.collector.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniscience.collector.config.MonitorProps;

/**
 * HTTP client for the Omniscience Incident API. Authenticates as the monitor
 * service account, caches the JWT, re-logins once on 401 (IMS TTL 24h).
 */
@Component
public class ImsClient implements IncidentClient {

    private static final Logger log = LoggerFactory.getLogger(ImsClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MonitorProps.Ims ims;
    private volatile String token;

    public ImsClient(MonitorProps props) {
        this.ims = props.ims();
    }

    @Override
    public boolean enabled() {
        return ims.enabled();
    }

    @Override
    public CreatedIncident createIncident(String title, String description, String priority) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "title", title,
                "description", description,
                "priority", priority));
        JsonNode node = sendAuthed("POST", "/api/incidents", body);
        return new CreatedIncident(node.path("id").asLong(), node.path("incidentNumber").asText(null));
    }

    @Override
    public void addComment(long incidentId, String commentBody) throws Exception {
        String body = mapper.writeValueAsString(Map.of("body", commentBody));
        sendAuthed("POST", "/api/incidents/" + incidentId + "/comments", body);
    }

    @Override
    public void resolve(long incidentId, String notes) throws Exception {
        String body = mapper.writeValueAsString(Map.of("status", "RESOLVED", "notes", notes));
        sendAuthed("PATCH", "/api/incidents/" + incidentId + "/status", body);
    }

    private JsonNode sendAuthed(String method, String path, String body) throws Exception {
        if (token == null) {
            login();
        }
        HttpResponse<String> response = send(method, path, body, token);
        if (response.statusCode() == 401) {
            login();
            response = send(method, path, body, token);
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("IMS " + method + " " + path + " -> "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body() == null || response.body().isBlank()
                ? mapper.nullNode()
                : mapper.readTree(response.body());
    }

    private synchronized void login() throws Exception {
        String body = mapper.writeValueAsString(Map.of("email", ims.email(), "password", ims.password()));
        HttpResponse<String> response = send("POST", "/api/auth/login", body, null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("IMS login failed (" + response.statusCode() + ")");
        }
        token = mapper.readTree(response.body()).path("token").asText();
        log.info("authenticated against IMS as {}", ims.email());
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ims.baseUrl() + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
