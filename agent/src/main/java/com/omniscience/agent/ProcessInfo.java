package com.omniscience.agent;

/**
 * A row for the Host Detail "Top Processes" table. Sent as a snapshot rather than
 * as metrics: process names and PIDs are unbounded, so one series per process is
 * precisely the cardinality explosion the collector guards against.
 */
public record ProcessInfo(int pid, String command, String user, double cpuPercent, long rssMb) {
}
