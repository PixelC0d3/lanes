package com.khmelyuk.multirun;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Samples memory (RSS) and CPU usage of the processes started by Multiple Run, the same numbers
 * `docker stats` shows for containers. A process is measured together with all its descendants
 * (an npm script spawns the actual node process, a shell script spawns the real server, ...),
 * so the reported usage is the whole process tree.
 *
 * <p>CPU is sampled as cumulative CPU time; the monitor computes the instantaneous percentage
 * from the delta between two consecutive samples, exactly like docker stats does.</p>
 *
 * <p>Sampling shells out to {@code ps}/{@code lsof} (Linux/macOS). On platforms without them
 * the sampler returns no data and the monitor shows "n/a".</p>
 */
public final class ProcessStatsSampler {

    private static final Logger LOG = Logger.getInstance(ProcessStatsSampler.class);
    private static final long PS_TIMEOUT_SECONDS = 5;

    /** Usage of one process at one sampling moment. */
    public static final class Stats {
        public final long rssKb;
        /** Cumulative CPU time since the process started, in seconds. */
        public final double cpuTimeSeconds;

        public Stats(long rssKb, double cpuTimeSeconds) {
            this.rssKb = rssKb;
            this.cpuTimeSeconds = cpuTimeSeconds;
        }
    }

    private ProcessStatsSampler() {
    }

    /** The pid itself plus every live descendant (children, grandchildren, ...). */
    public static Set<Long> processTreePids(long rootPid) {
        final Set<Long> pids = new LinkedHashSet<>();
        if (rootPid <= 0) {
            return pids;
        }
        pids.add(rootPid);
        ProcessHandle.of(rootPid).ifPresent(
                handle -> handle.descendants().forEach(descendant -> pids.add(descendant.pid())));
        return pids;
    }

    /** One {@code ps} call for all pids; returns per-pid stats (missing pids are simply absent). */
    public static Map<Long, Stats> samplePids(Collection<Long> pids) {
        if (pids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        final String pidList = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return parsePsOutput(runCommand("ps", "-o", "pid=,rss=,time=", "-p", pidList));
    }

    /**
     * Parses {@code ps -o pid=,rss=,time=} output lines ("{@code 1234 151200 00:01:30}").
     * Malformed lines are skipped.
     */
    public static Map<Long, Stats> parsePsOutput(List<String> lines) {
        final Map<Long, Stats> stats = new LinkedHashMap<>();
        for (String line : lines) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            try {
                final long pid = Long.parseLong(parts[0]);
                final long rssKb = Long.parseLong(parts[1]);
                final double cpuTime = parts.length > 2 ? parseCpuTime(parts[2]) : 0.0;
                stats.put(pid, new Stats(rssKb, Math.max(0, cpuTime)));
            } catch (NumberFormatException ignored) {
                // header junk or truncated line - skip it
            }
        }
        return stats;
    }

