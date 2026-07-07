package io.github.welingtonmonteiro.multiplerun;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The recorded memory history of one application over a session, plus its CSV serialization. Kept
 * free of IDE dependencies so the formatting is unit-testable in isolation.
 */
public final class MemoryHistory {

    /** One memory measurement of an application at a point in time. */
    public static final class Sample {
        public final long timeMs;
        public final long rssKb;
        public final double percent;

        public Sample(long timeMs, long rssKb, double percent) {
            this.timeMs = timeMs;
            this.rssKb = rssKb;
            this.percent = percent;
        }
    }

    private MemoryHistory() {
    }

    /** How the memory of an application is trending over the recorded session. */
    public enum Trend { INSUFFICIENT_DATA, STABLE, GROWING, SHRINKING }

    /**
     * A memory-trend summary of a session, used by the monitor's leak analysis. All figures come
     * straight from the recorded samples (no IDE dependency), so this is unit-tested in isolation.
     */
    public static final class Analysis {
        public final int samples;
        public final long durationMs;
        public final long firstRssKb;
        public final long lastRssKb;
        public final long minRssKb;
        public final long maxRssKb;
        /** last - first: net memory change over the session (can be negative). */
        public final long netChangeKb;
        /** Slope of a least-squares fit of RSS over time, in KB per minute. */
        public final double slopeKbPerMin;
        /** Slope projected to one hour, in KB (slope * 60): how fast it grows/shrinks per hour. */
        public final long projectedPerHourKb;
        /** R² of the linear fit in [0,1]: how steadily (linearly) memory moves - high = steady leak. */
        public final double rSquared;
        /** Fraction of consecutive samples that did not decrease, in [0,1]: 1 = never freed memory. */
        public final double monotonicFraction;
        public final Trend trend;

        Analysis(int samples, long durationMs, long firstRssKb, long lastRssKb, long minRssKb,
                 long maxRssKb, long netChangeKb, double slopeKbPerMin, long projectedPerHourKb,
                 double rSquared, double monotonicFraction, Trend trend) {
            this.samples = samples;
            this.durationMs = durationMs;
            this.firstRssKb = firstRssKb;
            this.lastRssKb = lastRssKb;
            this.minRssKb = minRssKb;
            this.maxRssKb = maxRssKb;
            this.netChangeKb = netChangeKb;
            this.slopeKbPerMin = slopeKbPerMin;
            this.projectedPerHourKb = projectedPerHourKb;
            this.rSquared = rSquared;
            this.monotonicFraction = monotonicFraction;
            this.trend = trend;
        }

        /** True when growth is both sustained (positive slope) and steady/linear (high R²). */
        public boolean isSteadyLeak() {
            return trend == Trend.GROWING && rSquared >= 0.75;
        }
    }

    /**
     * Summarizes a memory history into a trend/leak verdict. Fewer than three samples yields
     * {@link Trend#INSUFFICIENT_DATA}. Otherwise the net change is compared against a threshold of
     * 10% of the starting RSS (at least 5 MiB): a rise beyond it with a positive least-squares slope
     * reads as {@link Trend#GROWING} (a possible leak), a matching fall as {@link Trend#SHRINKING},
     * everything else as {@link Trend#STABLE}.
     */
    public static Analysis analyze(List<Sample> samples) {
        final int n = samples.size();
        if (n < 3) {
            final long only = n == 0 ? 0 : samples.get(0).rssKb;
            return new Analysis(n, 0, only, only, only, only, 0, 0, 0, 0, 0, Trend.INSUFFICIENT_DATA);
        }
        final long firstTime = samples.get(0).timeMs;
        final long firstRss = samples.get(0).rssKb;
        final long lastRss = samples.get(n - 1).rssKb;
        final long durationMs = samples.get(n - 1).timeMs - firstTime;
        long minRss = Long.MAX_VALUE;
        long maxRss = Long.MIN_VALUE;
        long prevRss = firstRss;
        int nonDecreasing = 0;
        // least-squares slope of rss (KB) over time (minutes), plus the terms for R²
        double sumX = 0, sumY = 0, sumXX = 0, sumYY = 0, sumXY = 0;
        for (int i = 0; i < n; i++) {
            final Sample sample = samples.get(i);
            final double x = (sample.timeMs - firstTime) / 60_000.0;
            final double y = sample.rssKb;
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumYY += y * y;
            sumXY += x * y;
            minRss = Math.min(minRss, sample.rssKb);
            maxRss = Math.max(maxRss, sample.rssKb);
            if (i > 0) {
                if (sample.rssKb >= prevRss) {
                    nonDecreasing++;
                }
                prevRss = sample.rssKb;
            }
        }
        final double denom = n * sumXX - sumX * sumX;
        final double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;

        // R² of the fit: (covariance)² / (var(x)·var(y)); 0 when either variance is degenerate
        final double covTerm = n * sumXY - sumX * sumY;
        final double varXTerm = n * sumXX - sumX * sumX;
        final double varYTerm = n * sumYY - sumY * sumY;
        final double rSquared = (varXTerm <= 0 || varYTerm <= 0)
                ? 0 : Math.max(0, Math.min(1, (covTerm * covTerm) / (varXTerm * varYTerm)));
        final double monotonicFraction = (double) nonDecreasing / (n - 1);
        final long projectedPerHourKb = Math.round(slope * 60);

        final long netChange = lastRss - firstRss;
        final long threshold = Math.max(firstRss / 10, 5 * 1024L); // 10% of start, min 5 MiB
        final Trend trend;
        if (slope > 0 && netChange > threshold) {
            trend = Trend.GROWING;
        } else if (slope < 0 && -netChange > threshold) {
            trend = Trend.SHRINKING;
        } else {
            trend = Trend.STABLE;
        }
        return new Analysis(n, durationMs, firstRss, lastRss, minRss, maxRss, netChange, slope,
                            projectedPerHourKb, rSquared, monotonicFraction, trend);
    }

