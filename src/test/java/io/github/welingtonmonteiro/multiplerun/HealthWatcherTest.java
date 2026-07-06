package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HealthWatcherTest {

    @Test
    public void downStreakCountsUpWhileDownAndResetsWhenUp() {
        assertEquals(1, MemoryLimitWatcher.nextDownStreak(0, true));
        assertEquals(3, MemoryLimitWatcher.nextDownStreak(2, true));
        assertEquals(0, MemoryLimitWatcher.nextDownStreak(5, false));
    }

    @Test
    public void alertsOnlyAtTheStreakLimitAndOnlyOnce() {
        // below the limit: no alert
        assertFalse(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK - 1, false));
        // at the limit and not yet notified: alert
        assertTrue(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK, false));
        // beyond the limit but already notified: stay quiet
        assertFalse(MemoryLimitWatcher.shouldAlertUnhealthy(MemoryLimitWatcher.UNHEALTHY_STREAK + 1, true));
    }
}