    /**
     * Parses the ps TIME column into seconds. Handles the Linux form {@code [dd-]hh:mm:ss},
     * the macOS form {@code mm:ss.xx} and a comma decimal separator (locale-aware ps).
     * Returns -1 when the value is not a time.
     */
    public static double parseCpuTime(String value) {
        try {
            double days = 0;
            String rest = value;
            final int dash = value.indexOf('-');
            if (dash >= 0) {
                days = Long.parseLong(value.substring(0, dash));
                rest = value.substring(dash + 1);
            }
            double seconds = 0;
            for (String part : rest.split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part.replace(',', '.'));
            }
            return days * 86_400 + seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Sums the stats of the pids belonging to one process tree. */
    public static Stats aggregate(Map<Long, Stats> byPid, Set<Long> treePids) {
        long rssKb = 0;
        double cpuTime = 0;
        boolean found = false;
        for (Long pid : treePids) {
            final Stats stats = byPid.get(pid);
            if (stats != null) {
                rssKb += stats.rssKb;
                cpuTime += stats.cpuTimeSeconds;
                found = true;
            }
        }
        return found ? new Stats(rssKb, cpuTime) : null;
    }

    /**
     * CPU seconds burned by a process tree between two samples - the docker-stats way of getting
     * an instantaneous CPU percentage (delta / elapsed * 100). Only pids present in both samples
     * count (a pid missing from the previous sample has no meaningful delta).
     * Returns -1 when no pid of the tree has a previous sample yet.
     */
    public static double cpuDeltaSeconds(Map<Long, Stats> current, Map<Long, Double> previous, Set<Long> treePids) {
        double delta = 0;
        boolean matched = false;
        for (Long pid : treePids) {
            final Stats now = current.get(pid);
            final Double before = previous.get(pid);
            if (now != null && before != null) {
                delta += Math.max(0, now.cpuTimeSeconds - before);
                matched = true;
            }
        }
        return matched ? delta : -1;
    }

    /** Formats kilobytes the way docker stats does: {@code 151.2MiB}, {@code 1.50GiB}. */
    public static String formatMemory(long kb) {
        if (kb < 0) {
            return "n/a";
        }
        final double mib = kb / 1024.0;
        if (mib < 1024) {
            return String.format(Locale.US, "%.1fMiB", mib);
        }
        return String.format(Locale.US, "%.2fGiB", mib / 1024.0);
    }

    /** Formats an uptime like docker ps: {@code 42s}, {@code 5m 12s}, {@code 2h 08m}, {@code 3d 4h}. */
    public static String formatUptime(long ms) {
        if (ms < 0) {
            return "n/a";
        }
        final long seconds = ms / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        final long minutes = seconds / 60;
        if (minutes < 60) {
            return String.format(Locale.US, "%dm %02ds", minutes, seconds % 60);
        }
        final long hours = minutes / 60;
        if (hours < 24) {
            return String.format(Locale.US, "%dh %02dm", hours, minutes % 60);
        }
        return String.format(Locale.US, "%dd %dh", hours / 24, hours % 24);
    }

    /**
     * Memory percentage like docker stats: usage against the configured limit when one is set,
     * against the total host memory otherwise. Returns -1 when it cannot be computed.
     */
    public static double memoryPercent(long rssKb, Integer limitMb, long hostTotalKb) {
        final long baseKb = (limitMb != null && limitMb > 0) ? limitMb * 1024L : hostTotalKb;
        if (baseKb <= 0 || rssKb < 0) {
            return -1;
        }
        return rssKb * 100.0 / baseKb;
    }

    /**
     * TCP ports in LISTEN state per pid, like the PORTS column of `docker ps`.
     * Uses {@code lsof -nP -a -p <pids> -iTCP -sTCP:LISTEN}; empty on platforms without lsof.
     */
    public static Map<Long, Set<Integer>> sampleListeningPorts(Collection<Long> pids) {
        if (pids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        final String pidList = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return parseLsofOutput(runCommand("lsof", "-nP", "-a", "-p", pidList, "-iTCP", "-sTCP:LISTEN"));
    }

    /** Pids listening on the given TCP port ({@code lsof -t}); used by "Kill Process on Port". */
    public static List<Long> pidsListeningOnPort(int port) {
        return parseTersePids(runCommand("lsof", "-t", "-iTCP:" + port, "-sTCP:LISTEN"));
    }

    /**
     * Parses regular {@code lsof -iTCP -sTCP:LISTEN} lines
     * ("{@code node 41234 user 23u IPv6 ... TCP *:3015 (LISTEN)}") into pid -> listening ports.
     * The pid is the first numeric token (command names may contain spaces); the port is the
     * digits after the last ':' of the address token, so IPv4, IPv6 and wildcard forms all work.
     */
    public static Map<Long, Set<Integer>> parseLsofOutput(List<String> lines) {
        final Map<Long, Set<Integer>> ports = new LinkedHashMap<>();
        for (String line : lines) {
            final String[] parts = line.trim().split("\\s+");
            Long pid = null;
            Integer port = null;
            for (String part : parts) {
                if (pid == null) {
                    try {
                        pid = Long.parseLong(part);
                        continue;
                    } catch (NumberFormatException ignored) {
                        // still inside the command name
                    }
                }
                final int colon = part.lastIndexOf(':');
                if (colon >= 0 && colon < part.length() - 1) {
                    final String candidate = part.substring(colon + 1);
                    if (!candidate.isEmpty() && candidate.chars().allMatch(Character::isDigit)) {
                        port = Integer.parseInt(candidate);
                    }
                }
            }
            if (pid != null && port != null) {
                ports.computeIfAbsent(pid, p -> new java.util.TreeSet<>()).add(port);
            }
        }
        return ports;
    }

    /** Parses {@code lsof -t} output: one pid per line, anything else is skipped. */
    public static List<Long> parseTersePids(List<String> lines) {
        final List<Long> pids = new ArrayList<>();
        for (String line : lines) {
            try {
                pids.add(Long.parseLong(line.trim()));
            } catch (NumberFormatException ignored) {
                // blank or noise line
            }
        }
        return pids;
    }

    private static List<String> runCommand(String... command) {
        final List<String> lines = new ArrayList<>();
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            LOG.debug("Multiple Run monitor: command not available: " + command[0], e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lines;
    }

    /** Total physical memory of the machine in KB, or -1 when unknown. */
    public static long hostTotalMemoryKb() {
        try {
            final com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize() / 1024;
        } catch (Throwable t) {
            return -1;
        }
    }
}