    /** Human-readable verdict for the trend (used in the UI and the exported report). */
    public static String verdictText(Analysis a) {
        switch (a.trend) {
            case GROWING:      return a.isSteadyLeak() ? "Growing - likely memory leak (steady)"
                                                       : "Growing - possible memory leak";
            case SHRINKING:    return "Shrinking";
            case STABLE:       return "Stable";
            default:           return "Insufficient data";
        }
    }

    /** Multi-line, plain-text summary of an analysis - the header of the exported report. */
    public static String summaryText(String appName, Analysis a) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Memory analysis - ").append(appName == null ? "application" : appName).append('\n');
        sb.append("Verdict: ").append(verdictText(a)).append('\n');
        if (a.trend == Trend.INSUFFICIENT_DATA) {
            sb.append("Not enough samples yet to establish a trend.\n");
            return sb.toString();
        }
        sb.append(String.format(Locale.US, "Trend: %+.1f MiB/min (~ %s%s/h), R2=%.2f, memory not freed %.0f%% of the time%n",
                                a.slopeKbPerMin / 1024.0,
                                a.projectedPerHourKb >= 0 ? "+" : "-", mem(Math.abs(a.projectedPerHourKb)),
                                a.rSquared, a.monotonicFraction * 100));
        sb.append("Duration: ").append(durationText(a.durationMs)).append(" over ").append(a.samples).append(" samples\n");
        final String sign = a.netChangeKb >= 0 ? "+" : "-";
        sb.append("First -> last: ").append(mem(a.firstRssKb)).append(" -> ").append(mem(a.lastRssKb))
          .append(" (").append(sign).append(mem(Math.abs(a.netChangeKb))).append(")\n");
        sb.append("Min / peak: ").append(mem(a.minRssKb)).append(" / ").append(mem(a.maxRssKb)).append('\n');
        return sb.toString();
    }

    /** Formats kilobytes like docker stats ({@code 151.2MiB}, {@code 1.50GiB}); kept IDE-free. */
    static String mem(long kb) {
        if (kb < 0) {
            return "n/a";
        }
        final double mib = kb / 1024.0;
        return mib < 1024 ? String.format(Locale.US, "%.1fMiB", mib)
                          : String.format(Locale.US, "%.2fGiB", mib / 1024.0);
    }

    /** Formats a duration in ms like {@code 42s}, {@code 5m 12s}, {@code 2h 08m}; kept IDE-free. */
    static String durationText(long ms) {
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
        return String.format(Locale.US, "%dh %02dm", hours, minutes % 60);
    }

    /** Serializes the samples to CSV: a header row then one row per sample (US decimal, ISO time). */
    public static String toCsv(List<Sample> samples) {
        final StringBuilder sb = new StringBuilder("timestamp_ms,iso_time,rss_kb,percent\n");
        for (Sample sample : samples) {
            sb.append(sample.timeMs).append(',')
              .append(Instant.ofEpochMilli(sample.timeMs)).append(',')
              .append(sample.rssKb).append(',')
              .append(String.format(Locale.US, "%.2f", sample.percent)).append('\n');
        }
        return sb.toString();
    }
}
