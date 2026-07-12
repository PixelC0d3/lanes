package io.github.welingtonmonteiro.multiplerun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplerunRunnerTest {

    @Test
    fun supportsRunDebugCoverageExecutors() {
        assertTrue(MultiplerunRunner.isSupportedExecutor("Run"))
        assertTrue(MultiplerunRunner.isSupportedExecutor("Debug"))
        assertTrue(MultiplerunRunner.isSupportedExecutor("Coverage"))
    }

    @Test
    fun supportsJRebelExecutors() {
        assertTrue(MultiplerunRunner.isSupportedExecutor(MultiplerunRunner.JREBEL_EXECUTOR_ID))
        assertTrue(MultiplerunRunner.isSupportedExecutor(MultiplerunRunner.JREBEL_DEBUG_ID))
    }

    @Test
    fun executorMatchingIsCaseInsensitive() {
        assertTrue(MultiplerunRunner.isSupportedExecutor("run"))
        assertTrue(MultiplerunRunner.isSupportedExecutor("DEBUG"))
        assertTrue(MultiplerunRunner.isSupportedExecutor("jrebel executor"))
    }

    @Test
    fun rejectsUnknownExecutors() {
        assertFalse(MultiplerunRunner.isSupportedExecutor("Profile Something Else"))
        assertFalse(MultiplerunRunner.isSupportedExecutor(""))
        assertFalse(MultiplerunRunner.isSupportedExecutor(null))
    }

    @Test
    fun memAlertThresholdIsClampedTo1To100() {
        assertEquals(1, MultiplerunRunConfiguration.clampMemAlertThreshold(0))
        assertEquals(1, MultiplerunRunConfiguration.clampMemAlertThreshold(-5))
        assertEquals(50, MultiplerunRunConfiguration.clampMemAlertThreshold(50))
        assertEquals(100, MultiplerunRunConfiguration.clampMemAlertThreshold(100))
        assertEquals(100, MultiplerunRunConfiguration.clampMemAlertThreshold(250))
    }

    @Test
    fun cpuAlertThresholdIsClampedTo0To1000AndZeroDisables() {
        assertEquals(0, MultiplerunRunConfiguration.clampCpuAlertThreshold(0))
        assertEquals(0, MultiplerunRunConfiguration.clampCpuAlertThreshold(-10))
        // above 100 is legitimate on multi-core machines (docker-stats-style CPU)
        assertEquals(400, MultiplerunRunConfiguration.clampCpuAlertThreshold(400))
        assertEquals(1000, MultiplerunRunConfiguration.clampCpuAlertThreshold(99999))
    }
}
