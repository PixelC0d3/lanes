package io.github.pixelcodes.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthWatcherTest {

    @Test
    fun downStreakCountsUpWhileDownAndResetsWhenUp() {
        assertEquals(1, MemoryLimitWatcher.nextDownStreak(0, true))
        assertEquals(3, MemoryLimitWatcher.nextDownStreak(2, true))
        assertEquals(0, MemoryLimitWatcher.nextDownStreak(5, false))
    }

    @Test
    fun alertsOnlyAtTheStreakLimitAndOnlyOnce() {
        // below the limit: no alert
        assertFalse(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK - 1, false))
        // at the limit and not yet notified: alert
        assertTrue(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK, false))
        // beyond the limit but already notified: stay quiet
        assertFalse(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK + 1, true))
    }

    @Test
    fun cpuIsSustainedOnlyAtOrAboveTheCheckCount() {
        assertFalse(MemoryLimitWatcher.isCpuSustained(MemoryLimitWatcher.CPU_SUSTAINED_CHECKS - 1,
                                                       MemoryLimitWatcher.CPU_SUSTAINED_CHECKS))
        assertTrue(MemoryLimitWatcher.isCpuSustained(MemoryLimitWatcher.CPU_SUSTAINED_CHECKS,
                                                      MemoryLimitWatcher.CPU_SUSTAINED_CHECKS))
        assertTrue(MemoryLimitWatcher.isCpuSustained(MemoryLimitWatcher.CPU_SUSTAINED_CHECKS + 5,
                                                      MemoryLimitWatcher.CPU_SUSTAINED_CHECKS))
    }
}
