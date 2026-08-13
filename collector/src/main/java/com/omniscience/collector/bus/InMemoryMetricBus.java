package com.omniscience.collector.bus;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.omniscience.collector.config.MonitorProps;
import com.omniscience.collector.model.Sample;
import com.omniscience.collector.model.StoredSample;
import com.omniscience.collector.series.SeriesRegistry;
import com.omniscience.collector.store.MetricStore;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Local-profile bus: bounded queue + one drain thread. Series resolution happens
 * here on the consumer side, not at ingest — the edge stays stateless and free of
 * database work, which is what lets collectors scale horizontally.
 *
 * The Kafka implementation replaces this class; producers keep calling publish()
 * and the drain logic becomes a Kafka listener.
 */
@Component
public class InMemoryMetricBus implements MetricBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricBus.class);

    private final BlockingQueue<List<Sample>> queue;
    private final MetricStore store;
    private final SeriesRegistry registry;
    private final AtomicLong shed = new AtomicLong();
    private volatile boolean running = true;
    private Thread worker;

    public InMemoryMetricBus(MonitorProps props, MetricStore store, SeriesRegistry registry) {
        this.queue = new LinkedBlockingQueue<>(props.busCapacity());
        this.store = store;
        this.registry = registry;
    }

    @Override
    public boolean publish(List<Sample> batch) {
        boolean accepted = queue.offer(batch);
        if (!accepted) {
            shed.incrementAndGet();
        }
        return accepted;
    }

    @Override
    public long shedCount() {
        return shed.get();
    }

    @Override
    public int depth() {
        return queue.size();
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::drainLoop, "metric-bus-drain");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
    }

    private void drainLoop() {
        while (running) {
            try {
                List<Sample> batch = queue.poll(1, TimeUnit.SECONDS);
                if (batch != null) {
                    store.write(batch.stream()
                            .map(s -> new StoredSample(
                                    registry.resolve(s.orgId(), s.metric(), s.tags(), s.ts()),
                                    s.ts(), s.value()))
                            .toList());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("failed to persist batch: {}", e.getMessage());
            }
        }
    }
}
