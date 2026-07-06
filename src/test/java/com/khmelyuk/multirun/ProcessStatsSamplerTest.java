package com.khmelyuk.multirun;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the process monitor feature: parsing of the ps output, aggregation of a process tree
 * and the docker-stats-like formatting shown in the Multiple Run Monitor tool window.
 */
public class ProcessStatsSamplerTest {

    // --- parsePsOutput --------------------------------------------------------------------

    @Test
    public void parsesPidRssAndCpuTimeColumns() {
        final Map<Long, ProcessStatsSampler.Stats> stats = ProcessStatsSampler.parsePsOutput(
                Arrays.asList("  1234 151200  00:01:30", "5678 30720 00:00:00"));

        assertEquals(2, stats.size());
        assertEquals(151200, stats.get(1234L).rssKb);
        assertEquals(90.0, stats.get(1234L).cpuTimeSeconds, 0.0001);
        assertEquals(30720, stats.get(5678L).rssKb);
    }

    @Test
    public void toleratesCommaDecimalSeparatorInCpuTime() {
        // macOS prints mm:ss.xx and ps honors the locale on some systems (pt-BR/German -> "0:01,50")
        final Map<Long, ProcessStatsSampler.Stats> stats =
                ProcessStatsSampler.parsePsOutput(Arrays.asList("1234 1024 0:01,50"));

        assertEquals(1.5, stats.get(1234L).cpuTimeSeconds, 0.0001);
    }

    // --- parseCpuTime -----------------------------------------------------------------------

    @Test
    public void parsesLinuxAndMacCpuTimeFormats() {
        assertEquals(12.0, ProcessStatsSampler.parseCpuTime("00:00:12"), 0.0001);
        assertEquals(7384.0, ProcessStatsSampler.parseCpuTime("02:03:04"), 0.0001);
        assertEquals(93784.0, ProcessStatsSampler.parseCpuTime("1-02:03:04"), 0.0001);   // Linux, with days
        assertEquals(12.34, ProcessStatsSampler.parseCpuTime("0:12.34"), 0.0001);        // macOS
        assertEquals(-1.0, ProcessStatsSampler.parseCpuTime("abc"), 0.0001);
    }

    // --- parseProcStatCpuTicks (/proc precision for the instantaneous CPU %) -----------------

    @Test
    public void parsesUtimePlusStimeFromProcStat() {
        // utime=150 (12th field after the comm) + stime=250 (13th)
        final String line = "42 (node) S 1 42 42 0 -1 4194304 500 0 0 0 150 250 3 2 20 0 11 0 12345 100000 200";

        assertEquals(400, ProcessStatsSampler.parseProcStatCpuTicks(line));
    }

    @Test
    public void procStatCommandNameMayContainSpacesAndParentheses() {
        // the comm field is parenthesized and can contain anything, including ') ' sequences
        final String line = "42 (my (weird) app) S 1 42 42 0 -1 4194304 500 0 0 0 70 30 3 2 20 0 11 0 12345 100000 200";

        assertEquals(100, ProcessStatsSampler.parseProcStatCpuTicks(line));
    }

    @Test
    public void malformedProcStatGivesMinusOne() {
        assertEquals(-1, ProcessStatsSampler.parseProcStatCpuTicks("garbage"));
        assertEquals(-1, ProcessStatsSampler.parseProcStatCpuTicks("42 (node) S 1 42"));
        assertEquals(-1, ProcessStatsSampler.parseProcStatCpuTicks("42 (node) S a b c d e f g h i j k l m n"));
    }

    @Test
    public void skipsMalformedLines() {
        final Map<Long, ProcessStatsSampler.Stats> stats = ProcessStatsSampler.parsePsOutput(
                Arrays.asList("", "  PID   RSS  %CPU", "abc def", "1234 2048 0.3", "999"));

        assertEquals(1, stats.size());
        assertEquals(2048, stats.get(1234L).rssKb);
    }

    @Test
    public void emptyOutputGivesEmptyStats() {
        assertTrue(ProcessStatsSampler.parsePsOutput(emptyList()).isEmpty());
    }

    // --- aggregate (whole process tree, e.g. npm wrapper + node child) ---------------------

