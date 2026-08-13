package com.omniscience.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Reads real host metrics via OSHI. Rates (CPU, network) are computed between
 * snapshots, so the first cycle measures the first real interval rather than
 * reporting a meaningless absolute counter.
 */
public final class MetricCollector {

    private static final int TOP_PROCESSES = 10;

    private final SystemInfo systemInfo = new SystemInfo();
    private final CentralProcessor cpu;
    private final OperatingSystem os;
    private long[] prevTicks;
    private long prevRecv = -1;
    private long prevSent = -1;
    private long prevNetAt;
    private final File diskRoot = new File(".").getAbsoluteFile();

    public MetricCollector() {
        this.cpu = systemInfo.getHardware().getProcessor();
        this.os = systemInfo.getOperatingSystem();
        this.prevTicks = cpu.getSystemCpuLoadTicks();
        this.prevNetAt = System.currentTimeMillis();
    }

    public HostReading collect() {
        long ts = System.currentTimeMillis();
        List<MetricPoint> points = new ArrayList<>();

        double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(prevTicks);
        prevTicks = cpu.getSystemCpuLoadTicks();
        points.add(new MetricPoint("system.cpu.load", Math.min(Math.max(cpuLoad, 0), 1.0), ts));

        GlobalMemory memory = systemInfo.getHardware().getMemory();
        points.add(new MetricPoint("system.memory.used.percent",
                100.0 * (1.0 - (double) memory.getAvailable() / memory.getTotal()), ts));
        points.add(new MetricPoint("system.memory.used.bytes",
                memory.getTotal() - memory.getAvailable(), ts));

        if (diskRoot.getTotalSpace() > 0) {
            points.add(new MetricPoint("system.disk.used.percent",
                    100.0 * (1.0 - (double) diskRoot.getFreeSpace() / diskRoot.getTotalSpace()), ts));
        }

        addNetwork(points, ts);

        points.add(new MetricPoint("system.process.count", os.getProcessCount(), ts));
        points.add(new MetricPoint("system.thread.count", os.getThreadCount(), ts));
        points.add(new MetricPoint("agent.up", 1.0, ts));

        return new HostReading(points, topProcesses());
    }

    /**
     * Network is reported as bytes/sec across all interfaces combined. Per-interface
     * tags would be bounded and legitimate, but aggregate is what Host Detail shows.
     */
    private void addNetwork(List<MetricPoint> points, long ts) {
        long recv = 0;
        long sent = 0;
        for (NetworkIF nif : systemInfo.getHardware().getNetworkIFs()) {
            nif.updateAttributes();
            recv += nif.getBytesRecv();
            sent += nif.getBytesSent();
        }
        long elapsedMs = ts - prevNetAt;
        if (prevRecv >= 0 && elapsedMs > 0) {
            double seconds = elapsedMs / 1000.0;
            points.add(new MetricPoint("system.network.in.bytes_per_sec",
                    Math.max(0, (recv - prevRecv) / seconds), ts));
            points.add(new MetricPoint("system.network.out.bytes_per_sec",
                    Math.max(0, (sent - prevSent) / seconds), ts));
        }
        prevRecv = recv;
        prevSent = sent;
        prevNetAt = ts;
    }

    private List<ProcessInfo> topProcesses() {
        List<OSProcess> procs = os.getProcesses(
                null, Comparator.comparingDouble(OSProcess::getProcessCpuLoadCumulative).reversed(), TOP_PROCESSES);
        List<ProcessInfo> out = new ArrayList<>(procs.size());
        for (OSProcess p : procs) {
            out.add(new ProcessInfo(
                    p.getProcessID(),
                    p.getName(),
                    p.getUser() == null ? "" : p.getUser(),
                    Math.round(p.getProcessCpuLoadCumulative() * 1000.0) / 10.0,
                    p.getResidentSetSize() / (1024 * 1024)));
        }
        return out;
    }
}
