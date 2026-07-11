package io.github.welingtonmonteiro.multiplerun.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultirunStatusBarWidgetTest {

    @Test
    fun emptyWhenNothingRunning() {
        assertEquals("", MultirunStatusBarWidget.widgetText(0, 12345, 0))
    }

    @Test
    fun singularAndPluralAppLabel() {
        assertTrue(MultirunStatusBarWidget.widgetText(1, 1024, 0).startsWith("▶ 1 app ·"))
        assertTrue(MultirunStatusBarWidget.widgetText(3, 1024, 0).startsWith("▶ 3 apps ·"))
    }

    @Test
    fun unhealthySuffixOnlyWhenPresent() {
        assertFalse(MultirunStatusBarWidget.widgetText(2, 1024, 0).contains("⚠"))
        assertTrue(MultirunStatusBarWidget.widgetText(2, 1024, 1).contains("⚠ 1"))
    }

    @Test
    fun includesFormattedMemory() {
        val text = MultirunStatusBarWidget.widgetText(1, 1024L * 1024L, 0) // 1 GiB in KB
        assertTrue(text.contains("GiB") || text.contains("MiB"))
    }
}
