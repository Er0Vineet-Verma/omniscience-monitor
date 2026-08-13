package com.omniscience.collector.series;

import java.util.Map;
import java.util.TreeMap;

/**
 * Canonicalizes a tag set so the same logical series always produces the same key
 * regardless of tag ordering. Sorted {@code k=v} pairs joined by commas; the full
 * key is {@code org|metric|tags}.
 */
public final class SeriesKey {

    private SeriesKey() {
    }

    public static String canonicalTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(tags).entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static String of(String orgId, String metric, String canonicalTags) {
        return orgId + "|" + metric + "|" + canonicalTags;
    }
}
