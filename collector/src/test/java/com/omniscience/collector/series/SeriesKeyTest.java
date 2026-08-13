package com.omniscience.collector.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Series identity must not depend on the order tags happen to arrive in. */
class SeriesKeyTest {

    @Test
    void tagOrderDoesNotChangeIdentity() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("host", "vineetpc");
        a.put("iface", "eth0");

        Map<String, String> b = new LinkedHashMap<>();
        b.put("iface", "eth0");
        b.put("host", "vineetpc");

        assertEquals(SeriesKey.canonicalTags(a), SeriesKey.canonicalTags(b));
        assertEquals("host=vineetpc,iface=eth0", SeriesKey.canonicalTags(a));
    }

    @Test
    void differentTenantsNeverShareASeries() {
        Map<String, String> tags = Map.of("host", "vineetpc");
        assertNotEquals(
                SeriesKey.of("org-a", "system.cpu.load", SeriesKey.canonicalTags(tags)),
                SeriesKey.of("org-b", "system.cpu.load", SeriesKey.canonicalTags(tags)));
    }

    @Test
    void emptyTagsAreStable() {
        assertEquals("", SeriesKey.canonicalTags(Map.of()));
        assertEquals("", SeriesKey.canonicalTags(null));
        assertEquals("org-demo|up|", SeriesKey.of("org-demo", "up", ""));
    }
}
