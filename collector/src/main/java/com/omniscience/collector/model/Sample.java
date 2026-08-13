package com.omniscience.collector.model;

import java.time.Instant;
import java.util.Map;

/**
 * A logical sample as it arrives: metric name plus a tag set. org_id is stamped
 * server-side from the authenticated agent — never taken from the payload.
 * {@code host} is an ordinary tag; nothing in the model privileges it.
 */
public record Sample(String orgId, String metric, Map<String, String> tags, double value, Instant ts) {

    public String host() {
        return tags.getOrDefault("host", "");
    }
}
