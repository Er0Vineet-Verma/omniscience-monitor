package com.omniscience.collector.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class PercentilesTest {

    /** 1..100 sorted: nearest-rank P95 is the 95th value. */
    @Test
    void nearestRankOverKnownDistribution() {
        List<Double> values = IntStream.rangeClosed(1, 100).mapToObj(i -> (double) i).toList();
        assertEquals(95.0, Percentiles.nearestRank(values, 0.95).orElseThrow());
        assertEquals(99.0, Percentiles.nearestRank(values, 0.99).orElseThrow());
        assertEquals(50.0, Percentiles.nearestRank(values, 0.50).orElseThrow());
    }

    @Test
    void clampsAtBothEnds() {
        List<Double> values = List.of(1.0, 2.0, 3.0);
        assertEquals(1.0, Percentiles.nearestRank(values, 0.0).orElseThrow());
        assertEquals(3.0, Percentiles.nearestRank(values, 1.0).orElseThrow());
        assertEquals(3.0, Percentiles.nearestRank(values, 1.5).orElseThrow());
    }

    @Test
    void emptyInputHasNoPercentile() {
        assertTrue(Percentiles.nearestRank(List.of(), 0.95).isEmpty());
        assertTrue(Percentiles.nearestRank(null, 0.95).isEmpty());
    }

    @Test
    void singleValueIsEveryPercentile() {
        assertEquals(42.0, Percentiles.nearestRank(List.of(42.0), 0.95).orElseThrow());
    }
}
