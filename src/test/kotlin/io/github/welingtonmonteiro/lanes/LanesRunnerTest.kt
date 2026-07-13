package io.github.welingtonmonteiro.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanesRunnerTest {

    @Test
    fun supportsRunDebugCoverageExecutors() {
        assertTrue(LanesRunner.isSupportedExecutor("Run"))
        assertTrue(LanesRunner.isSupportedExecutor("Debug"))
        assertTrue(LanesRunner.isSupportedExecutor("Coverage"))
    }

    @Test
    fun supportsJRebelExecutors() {
        assertTrue(LanesRunner.isSupportedExecutor(LanesRunner.JREBEL_EXECUTOR_ID))
        assertTrue(LanesRunner.isSupportedExecutor(LanesRunner.JREBEL_DEBUG_ID))
    }

    @Test
    fun executorMatchingIsCaseInsensitive() {
        assertTrue(LanesRunner.isSupportedExecutor("run"))
        assertTrue(LanesRunner.isSupportedExecutor("DEBUG"))
        assertTrue(LanesRunner.isSupportedExecutor("jrebel executor"))
    }

    @Test
    fun rejectsUnknownExecutors() {
        assertFalse(LanesRunner.isSupportedExecutor("Profile Something Else"))
        assertFalse(LanesRunner.isSupportedExecutor(""))
        assertFalse(LanesRunner.isSupportedExecutor(null))
    }

    @Test
    fun memAlertThresholdIsClampedTo1To100() {
        assertEquals(1, LanesRunConfiguration.clampMemAlertThreshold(0))
        assertEquals(1, LanesRunConfiguration.clampMemAlertThreshold(-5))
        assertEquals(50, LanesRunConfiguration.clampMemAlertThreshold(50))
        assertEquals(100, LanesRunConfiguration.clampMemAlertThreshold(100))
        assertEquals(100, LanesRunConfiguration.clampMemAlertThreshold(250))
    }

    @Test
    fun cpuAlertThresholdIsClampedTo0To1000AndZeroDisables() {
        assertEquals(0, LanesRunConfiguration.clampCpuAlertThreshold(0))
        assertEquals(0, LanesRunConfiguration.clampCpuAlertThreshold(-10))
        // above 100 is legitimate on multi-core machines (docker-stats-style CPU)
        assertEquals(400, LanesRunConfiguration.clampCpuAlertThreshold(400))
        assertEquals(1000, LanesRunConfiguration.clampCpuAlertThreshold(99999))
    }
}
