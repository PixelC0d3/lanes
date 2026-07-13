package io.github.pixelcodes.lanes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatedLogPanelTest {

    @Test
    fun formatLinePrefixesWithAppTag() {
        assertEquals("[api] Listening on 3000", AggregatedLogPanel.formatLine("api", "Listening on 3000"))
        assertEquals("[web] ", AggregatedLogPanel.formatLine("web", ""))
    }

    @Test
    fun emptyFilterMatchesEverything() {
        assertTrue(AggregatedLogPanel.matchesFilter("anything", ""))
        assertTrue(AggregatedLogPanel.matchesFilter("anything", "   "))
        assertTrue(AggregatedLogPanel.matchesFilter("anything", null))
    }

    @Test
    fun filterIsCaseInsensitiveSubstring() {
        assertTrue(AggregatedLogPanel.matchesFilter("ERROR: boom", "error"))
        assertTrue(AggregatedLogPanel.matchesFilter("Started on port 8080", "8080"))
        assertFalse(AggregatedLogPanel.matchesFilter("all good", "error"))
    }

    @Test
    fun colorIndexIsStablePerApp() {
        val assigned = LinkedHashMap<String, Int>()
        val api = AggregatedLogPanel.colorIndexFor("api", assigned, 8)
        val web = AggregatedLogPanel.colorIndexFor("web", assigned, 8)
        assertEquals(0, api)
        assertEquals(1, web)
        // same app keeps its color
        assertEquals(api, AggregatedLogPanel.colorIndexFor("api", assigned, 8))
        assertEquals(web, AggregatedLogPanel.colorIndexFor("web", assigned, 8))
    }

    @Test
    fun colorIndexCyclesThroughPalette() {
        val assigned = LinkedHashMap<String, Int>()
        assertEquals(0, AggregatedLogPanel.colorIndexFor("a", assigned, 2))
        assertEquals(1, AggregatedLogPanel.colorIndexFor("b", assigned, 2))
        // palette of 2 wraps around for the third distinct app
        assertEquals(0, AggregatedLogPanel.colorIndexFor("c", assigned, 2))
    }

    @Test
    fun splitKeepingLinesHandlesMultilineAndPartialChunks() {
        val lines = AggregatedLogPanel.splitKeepingLines("one\ntwo\n")
        assertEquals(2, lines.size)
        assertEquals("one", lines[0])
        assertEquals("two", lines[1])

        // no trailing newline: the last (partial) line is still emitted
        val partial = AggregatedLogPanel.splitKeepingLines("half")
        assertEquals(1, partial.size)
        assertEquals("half", partial[0])
    }

    @Test
    fun splitKeepingLinesStripsCarriageReturns() {
        val lines = AggregatedLogPanel.splitKeepingLines("win\r\nline\r\n")
        assertEquals(2, lines.size)
        assertEquals("win", lines[0])
        assertEquals("line", lines[1])
    }

    private val esc = "\u001B"

    @Test
    fun stripAnsiRemovesColorCodesButKeepsPlainBrackets() {
        assertEquals("HELLO world", AggregatedLogPanel.stripAnsi(esc + "[32mHELLO" + esc + "[0m world"))
        // brackets that are not part of an escape sequence must survive untouched
        assertEquals("[INFO] started", AggregatedLogPanel.stripAnsi("[INFO] started"))
        assertEquals("", AggregatedLogPanel.stripAnsi(null))
    }

    @Test
    fun stripAnsiAlsoRemovesCursorAndEraseSequences() {
        assertEquals("done", AggregatedLogPanel.stripAnsi(esc + "[2K" + esc + "[1Gdone"))
    }

    @Test
    fun parseAnsiConcatenationEqualsStrippedText() {
        val raw = esc + "[1m" + esc + "[31mERR" + esc + "[0m ok"
        val sb = StringBuilder()
        for (span in AggregatedLogPanel.parseAnsi(raw)) {
            sb.append(span.text)
        }
        assertEquals(AggregatedLogPanel.stripAnsi(raw), sb.toString())
    }

    @Test
    fun parseAnsiAppliesForegroundAndBoldThenResets() {
        val spans = AggregatedLogPanel.parseAnsi(esc + "[1;32mgo" + esc + "[0mstop")
        assertEquals("go", spans[0].text)
        assertEquals(2, spans[0].fgIndex) // green
        assertTrue(spans[0].bold)
        assertEquals("stop", spans[1].text)
        assertEquals(-1, spans[1].fgIndex) // reset to default
        assertFalse(spans[1].bold)
    }

    @Test
    fun parseAnsiMapsBrightColors() {
        val spans = AggregatedLogPanel.parseAnsi(esc + "[92mx")
        assertEquals(10, spans[0].fgIndex) // bright green = 2 + 8
    }

    @Test
    fun applySgrHandlesResetColorAndBold() {
        assertEquals(-1, AggregatedLogPanel.applySgr("0", 5, true)[0])
        assertEquals(0, AggregatedLogPanel.applySgr("0", 5, true)[1])
        assertEquals(1, AggregatedLogPanel.applySgr("31", -1, false)[0]) // red
        assertEquals(1, AggregatedLogPanel.applySgr("1", 3, false)[1])  // bold on, fg kept
        assertEquals(3, AggregatedLogPanel.applySgr("1", 3, false)[0])
    }
}
