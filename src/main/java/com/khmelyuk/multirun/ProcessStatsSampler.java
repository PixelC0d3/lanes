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
 * <p>Sampling shells out to {@code ps} (Linux/macOS). On platforms without {@code ps} the sampler
 * returns no data and the monitor shows "n/a".</p>
 */
public final class ProcessStatsSampler {

    private static final Logger LOG = Logger.getInstance(ProcessStatsSampler.class);
    private static final long PS_TIMEOUT_SECONDS = 5;

    /** Aggregated usage of one process tree. */
    public static final class Stats {
        public final long rssKb;
        public final double cpuPercent;

        public Stats(long rssKb, double cpuPercent) {
            this.rssKb = rssKb;
            this.cpuPercent = cpuPercent;
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
        final List<String> lines = new ArrayList<>();
        try {
            final Process ps = new ProcessBuilder("ps", "-o", "pid=,rss=,%cpu=", "-p", pidList)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (!ps.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
            }
        } catch (IOException e) {
            LOG.debug("Multiple Run monitor: ps is not available", e);
            return java.util.Collections.emptyMap();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Collections.emptyMap();
        }
        return parsePsOutput(lines);
    }

    /**
     * Parses {@code ps -o pid=,rss=,%cpu=} output lines ("{@code 1234 151200 1.5}").
     * Malformed lines are skipped; the CPU column tolerates a comma decimal separator
     * (ps honors the locale on some systems).
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
                final double cpu = parts.length > 2 ? Double.parseDouble(parts[2].replace(',', '.')) : 0.0;
                stats.put(pid, new Stats(rssKb, cpu));
            } catch (NumberFormatException ignored) {
                // header junk or truncated line - skip it
            }
        }
        return stats;
    }

    /** Sums the stats of the pids belonging to one process tree. */
    public static Stats aggregate(Map<Long, Stats> byPid, Set<Long> treePids) {
        long rssKb = 0;
        double cpu = 0;
        boolean found = false;
        for (Long pid : treePids) {
            final Stats stats = byPid.get(pid);
            if (stats != null) {
                rssKb += stats.rssKb;
                cpu += stats.cpuPercent;
                found = true;
            }
        }
        return found ? new Stats(rssKb, cpu) : null;
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
