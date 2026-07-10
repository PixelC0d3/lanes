package io.github.welingtonmonteiro.multiplerun

import java.time.Instant
import java.util.Locale

/**
 * The recorded memory history of one application over a session, plus its CSV serialization. Kept
 * free of IDE dependencies so the formatting is unit-testable in isolation.
 *
 * Java callers keep the exact same surface (`MemoryHistory.Sample`, `MemoryHistory.analyze(...)`,
 * field access like `sample.rssKb` / `analysis.slopeKbPerMin`): the nested types stay nested, the
 * fields are exposed with `@JvmField` and the functions with `@JvmStatic`.
 */
class MemoryHistory private constructor() {

    /** One memory measurement of an application at a point in time. */
    class Sample(
        @JvmField val timeMs: Long,
        @JvmField val rssKb: Long,
        @JvmField val percent: Double,
    )

    /** How the memory of an application is trending over the recorded session. */
    enum class Trend { INSUFFICIENT_DATA, STABLE, GROWING, SHRINKING }

    /**
     * A memory-trend summary of a session, used by the monitor's leak analysis. All figures come
     * straight from the recorded samples (no IDE dependency), so this is unit-tested in isolation.
     */
    class Analysis internal constructor(
        @JvmField val samples: Int,
        @JvmField val durationMs: Long,
        @JvmField val firstRssKb: Long,
        @JvmField val lastRssKb: Long,
        @JvmField val minRssKb: Long,
        @JvmField val maxRssKb: Long,
        /** last - first: net memory change over the session (can be negative). */
        @JvmField val netChangeKb: Long,
        /** Slope of a least-squares fit of RSS over time, in KB per minute. */
        @JvmField val slopeKbPerMin: Double,
        /** Slope projected to one hour, in KB (slope * 60): how fast it grows/shrinks per hour. */
        @JvmField val projectedPerHourKb: Long,
        /** R² of the linear fit in [0,1]: how steadily (linearly) memory moves - high = steady leak. */
        @JvmField val rSquared: Double,
        /** Fraction of consecutive samples that did not decrease, in [0,1]: 1 = never freed memory. */
        @JvmField val monotonicFraction: Double,
        @JvmField val trend: Trend,
    ) {
        /** True when growth is both sustained (positive slope) and steady/linear (high R²). */
        fun isSteadyLeak(): Boolean = trend == Trend.GROWING && rSquared >= 0.75
    }

