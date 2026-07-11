package io.github.welingtonmonteiro.multiplerun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultirunRunnerTest {

    @Test
    fun supportsRunDebugCoverageExecutors() {
        assertTrue(MultirunRunner.isSupportedExecutor("Run"))
        assertTrue(MultirunRunner.isSupportedExecutor("Debug"))
        assertTrue(MultirunRunner.isSupportedExecutor("Coverage"))
    }

    @Test
    fun supportsJRebelExecutors() {
        assertTrue(MultirunRunner.isSupportedExecutor(MultirunRunner.JREBEL_EXECUTOR_ID))
        assertTrue(MultirunRunner.isSupportedExecutor(MultirunRunner.JREBEL_DEBUG_ID))
    }

    @Test
    fun executorMatchingIsCaseInsensitive() {
        assertTrue(MultirunRunner.isSupportedExecutor("run"))
        assertTrue(MultirunRunner.isSupportedExecutor("DEBUG"))
        assertTrue(MultirunRunner.isSupportedExecutor("jrebel executor"))
    }

    @Test
    fun rejectsUnknownExecutors() {
        assertFalse(MultirunRunner.isSupportedExecutor("Profile Something Else"))
        assertFalse(MultirunRunner.isSupportedExecutor(""))
        assertFalse(MultirunRunner.isSupportedExecutor(null))
    }

    @Test
    fun memAlertThresholdIsClampedTo1To100() {
        assertEquals(1, MultirunRunConfiguration.clampMemAlertThreshold(0))
        assertEquals(1, MultirunRunConfiguration.clampMemAlertThreshold(-5))
        assertEquals(50, MultirunRunConfiguration.clampMemAlertThreshold(50))
        assertEquals(100, MultirunRunConfiguration.clampMemAlertThreshold(100))
        assertEquals(100, MultirunRunConfiguration.clampMemAlertThreshold(250))
    }

    @Test
    fun cpuAlertThresholdIsClampedTo0To1000AndZeroDisables() {
        assertEquals(0, MultirunRunConfiguration.clampCpuAlertThreshold(0))
        assertEquals(0, MultirunRunConfiguration.clampCpuAlertThreshold(-10))
        // above 100 is legitimate on multi-core machines (docker-stats-style CPU)
        assertEquals(400, MultirunRunConfiguration.clampCpuAlertThreshold(400))
        assertEquals(1000, MultirunRunConfiguration.clampCpuAlertThreshold(99999))
    }
}