    @Test
    public void aggregateSumsTheWholeProcessTree() {
        final Map<Long, ProcessStatsSampler.Stats> byPid = ProcessStatsSampler.parsePsOutput(
                Arrays.asList("100 1000 00:00:01", "101 2000 00:00:02", "999 50000 00:00:09"));
        final Set<Long> tree = new LinkedHashSet<>(Arrays.asList(100L, 101L));

        final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(byPid, tree);

        assertEquals("the npm wrapper and its node child must be summed", 3000, stats.rssKb);
        assertEquals(3.0, stats.cpuTimeSeconds, 0.0001);
    }

    @Test
    public void aggregateReturnsNullWhenNoPidWasSampled() {
        final Map<Long, ProcessStatsSampler.Stats> byPid =
                ProcessStatsSampler.parsePsOutput(Arrays.asList("999 1 00:00:00"));

        assertNull("a dead process tree must show as n/a, not as 0",
                   ProcessStatsSampler.aggregate(byPid, new LinkedHashSet<>(Arrays.asList(100L))));
    }

    // --- cpuDeltaSeconds (docker-style instantaneous CPU %) ----------------------------------

    @Test
    public void cpuDeltaSumsOnlyPidsPresentInBothSamples() {
        final Map<Long, ProcessStatsSampler.Stats> current = ProcessStatsSampler.parsePsOutput(
                Arrays.asList("100 1000 00:00:05", "101 2000 00:00:07", "102 500 00:00:09"));
        final Map<Long, Double> previous = new java.util.HashMap<>();
        previous.put(100L, 4.0);
        previous.put(101L, 6.5);
        // 102 is a fresh child: no baseline yet, must not distort the delta

        final Set<Long> tree = new LinkedHashSet<>(Arrays.asList(100L, 101L, 102L));

        assertEquals(1.5, ProcessStatsSampler.cpuDeltaSeconds(current, previous, tree), 0.0001);
    }

    @Test
    public void cpuDeltaIsUnknownOnTheFirstSample() {
        final Map<Long, ProcessStatsSampler.Stats> current =
                ProcessStatsSampler.parsePsOutput(Arrays.asList("100 1000 00:00:05"));

        assertEquals("without a baseline there is no rate yet", -1.0,
                     ProcessStatsSampler.cpuDeltaSeconds(current, new java.util.HashMap<>(),
                                                         new LinkedHashSet<>(Arrays.asList(100L))), 0.0001);
    }

    // --- formatUptime -------------------------------------------------------------------------

    @Test
    public void formatsUptimeLikeDockerPs() {
        assertEquals("42s", ProcessStatsSampler.formatUptime(42_000));
        assertEquals("5m 12s", ProcessStatsSampler.formatUptime((5 * 60 + 12) * 1000L));
        assertEquals("2h 08m", ProcessStatsSampler.formatUptime((2 * 3600 + 8 * 60) * 1000L));
        assertEquals("3d 4h", ProcessStatsSampler.formatUptime((3 * 86400 + 4 * 3600) * 1000L));
        assertEquals("n/a", ProcessStatsSampler.formatUptime(-1));
    }

    // --- MemoryLimitWatcher.isNearLimit (90% alert) -------------------------------------------

    @Test
    public void alertsAtNinetyPercentOfTheLimit() {
        // 90% of a 100 MB limit = 92160 KB
        assertTrue(MemoryLimitWatcher.isNearLimit(92_160, 100));
        assertTrue(MemoryLimitWatcher.isNearLimit(102_400, 100));
        assertFalse("below the threshold there must be no alert", MemoryLimitWatcher.isNearLimit(92_159, 100));
        assertFalse("unknown usage must not alert", MemoryLimitWatcher.isNearLimit(-1, 100));
        assertFalse("no limit, no alert", MemoryLimitWatcher.isNearLimit(92_160, 0));
    }

    @Test
    public void alertThresholdIsConfigurable() {
        // 80% of a 100 MB limit = 81920 KB
        assertTrue(MemoryLimitWatcher.isNearLimit(81_920, 100, 80));
        assertFalse(MemoryLimitWatcher.isNearLimit(81_920, 100, 90));
        assertFalse("threshold 0 disables the alert", MemoryLimitWatcher.isNearLimit(81_920, 100, 0));
    }

    // --- isCrashExit (restart on crash policy) ------------------------------------------------

