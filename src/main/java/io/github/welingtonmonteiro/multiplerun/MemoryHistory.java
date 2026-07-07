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
        public final Trend trend;

        Analysis(int samples, long durationMs, long firstRssKb, long lastRssKb, long minRssKb,
                 long maxRssKb, long netChangeKb, double slopeKbPerMin, Trend trend) {
            this.samples = samples;
            this.durationMs = durationMs;
            this.firstRssKb = firstRssKb;
            this.lastRssKb = lastRssKb;
            this.minRssKb = minRssKb;
            this.maxRssKb = maxRssKb;
            this.netChangeKb = netChangeKb;
            this.slopeKbPerMin = slopeKbPerMin;
            this.trend = trend;
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
            return new Analysis(n, 0, only, only, only, only, 0, 0, Trend.INSUFFICIENT_DATA);
        }
        final long firstTime = samples.get(0).timeMs;
        final long firstRss = samples.get(0).rssKb;
        final long lastRss = samples.get(n - 1).rssKb;
        final long durationMs = samples.get(n - 1).timeMs - firstTime;
        long minRss = Long.MAX_VALUE;
        long maxRss = Long.MIN_VALUE;
        // least-squares slope of rss (KB) over time (minutes)
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
        for (Sample sample : samples) {
            final double x = (sample.timeMs - firstTime) / 60_000.0;
            final double y = sample.rssKb;
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
            minRss = Math.min(minRss, sample.rssKb);
            maxRss = Math.max(maxRss, sample.rssKb);
        }
        final double denom = n * sumXX - sumX * sumX;
        final double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;

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
        return new Analysis(n, durationMs, firstRss, lastRss, minRss, maxRss, netChange, slope, trend);
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
