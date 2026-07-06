package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class MemoryHistoryTest {

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
