package com.omniscience.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded FIFO buffer that survives collector outages. When full it drops the
 * OLDEST points first — for alerting, recent data is worth more than history
 * (SYSTEM-DESIGN.md slice 2). Single-threaded by design: the agent is one loop.
 */
public final class MetricBuffer {

    private final ArrayDeque<MetricPoint> deque = new ArrayDeque<>();
    private final int capacity;
    private long totalDropped;

    public MetricBuffer(int capacity) {
        this.capacity = capacity;
    }

    /** Adds points, evicting oldest on overflow. Returns how many were dropped. */
    public int addAll(List<MetricPoint> points) {
        int dropped = 0;
        for (MetricPoint p : points) {
            if (deque.size() >= capacity) {
                deque.pollFirst();
                dropped++;
            }
            deque.addLast(p);
        }
        totalDropped += dropped;
        return dropped;
    }

    /** Oldest {@code max} points without removing them (removal happens only after a 2xx). */
    public List<MetricPoint> snapshot(int max) {
        List<MetricPoint> out = new ArrayList<>(Math.min(max, deque.size()));
        int i = 0;
        for (MetricPoint p : deque) {
            if (i++ >= max) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    /** Removes the first {@code n} points after a confirmed send. */
    public void removeFirst(int n) {
        for (int i = 0; i < n && !deque.isEmpty(); i++) {
            deque.pollFirst();
        }
    }

    public int size() {
        return deque.size();
    }

    public boolean isEmpty() {
        return deque.isEmpty();
    }

    public long totalDropped() {
        return totalDropped;
    }
}
