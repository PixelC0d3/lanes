package io.github.pixelcodes.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHistoryTest {

    /** Builds a series of [count] samples, each [stepMs] apart, rss = base + i*deltaKb. */
    private fun series(base: Long, deltaKb: Long, count: Int, stepMs: Long): List<MemoryHistory.Sample> {
        val list = ArrayList<MemoryHistory.Sample>()
        for (i in 0 until count) {
            list.add(MemoryHistory.Sample(i * stepMs, base + i * deltaKb, 0.0))
        }
        return list
    }

    @Test
    fun analyzeFewerThanThreeSamplesIsInsufficient() {
        assertEquals(MemoryHistory.Trend.INSUFFICIENT_DATA,
                     MemoryHistory.analyze(emptyList()).trend)
        assertEquals(MemoryHistory.Trend.INSUFFICIENT_DATA,
                     MemoryHistory.analyze(series(100_000, 0, 2, 1000)).trend)
    }

    @Test
    fun analyzeFlatSeriesIsStable() {
        val a = MemoryHistory.analyze(series(200_000, 0, 10, 1000))
        assertEquals(MemoryHistory.Trend.STABLE, a.trend)
        assertEquals(0L, a.netChangeKb)
        assertEquals(0.0, a.slopeKbPerMin, 0.001)
    }

    @Test
    fun analyzeSteadyGrowthReadsAsPossibleLeak() {
        // 20 samples, +20 MiB each 2s over a start of 100 MiB -> clearly growing
        val a = MemoryHistory.analyze(series(100 * 1024L, 20 * 1024L, 20, 2000))
        assertEquals(MemoryHistory.Trend.GROWING, a.trend)
        assertTrue(a.netChangeKb > 0)
        assertTrue(a.slopeKbPerMin > 0)
        assertEquals(100 * 1024L, a.firstRssKb)
        assertEquals(a.maxRssKb, a.lastRssKb) // monotonic growth: last is the peak
    }

    @Test
    fun analyzeSteadyDeclineIsShrinking() {
        val a = MemoryHistory.analyze(series(500 * 1024L, -20 * 1024L, 20, 2000))
        assertEquals(MemoryHistory.Trend.SHRINKING, a.trend)
        assertTrue(a.netChangeKb < 0)
        assertTrue(a.slopeKbPerMin < 0)
    }

    @Test
    fun analyzeTinyWiggleStaysStable() {
        // grows only ~1 MiB total on a 300 MiB base -> under the 10%/5MiB threshold
        val a = MemoryHistory.analyze(series(300 * 1024L, 64, 16, 1000))
        assertEquals(MemoryHistory.Trend.STABLE, a.trend)
    }

    @Test
    fun steadyLinearGrowthIsHighConfidenceLeak() {
        val a = MemoryHistory.analyze(series(100 * 1024L, 20 * 1024L, 20, 2000))
        assertTrue("perfectly linear -> R2 near 1", a.rSquared > 0.99)
        assertEquals("monotonic growth -> memory never freed", 1.0, a.monotonicFraction, 0.0001)
        assertTrue(a.isSteadyLeak())
        assertTrue(a.projectedPerHourKb > 0)
        assertEquals("Growing - likely memory leak (steady)", MemoryHistory.verdictText(a))
    }

    @Test
    fun summaryTextCarriesAppNameAndVerdict() {
        val a = MemoryHistory.analyze(series(100 * 1024L, 20 * 1024L, 20, 2000))
        val text = MemoryHistory.summaryText("eparts-api", a)
        assertTrue(text.startsWith("Memory analysis - eparts-api"))
        assertTrue(text.contains("Verdict: Growing"))
        assertTrue(text.contains("R2="))
    }

    @Test
    fun summaryTextForInsufficientDataStaysShort() {
        val text = MemoryHistory.summaryText("app", MemoryHistory.analyze(emptyList()))
        assertTrue(text.contains("Insufficient data"))
        assertTrue(text.contains("Not enough samples"))
    }

    @Test
    fun memAndDurationFormattersAreLocaleStable() {
        assertEquals("512.0MiB", MemoryHistory.mem(512 * 1024L))
        assertEquals("1.50GiB", MemoryHistory.mem((1.5 * 1024 * 1024).toLong()))
        assertEquals("42s", MemoryHistory.durationText(42_000L))
        assertEquals("5m 12s", MemoryHistory.durationText((5 * 60 + 12) * 1000L))
    }

    @Test
    fun csvHasHeaderAndOneRowPerSample() {
        val csv = MemoryHistory.toCsv(listOf(
            MemoryHistory.Sample(1000L, 2048L, 12.5),
            MemoryHistory.Sample(3000L, 4096L, 25.0)))
        // unlike Java's String.split(regex), Kotlin's split keeps a trailing empty element
        // when the string ends with the delimiter (the CSV ends with "\n") - drop it to match
        val lines = csv.split("\n").dropLastWhile { it.isEmpty() }
        assertEquals(3, lines.size) // header + 2 rows
        assertEquals("timestamp_ms,iso_time,rss_kb,percent", lines[0])
        assertTrue(lines[1].startsWith("1000,"))
        assertTrue(lines[1].contains(",2048,"))
        // US decimal separator regardless of the default locale
        assertTrue(lines[1].endsWith(",12.50"))
        assertTrue(lines[2].endsWith(",25.00"))
    }

    @Test
    fun csvOfEmptyHistoryIsJustTheHeader() {
        assertEquals("timestamp_ms,iso_time,rss_kb,percent\n", MemoryHistory.toCsv(emptyList()))
    }
}