    companion object {
        /**
         * Summarizes a memory history into a trend/leak verdict. Fewer than three samples yields
         * [Trend.INSUFFICIENT_DATA]. Otherwise the net change is compared against a threshold of
         * 10% of the starting RSS (at least 5 MiB): a rise beyond it with a positive least-squares
         * slope reads as [Trend.GROWING] (a possible leak), a matching fall as [Trend.SHRINKING],
         * everything else as [Trend.STABLE].
         */
        @JvmStatic
        fun analyze(samples: List<Sample>): Analysis {
            val n = samples.size
            if (n < 3) {
                val only = if (n == 0) 0L else samples[0].rssKb
                return Analysis(n, 0, only, only, only, only, 0, 0.0, 0, 0.0, 0.0, Trend.INSUFFICIENT_DATA)
            }
            val firstTime = samples[0].timeMs
            val firstRss = samples[0].rssKb
            val lastRss = samples[n - 1].rssKb
            val durationMs = samples[n - 1].timeMs - firstTime
            var minRss = Long.MAX_VALUE
            var maxRss = Long.MIN_VALUE
            var prevRss = firstRss
            var nonDecreasing = 0
            // least-squares slope of rss (KB) over time (minutes), plus the terms for R²
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var sumXY = 0.0
            for (i in 0 until n) {
                val sample = samples[i]
                val x = (sample.timeMs - firstTime) / 60_000.0
                val y = sample.rssKb.toDouble()
                sumX += x
                sumY += y
                sumXX += x * x
                sumYY += y * y
                sumXY += x * y
                minRss = minOf(minRss, sample.rssKb)
                maxRss = maxOf(maxRss, sample.rssKb)
                if (i > 0) {
                    if (sample.rssKb >= prevRss) {
                        nonDecreasing++
                    }
                    prevRss = sample.rssKb
                }
            }
            val denom = n * sumXX - sumX * sumX
            val slope = if (denom == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denom

            // R² of the fit: (covariance)² / (var(x)·var(y)); 0 when either variance is degenerate
            val covTerm = n * sumXY - sumX * sumY
            val varXTerm = n * sumXX - sumX * sumX
            val varYTerm = n * sumYY - sumY * sumY
            val rSquared = if (varXTerm <= 0 || varYTerm <= 0) 0.0
                else ((covTerm * covTerm) / (varXTerm * varYTerm)).coerceIn(0.0, 1.0)
            val monotonicFraction = nonDecreasing.toDouble() / (n - 1)
            val projectedPerHourKb = Math.round(slope * 60)

            val netChange = lastRss - firstRss
            val threshold = maxOf(firstRss / 10, 5 * 1024L) // 10% of start, min 5 MiB
            val trend = when {
                slope > 0 && netChange > threshold -> Trend.GROWING
                slope < 0 && -netChange > threshold -> Trend.SHRINKING
                else -> Trend.STABLE
            }
            return Analysis(n, durationMs, firstRss, lastRss, minRss, maxRss, netChange, slope,
                            projectedPerHourKb, rSquared, monotonicFraction, trend)
        }

        /** Human-readable verdict for the trend (used in the UI and the exported report). */
        @JvmStatic
        fun verdictText(a: Analysis): String = when (a.trend) {
            Trend.GROWING -> if (a.isSteadyLeak()) "Growing - likely memory leak (steady)"
                             else "Growing - possible memory leak"
            Trend.SHRINKING -> "Shrinking"
            Trend.STABLE -> "Stable"
            else -> "Insufficient data"
        }

        /** Multi-line, plain-text summary of an analysis - the header of the exported report. */
        @JvmStatic
        fun summaryText(appName: String?, a: Analysis): String {
            val sb = StringBuilder()
            sb.append("Memory analysis - ").append(appName ?: "application").append('\n')
            sb.append("Verdict: ").append(verdictText(a)).append('\n')
            if (a.trend == Trend.INSUFFICIENT_DATA) {
                sb.append("Not enough samples yet to establish a trend.\n")
                return sb.toString()
            }
            sb.append(String.format(Locale.US, "Trend: %+.1f MiB/min (~ %s%s/h), R2=%.2f, memory not freed %.0f%% of the time%n",
                                    a.slopeKbPerMin / 1024.0,
                                    if (a.projectedPerHourKb >= 0) "+" else "-", mem(Math.abs(a.projectedPerHourKb)),
                                    a.rSquared, a.monotonicFraction * 100))
            sb.append("Duration: ").append(durationText(a.durationMs)).append(" over ").append(a.samples).append(" samples\n")
            val sign = if (a.netChangeKb >= 0) "+" else "-"
            sb.append("First -> last: ").append(mem(a.firstRssKb)).append(" -> ").append(mem(a.lastRssKb))
              .append(" (").append(sign).append(mem(Math.abs(a.netChangeKb))).append(")\n")
            sb.append("Min / peak: ").append(mem(a.minRssKb)).append(" / ").append(mem(a.maxRssKb)).append('\n')
            return sb.toString()
        }

        /** Formats kilobytes like docker stats (`151.2MiB`, `1.50GiB`); kept IDE-free. */
        @JvmStatic
        fun mem(kb: Long): String {
            if (kb < 0) {
                return "n/a"
            }
            val mib = kb / 1024.0
            return if (mib < 1024) String.format(Locale.US, "%.1fMiB", mib)
                   else String.format(Locale.US, "%.2fGiB", mib / 1024.0)
        }

        /** Formats a duration in ms like `42s`, `5m 12s`, `2h 08m`; kept IDE-free. */
        @JvmStatic
        fun durationText(ms: Long): String {
            if (ms < 0) {
                return "n/a"
            }
            val seconds = ms / 1000
            if (seconds < 60) {
                return "${seconds}s"
            }
            val minutes = seconds / 60
            if (minutes < 60) {
                return String.format(Locale.US, "%dm %02ds", minutes, seconds % 60)
            }
            val hours = minutes / 60
            return String.format(Locale.US, "%dh %02dm", hours, minutes % 60)
        }

        /** Serializes the samples to CSV: a header row then one row per sample (US decimal, ISO time). */
        @JvmStatic
        fun toCsv(samples: List<Sample>): String {
            val sb = StringBuilder("timestamp_ms,iso_time,rss_kb,percent\n")
            for (sample in samples) {
                sb.append(sample.timeMs).append(',')
                  .append(Instant.ofEpochMilli(sample.timeMs)).append(',')
                  .append(sample.rssKb).append(',')
                  .append(String.format(Locale.US, "%.2f", sample.percent)).append('\n')
            }
            return sb.toString()
        }
    }
}