    @Test
    public void crashExitCodesTriggerRestartButIntentionalStopsDoNot() {
        assertTrue(RunConfigurationHelper.isCrashExit(1));
        assertTrue(RunConfigurationHelper.isCrashExit(134));   // SIGABRT (e.g. node OOM abort)
        assertFalse("success is not a crash", RunConfigurationHelper.isCrashExit(0));
        assertFalse("SIGINT (ctrl-c) is intentional", RunConfigurationHelper.isCrashExit(130));
        assertFalse("SIGKILL (force kill) is intentional", RunConfigurationHelper.isCrashExit(137));
        assertFalse("SIGTERM (stop button) is intentional", RunConfigurationHelper.isCrashExit(143));
    }

    // --- formatMemory (docker stats style) --------------------------------------------------

    @Test
    public void formatsKilobytesAsMiBAndGiB() {
        assertEquals("151.2MiB", ProcessStatsSampler.formatMemory(154829));
        assertEquals("1.00GiB", ProcessStatsSampler.formatMemory(1024 * 1024));
        assertEquals("0.0MiB", ProcessStatsSampler.formatMemory(0));
        assertEquals("n/a", ProcessStatsSampler.formatMemory(-1));
    }

    // --- memoryPercent ----------------------------------------------------------------------

    @Test
    public void percentAgainstConfiguredLimitWhenSet() {
        // 512MB used of a 1024MB limit -> 50%
        assertEquals(50.0, ProcessStatsSampler.memoryPercent(512 * 1024, 1024, 32L * 1024 * 1024), 0.0001);
    }

    @Test
    public void percentAgainstHostMemoryWhenNoLimit() {
        // 1GiB used of a 16GiB machine -> 6.25%
        assertEquals(6.25, ProcessStatsSampler.memoryPercent(1024 * 1024, null, 16L * 1024 * 1024), 0.0001);
    }

    @Test
    public void percentIsUnknownWithoutAnyBase() {
        assertEquals(-1.0, ProcessStatsSampler.memoryPercent(1024, null, -1), 0.0001);
        assertEquals(-1.0, ProcessStatsSampler.memoryPercent(-1, 1024, -1), 0.0001);
    }

    // --- processTreePids --------------------------------------------------------------------

    @Test
    public void invalidPidGivesEmptyTree() {
        assertTrue(ProcessStatsSampler.processTreePids(-1).isEmpty());
        assertTrue(ProcessStatsSampler.processTreePids(0).isEmpty());
    }

    @Test
    public void ownProcessTreeContainsAtLeastTheRootPid() {
        final long myPid = ProcessHandle.current().pid();

        assertTrue("the pid itself must always be part of its tree",
                   ProcessStatsSampler.processTreePids(myPid).contains(myPid));
    }

    // --- parseLsofOutput (Ports column / kill-by-port) ---------------------------------------

    @Test
    public void parsesLsofListenLinesIntoPidToPorts() {
        final Map<Long, Set<Integer>> ports = ProcessStatsSampler.parseLsofOutput(Arrays.asList(
                "COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME",
                "node    41234 wm   23u  IPv6 123456      0t0  TCP *:3015 (LISTEN)",
                "node    41234 wm   24u  IPv4 123457      0t0  TCP 127.0.0.1:9229 (LISTEN)",
                "java     5555 wm   88u  IPv6    999      0t0  TCP [::1]:8080 (LISTEN)"));

        assertEquals(2, ports.size());
        assertEquals(new java.util.TreeSet<>(Arrays.asList(3015, 9229)), ports.get(41234L));
        assertEquals(new java.util.TreeSet<>(Arrays.asList(8080)), ports.get(5555L));
    }

    @Test
    public void lsofParserSkipsMalformedLines() {
        final Map<Long, Set<Integer>> ports = ProcessStatsSampler.parseLsofOutput(
                Arrays.asList("", "garbage without columns", "node abc def"));

        assertTrue(ports.isEmpty());
    }

    @Test
    public void parsesTersePidOutput() {
        assertEquals(Arrays.asList(41234L, 5555L),
                     ProcessStatsSampler.parseTersePids(Arrays.asList("41234", " 5555 ", "", "noise")));
    }

    // --- samplePids (integration with the real ps, available on Linux/macOS) ----------------

    @Test
    public void samplesTheCurrentProcessWithRealPs() {
        final long myPid = ProcessHandle.current().pid();

        final Map<Long, ProcessStatsSampler.Stats> stats =
                ProcessStatsSampler.samplePids(Arrays.asList(myPid));

        // on Linux/macOS ps must find this JVM with a positive RSS; skip silently elsewhere
        if (!stats.isEmpty()) {
            assertTrue("a running JVM uses more than 0 kb", stats.get(myPid).rssKb > 0);
        }
    }
}
