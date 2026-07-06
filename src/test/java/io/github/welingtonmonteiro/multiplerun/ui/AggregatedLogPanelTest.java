package io.github.welingtonmonteiro.multiplerun.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AggregatedLogPanelTest {

    @Test
    public void formatLinePrefixesWithAppTag() {
        assertEquals("[api] Listening on 3000", AggregatedLogPanel.formatLine("api", "Listening on 3000"));
        assertEquals("[web] ", AggregatedLogPanel.formatLine("web", ""));
    }

    @Test
    public void emptyFilterMatchesEverything() {
        assertTrue(AggregatedLogPanel.matchesFilter("anything", ""));
        assertTrue(AggregatedLogPanel.matchesFilter("anything", "   "));
        assertTrue(AggregatedLogPanel.matchesFilter("anything", null));
    }

    @Test
    public void filterIsCaseInsensitiveSubstring() {
        assertTrue(AggregatedLogPanel.matchesFilter("ERROR: boom", "error"));
        assertTrue(AggregatedLogPanel.matchesFilter("Started on port 8080", "8080"));
        assertFalse(AggregatedLogPanel.matchesFilter("all good", "error"));
    }

    @Test
    public void colorIndexIsStablePerApp() {
        final Map<String, Integer> assigned = new LinkedHashMap<>();
        final int api = AggregatedLogPanel.colorIndexFor("api", assigned, 8);
        final int web = AggregatedLogPanel.colorIndexFor("web", assigned, 8);
        assertEquals(0, api);
        assertEquals(1, web);
        // same app keeps its color
        assertEquals(api, AggregatedLogPanel.colorIndexFor("api", assigned, 8));
        assertEquals(web, AggregatedLogPanel.colorIndexFor("web", assigned, 8));
    }

    @Test
    public void colorIndexCyclesThroughPalette() {
        final Map<String, Integer> assigned = new LinkedHashMap<>();
        assertEquals(0, AggregatedLogPanel.colorIndexFor("a", assigned, 2));
        assertEquals(1, AggregatedLogPanel.colorIndexFor("b", assigned, 2));
        // palette of 2 wraps around for the third distinct app
        assertEquals(0, AggregatedLogPanel.colorIndexFor("c", assigned, 2));
    }

    @Test
    public void splitKeepingLinesHandlesMultilineAndPartialChunks() {
        final List<String> lines = AggregatedLogPanel.splitKeepingLines("one\ntwo\n");
        assertEquals(2, lines.size());
        assertEquals("one", lines.get(0));
        assertEquals("two", lines.get(1));

        // no trailing newline: the last (partial) line is still emitted
        final List<String> partial = AggregatedLogPanel.splitKeepingLines("half");
        assertEquals(1, partial.size());
        assertEquals("half", partial.get(0));
    }

    @Test
    public void splitKeepingLinesStripsCarriageReturns() {
        final List<String> lines = AggregatedLogPanel.splitKeepingLines("win\r\nline\r\n");
        assertEquals(2, lines.size());
        assertEquals("win", lines.get(0));
        assertEquals("line", lines.get(1));
    }
}
