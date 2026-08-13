package com.omniscience.collector.bridge;

/**
 * The downstream incident system. Extracted from ImsClient so the flap-simulation
 * test can substitute a counting fake and assert that oscillating metrics produce
 * exactly one ticket — the property the whole episode design exists to guarantee.
 */
public interface IncidentClient {

    boolean enabled();

    CreatedIncident createIncident(String title, String description, String priority) throws Exception;

    void addComment(long incidentId, String body) throws Exception;

    void resolve(long incidentId, String notes) throws Exception;

    record CreatedIncident(long id, String number) {
    }
}
