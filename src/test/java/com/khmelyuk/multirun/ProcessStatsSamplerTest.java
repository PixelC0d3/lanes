package com.khmelyuk.multirun;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the process monitor feature: parsing of the ps output, aggregation of a process tree
 * and the docker-stats-like formatting shown in the Multiple Run Monitor tool window.
 */
public class ProcessStatsSamplerTest {

    // --- parsePsOutput --------------------------------------------------------------------

    @Test
    public void parsesPidRssAndCpuColumns() {
        final Map<Long, ProcessStatsSampler.Stats> stats = ProcessStatsSampler.parsePsOutput(
                Arrays.asList("  1234 151200  1.5", "5678 30720 0.0"));

        assertEquals(2, stats.size());
        assertEquals(151200, stats.get(1234L).rssKb);
        assertEquals(1.5, stats.get(1234L).cpuPercent, 0.0001);
        assertEquals(30720, stats.get(5678L).rssKb);
    }

    @Test
    public void toleratesCommaDecimalSeparatorInCpuColumn() {
        // ps honors the locale on some systems (pt-BR/German print "1,5")
        final Map<Long, ProcessStatsSampler.Stats> stats =
                ProcessStatsSampler.parsePsOutput(Arrays.asList("1234 1024 1,5"));

        assertEquals(1.5, stats.get(1234L).cpuPercent, 0.0001);
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
                Arrays.asList("100 1000 1.0", "101 2000 2.0", "999 50000 9.0"));
        final Set<Long> tree = new LinkedHashSet<>(Arrays.asList(100L, 101L));

        final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(byPid, tree);

        assertEquals("the npm wrapper and its node child must be summed", 3000, stats.rssKb);
        assertEquals(3.0, stats.cpuPercent, 0.0001);
    }

    @Test
    public void aggregateReturnsNullWhenNoPidWasSampled() {
        final Map<Long, ProcessStatsSampler.Stats> byPid =
                ProcessStatsSampler.parsePsOutput(Arrays.asList("999 1 0.0"));

        assertNull("a dead process tree must show as n/a, not as 0",
                   ProcessStatsSampler.aggregate(byPid, new LinkedHashSet<>(Arrays.asList(100L))));
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
