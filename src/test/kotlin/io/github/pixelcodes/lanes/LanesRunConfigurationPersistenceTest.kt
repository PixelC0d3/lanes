package io.github.pixelcodes.lanes

import com.intellij.testFramework.fixtures.BasePlatformTestCase

import org.jdom.Element

/**
 * Round trip of the run configuration through `writeExternal`/`readExternal`.
 *
 * This is what the IDE calls to store a Lanes group in `.idea/workspace.xml` and read it back, so
 * a regression here silently loses a user's configured groups - the worst failure this plugin can
 * have, and until now the only coverage was indirect. Needs a real project because building a
 * configuration goes through the platform's factory.
 */
class LanesRunConfigurationPersistenceTest : BasePlatformTestCase() {

    private fun newConfiguration(name: String): LanesRunConfiguration {
        val type = LanesConfigurationType()
        val factory = type.getConfigurationFactories()[0]
        val configuration = factory.createTemplateConfiguration(project) as LanesRunConfiguration
        // the name matters: per-app settings are keyed by it
        configuration.setName(name)
        return configuration
    }

    /** Writes [source] out and reads it back into a fresh instance, as the IDE does. */
    private fun roundTrip(source: LanesRunConfiguration): LanesRunConfiguration {
        val element = Element("configuration")
        source.writeExternal(element)
        val restored = newConfiguration("restored")
        restored.readExternal(element)
        return restored
    }

    fun testExecutionOptionsSurviveTheRoundTrip() {
        val original = newConfiguration("group")
        original.setStartOneByOne(true)
        original.setDelayTime(2.5)
        original.setRestartRunning(false)
        original.setRestartOnCrash(true)

        val restored = roundTrip(original)

        assertTrue(restored.isStartOneByOne())
        assertEquals(2.5, restored.getDelayTime(), 0.0001)
        assertFalse(restored.isRestartRunning())
        assertTrue(restored.isRestartOnCrash())
    }

    fun testEnvironmentProfilesSurviveTheRoundTrip() {
        val original = newConfiguration("group")
        original.setEnvProfiles(listOf(".local.def.env", ".local.com.env"))
        original.setEnvFilePath(".local.com.env")

        val restored = roundTrip(original)

        assertEquals(listOf(".local.def.env", ".local.com.env"), restored.getEnvProfiles())
        assertEquals(".local.com.env", restored.getEnvFilePath())
    }

    fun testPerApplicationSettingsSurviveTheRoundTrip() {
        val original = newConfiguration("group")
        // per-app settings are stored as attributes of each app entry, so they only persist for
        // applications that are actually in the group - that is the contract being pinned here
        original.setRunConfigurations(listOf(newConfiguration("api"), newConfiguration("ui")))
        original.setMemoryLimits(mapOf("api" to 2048, "ui" to 512))
        original.setReadyConditions(mapOf("api" to "port:3003"))
        original.setAppEnvFiles(mapOf("api" to ".api.env"))
        original.setDisabledApps(setOf("ui"))

        val restored = roundTrip(original)

        assertEquals(mapOf("api" to 2048, "ui" to 512), restored.getMemoryLimits())
        assertEquals(mapOf("api" to "port:3003"), restored.getReadyConditions())
        assertEquals(mapOf("api" to ".api.env"), restored.getAppEnvFiles())
        assertEquals(setOf("ui"), restored.getDisabledApps())
    }

    fun testSettingsOfApplicationsNotInTheGroupAreNotPersisted() {
        // the flip side of the contract above: a limit for an app that is not in the list has
        // nowhere to be written, so it must not silently reappear
        val original = newConfiguration("group")
        original.setMemoryLimits(mapOf("ghost" to 2048))

        assertTrue(roundTrip(original).getMemoryLimits().isEmpty())
    }

    fun testAlertThresholdsSurviveTheRoundTrip() {
        val original = newConfiguration("group")
        original.setMemAlertThreshold(75)
        original.setMemLimitRestart(true)
        original.setCpuAlertThreshold(150)

        val restored = roundTrip(original)

        assertEquals(75, restored.getMemAlertThreshold())
        assertTrue(restored.isMemLimitRestart())
        assertEquals(150, restored.getCpuAlertThreshold())
    }

    fun testAnEmptyConfigurationRoundTripsToDefaults() {
        // a group saved before a field existed must still read back cleanly
        val restored = roundTrip(newConfiguration("group"))

        assertNotNull(restored.getEnvProfiles())
        assertTrue(restored.getMemoryLimits().isEmpty())
        assertTrue(restored.getDisabledApps().isEmpty())
        assertEquals("", restored.getEnvFilePath())
    }
}
