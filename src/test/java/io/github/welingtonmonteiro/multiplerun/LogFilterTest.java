package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import io.github.welingtonmonteiro.multiplerun.LogFilter.Level;
import io.github.welingtonmonteiro.multiplerun.LogFilter.Mode;

public class LogFilterTest {

    @Test
    public void identityFilterAcceptsEverything() {
        final LogFilter f = LogFilter.all();
        assertTrue(f.accepts("api", "anything at all"));
        assertTrue(f.accepts("web", ""));
    }

    @Test
    public void appFilterRestrictsToOneApplication() {
        final LogFilter f = LogFilter.from("api", "", Mode.ANY, false, false, false, null);
        assertTrue(f.accepts("api", "hello"));
        assertFalse(f.accepts("web", "hello"));
    }

    @Test
    public void splitTermsTrimsAndDropsEmpties() {
        assertEquals(Arrays.asList("a", "b", "c"), LogFilter.splitTerms(" a , b ,, c "));
        assertTrue(LogFilter.splitTerms("   ").isEmpty());
        assertTrue(LogFilter.splitTerms(null).isEmpty());
    }

    @Test
    public void anyVsAllCombineTerms() {
        final LogFilter any = LogFilter.from(null, "foo, bar", Mode.ANY, false, false, false, null);
        assertTrue(any.accepts("x", "only foo here"));
        assertFalse(any.accepts("x", "neither term"));

        final LogFilter all = LogFilter.from(null, "foo, bar", Mode.ALL, false, false, false, null);
        assertFalse(all.accepts("x", "only foo here"));
        assertTrue(all.accepts("x", "foo and bar together"));
    }

    @Test
    public void excludeHidesMatchingLines() {
        final LogFilter f = LogFilter.from(null, "healthcheck", Mode.ANY, true, false, false, null);
        assertFalse(f.accepts("x", "GET /healthcheck 200"));
        assertTrue(f.accepts("x", "GET /orders 200"));
    }

    @Test
    public void caseSensitivityIsHonored() {
        final LogFilter insensitive = LogFilter.from(null, "ERROR", Mode.ANY, false, false, false, null);
        assertTrue(insensitive.accepts("x", "an error happened"));

        final LogFilter sensitive = LogFilter.from(null, "ERROR", Mode.ANY, false, true, false, null);
        assertFalse(sensitive.accepts("x", "an error happened"));
        assertTrue(sensitive.accepts("x", "an ERROR happened"));
    }

    @Test
    public void regexTermsMatch() {
        final LogFilter f = LogFilter.from(null, "\\d{3,}", Mode.ANY, false, false, true, null);
        assertTrue(f.accepts("x", "listening on 3015"));
        assertFalse(f.accepts("x", "listening on 42"));
    }

    @Test
    public void invalidRegexNeverMatchesInsteadOfThrowing() {
        final LogFilter f = LogFilter.from(null, "[unclosed", Mode.ANY, false, false, true, null);
        assertFalse(f.accepts("x", "[unclosed bracket in text"));
    }

    @Test
    public void levelDetectionRecognizesCommonTokens() {
        assertEquals(Level.ERROR, LogFilter.detectLevel("2026-07-07 ERROR boom"));
        assertEquals(Level.WARN, LogFilter.detectLevel("[warn] low disk"));
        assertEquals(Level.INFO, LogFilter.detectLevel("INFO started"));
        assertNull(LogFilter.detectLevel("just some text"));
    }

    @Test
    public void minLevelKeepsOnlyAtOrAboveAndDropsUndetected() {
        final LogFilter warnPlus = LogFilter.from(null, "", Mode.ANY, false, false, false, Level.WARN);
        assertTrue(warnPlus.accepts("x", "ERROR fatal"));
        assertTrue(warnPlus.accepts("x", "WARN careful"));
        assertFalse(warnPlus.accepts("x", "INFO fyi"));
        assertFalse(warnPlus.accepts("x", "no level here"));
    }

    @Test
    public void appLevelAndTermsCombine() {
        final LogFilter f = LogFilter.from("api", "timeout", Mode.ANY, false, false, false, Level.WARN);
        assertTrue(f.accepts("api", "WARN request timeout"));
        assertFalse(f.accepts("web", "WARN request timeout")); // wrong app
        assertFalse(f.accepts("api", "INFO request timeout")); // below level
        assertFalse(f.accepts("api", "WARN slow response"));   // no term
    }
}
