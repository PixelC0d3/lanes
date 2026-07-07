package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class MemoryHistoryTest {

    /** Builds a series of {@code count} samples, each {@code stepMs} apart, rss = base + i*deltaKb. */
    private static List<MemoryHistory.Sample> series(long base, long deltaKb, int count, long stepMs) {
        final List<MemoryHistory.Sample> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MemoryHistory.Sample(i * stepMs, base + (long) i * deltaKb, 0));
        }
        return list;
    }

    @Test
    public void analyzeFewerThanThreeSamplesIsInsufficient() {
        assertEquals(MemoryHistory.Trend.INSUFFICIENT_DATA,
                     MemoryHistory.analyze(Collections.emptyList()).trend);
        assertEquals(MemoryHistory.Trend.INSUFFICIENT_DATA,
                     MemoryHistory.analyze(series(100_000, 0, 2, 1000)).trend);
    }

    @Test
    public void analyzeFlatSeriesIsStable() {
        final MemoryHistory.Analysis a = MemoryHistory.analyze(series(200_000, 0, 10, 1000));
        assertEquals(MemoryHistory.Trend.STABLE, a.trend);
        assertEquals(0, a.netChangeKb);
        assertEquals(0.0, a.slopeKbPerMin, 0.001);
    }

    @Test
    public void analyzeSteadyGrowthReadsAsPossibleLeak() {
        // 20 samples, +20 MiB each 2s over a start of 100 MiB -> clearly growing
        final MemoryHistory.Analysis a =
                MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000));
        assertEquals(MemoryHistory.Trend.GROWING, a.trend);
        assertTrue(a.netChangeKb > 0);
        assertTrue(a.slopeKbPerMin > 0);
        assertEquals(100 * 1024, a.firstRssKb);
        assertEquals(a.maxRssKb, a.lastRssKb); // monotonic growth: last is the peak
    }

    @Test
    public void analyzeSteadyDeclineIsShrinking() {
        final MemoryHistory.Analysis a =
                MemoryHistory.analyze(series(500 * 1024, -20 * 1024, 20, 2000));
        assertEquals(MemoryHistory.Trend.SHRINKING, a.trend);
        assertTrue(a.netChangeKb < 0);
        assertTrue(a.slopeKbPerMin < 0);
    }

    @Test
    public void analyzeTinyWiggleStaysStable() {
        // grows only ~1 MiB total on a 300 MiB base -> under the 10%/5MiB threshold
        final MemoryHistory.Analysis a = MemoryHistory.analyze(series(300 * 1024, 64, 16, 1000));
        assertEquals(MemoryHistory.Trend.STABLE, a.trend);
    }

    @Test
    public void steadyLinearGrowthIsHighConfidenceLeak() {
        final MemoryHistory.Analysis a =
                MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000));
        assertTrue("perfectly linear -> R2 near 1", a.rSquared > 0.99);
        assertEquals("monotonic growth -> memory never freed", 1.0, a.monotonicFraction, 0.0001);
        assertTrue(a.isSteadyLeak());
        assertTrue(a.projectedPerHourKb > 0);
        assertEquals("Growing - likely memory leak (steady)", MemoryHistory.verdictText(a));
    }

    @Test
    public void summaryTextCarriesAppNameAndVerdict() {
        final MemoryHistory.Analysis a =
                MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000));
        final String text = MemoryHistory.summaryText("eparts-api", a);
        assertTrue(text.startsWith("Memory analysis - eparts-api"));
        assertTrue(text.contains("Verdict: Growing"));
        assertTrue(text.contains("R2="));
    }

    @Test
    public void summaryTextForInsufficientDataStaysShort() {
        final String text = MemoryHistory.summaryText("app", MemoryHistory.analyze(Collections.emptyList()));
        assertTrue(text.contains("Insufficient data"));
        assertTrue(text.contains("Not enough samples"));
    }

    @Test
    public void memAndDurationFormattersAreLocaleStable() {
        assertEquals("512.0MiB", MemoryHistory.mem(512 * 1024));
        assertEquals("1.50GiB", MemoryHistory.mem((long) (1.5 * 1024 * 1024)));
        assertEquals("42s", MemoryHistory.durationText(42_000));
        assertEquals("5m 12s", MemoryHistory.durationText((5 * 60 + 12) * 1000));
    }

    @Test
    public void csvHasHeaderAndOneRowPerSample() {
        final String csv = MemoryHistory.toCsv(Arrays.asList(
                new MemoryHistory.Sample(1000L, 2048L, 12.5),
                new MemoryHistory.Sample(3000L, 4096L, 25.0)));
        final String[] lines = csv.split("\n");
        assertEquals(3, lines.length); // header + 2 rows
        assertEquals("timestamp_ms,iso_time,rss_kb,percent", lines[0]);
        assertTrue(lines[1].startsWith("1000,"));
        assertTrue(lines[1].contains(",2048,"));
        // US decimal separator regardless of the default locale
        assertTrue(lines[1].endsWith(",12.50"));
        assertTrue(lines[2].endsWith(",25.00"));
    }

    @Test
    public void csvOfEmptyHistoryIsJustTheHeader() {
        assertEquals("timestamp_ms,iso_time,rss_kb,percent\n", MemoryHistory.toCsv(Collections.emptyList()));
    }
}
