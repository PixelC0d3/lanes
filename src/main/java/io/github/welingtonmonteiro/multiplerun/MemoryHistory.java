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
