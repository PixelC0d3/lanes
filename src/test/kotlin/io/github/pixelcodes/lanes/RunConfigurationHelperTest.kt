package io.github.pixelcodes.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.jdom.Element

import com.intellij.execution.configuration.EnvironmentVariablesData

/**
 * Covers the env file loading scenario: dotenv parsing, merge precedence (manual variables win
 * over the file) and the fallbacks for a missing/empty file.
 */
class RunConfigurationHelperTest {

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    private fun envFile(content: String): File {
        // a dotfile, exactly like the real-world .env / .local.env files
        val file = tempDir.newFile(".local.env")
        Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
        return file
    }

    // --- parseEnvFile ---------------------------------------------------------------------

    @Test
    fun parsesPlainKeyValueLines() {
        val file = envFile("DISTRIBUTION_CHANNEL=10\nALLOWED_DISTRIBUTION_CHANNEL=10\nENV_PREFIX=-com\n")

        val vars = RunConfigurationHelper.parseEnvFile(file)

        assertEquals(3, vars.size)
        assertEquals("10", vars["DISTRIBUTION_CHANNEL"])
        assertEquals("10", vars["ALLOWED_DISTRIBUTION_CHANNEL"])
        assertEquals("-com", vars["ENV_PREFIX"])
    }

    @Test
    fun skipsBlankLinesAndComments() {
        val file = envFile("\n# comment line\n  \nKEY=value\n#OTHER=ignored\n")

        val vars = RunConfigurationHelper.parseEnvFile(file)

        assertEquals(mapOf("KEY" to "value"), vars)
    }

    @Test
    fun acceptsExportPrefix() {
        val file = envFile("export KEY=value\n")

        assertEquals(mapOf("KEY" to "value"), RunConfigurationHelper.parseEnvFile(file))
    }

    @Test
    fun stripsMatchingQuotes() {
        val file = envFile("DOUBLE=\"quoted value\"\nSINGLE='single quoted'\nMIXED=\"not'stripped\n")

        val vars = RunConfigurationHelper.parseEnvFile(file)

        assertEquals("quoted value", vars["DOUBLE"])
        assertEquals("single quoted", vars["SINGLE"])
        assertEquals("\"not'stripped", vars["MIXED"])
    }

    @Test
    fun keepsEqualsSignInsideValue() {
        val file = envFile("CONNECTION=host=localhost;port=5432\n")

        assertEquals("host=localhost;port=5432", RunConfigurationHelper.parseEnvFile(file)["CONNECTION"])
    }

    @Test
    fun ignoresLinesWithoutKey() {
        val file = envFile("=value\nno-equals-line\nVALID=1\n")

        assertEquals(mapOf("VALID" to "1"), RunConfigurationHelper.parseEnvFile(file))
    }

    @Test
    fun trimsWhitespaceAroundKeyAndValue() {
        val file = envFile("  KEY  =  value  \n")

        assertEquals(mapOf("KEY" to "value"), RunConfigurationHelper.parseEnvFile(file))
    }

    // --- withEnvFile (merge precedence) ---------------------------------------------------

    @Test
    fun fileVariablesAreAppliedWhenNoManualOnesExist() {
        val file = envFile("ENV_PREFIX=-com\nDISTRIBUTION_CHANNEL=10\n")

        val result = RunConfigurationHelper.withEnvFile(EnvironmentVariablesData.DEFAULT, file.absolutePath, null)

        assertEquals("-com", result.getEnvs()["ENV_PREFIX"])
        assertEquals("10", result.getEnvs()["DISTRIBUTION_CHANNEL"])
        assertTrue(result.isPassParentEnvs())
    }

    @Test
    fun manualVariablesWinOverTheFile() {
        val file = envFile("ENV_PREFIX=-com\nDISTRIBUTION_CHANNEL=10\n")
        val manual = EnvironmentVariablesData.create(mapOf("ENV_PREFIX" to "-def"), true)

        val result = RunConfigurationHelper.withEnvFile(manual, file.absolutePath, null)

        assertEquals("manual entry must win on conflicts", "-def", result.getEnvs()["ENV_PREFIX"])
        assertEquals("file entry must fill the gaps", "10", result.getEnvs()["DISTRIBUTION_CHANNEL"])
    }

    @Test
    fun passParentEnvsFlagIsPreserved() {
        val file = envFile("KEY=value\n")
        val manual = EnvironmentVariablesData.create(emptyMap(), false)

        val result = RunConfigurationHelper.withEnvFile(manual, file.absolutePath, null)

        assertFalse(result.isPassParentEnvs())
        assertEquals("value", result.getEnvs()["KEY"])
    }

    @Test
    fun missingFileKeepsEnvDataUnchanged() {
        val manual = EnvironmentVariablesData.create(mapOf("KEY" to "value"), true)

        val result = RunConfigurationHelper.withEnvFile(manual, "/does/not/exist/.env", null)

        assertSame("the run must still start with the manual variables", manual, result)
    }

    @Test
    fun emptyOrNullPathKeepsEnvDataUnchanged() {
        val manual = EnvironmentVariablesData.create(mapOf("KEY" to "value"), true)

        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, "", null))
        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, "   ", null))
        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, null, null))
    }

    @Test
    fun envFileActivatesTheOverrideEvenWithoutManualVariables() {
        val file = envFile("KEY=value\n")

        val result = RunConfigurationHelper.withEnvFile(EnvironmentVariablesData.DEFAULT, file.absolutePath, null)

        assertTrue("a loaded file must make the override active, otherwise nothing is injected",
                   RunConfigurationHelper.isEnvOverrideActive(result))
    }

    // --- resolveEnvFile -------------------------------------------------------------------

    @Test
    fun absolutePathIsUsedAsIs() {
        val resolved = RunConfigurationHelper.resolveEnvFile("/tmp/some/.env", null)

        assertEquals(File("/tmp/some/.env"), resolved)
    }

    // --- mergeEnvData ---------------------------------------------------------------------

    @Test
    fun mergeOverrideWinsOnConflicts() {
        val base = EnvironmentVariablesData.create(mapOf("ENV_PREFIX" to "-def"), true)
        val override = EnvironmentVariablesData.create(mapOf("ENV_PREFIX" to "-com"), false)

        val merged = RunConfigurationHelper.mergeEnvData(base, override)

        assertEquals("-com", merged.getEnvs()["ENV_PREFIX"])
        assertFalse("pass-parent-envs flag must come from the override", merged.isPassParentEnvs())
    }

    // --- withMemoryLimit (per-application memory cap) ---------------------------------------

    @Test
    fun memoryLimitSetsNodeAndJvmOptions() {
        val result = RunConfigurationHelper.withMemoryLimit(EnvironmentVariablesData.DEFAULT, 1024)

        assertEquals("--max-old-space-size=1024", result.getEnvs()["NODE_OPTIONS"])
        assertEquals("-Xmx1024m", result.getEnvs()["JAVA_TOOL_OPTIONS"])
        assertTrue(result.isPassParentEnvs())
    }

    @Test
    fun memoryLimitAppendsToExistingOptions() {
        val base = EnvironmentVariablesData.create(mapOf("NODE_OPTIONS" to "--enable-source-maps"), true)

        val result = RunConfigurationHelper.withMemoryLimit(base, 512)

        assertEquals("user options must be preserved",
                     "--enable-source-maps --max-old-space-size=512", result.getEnvs()["NODE_OPTIONS"])
    }

    @Test
    fun memoryLimitActivatesTheOverride() {
        val result = RunConfigurationHelper.withMemoryLimit(EnvironmentVariablesData.DEFAULT, 256)

        assertTrue("a memory limit alone must trigger the injection",
                   RunConfigurationHelper.isEnvOverrideActive(result))
    }

    // --- consoleLogFileName (save console logs feature) ------------------------------------

    @Test
    fun consoleLogFileNameKeepsSafeNames() {
        assertEquals("eparts-api.log", RunConfigurationHelper.consoleLogFileName("eparts-api"))
        assertEquals("My App 2.log", RunConfigurationHelper.consoleLogFileName("My App 2"))
    }

    @Test
    fun consoleLogFileNameSanitizesUnsafeCharacters() {
        assertEquals("api_v2_ prod_.log", RunConfigurationHelper.consoleLogFileName("api/v2: prod*"))
    }

    @Test
    fun consoleLogFileNameFallsBackForBlankNames() {
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName(""))
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName("   "))
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName(null))
    }

    // --- parseDelay (locale tolerance) ----------------------------------------------------

    @Test
    fun delayAcceptsBothDecimalSeparators() {
        assertEquals(-1.0, LanesRunConfiguration.parseDelay("-1,0"), 0.0001)
        assertEquals(-1.0, LanesRunConfiguration.parseDelay("-1.0"), 0.0001)
        assertEquals(0.5, LanesRunConfiguration.parseDelay("0,5"), 0.0001)
        assertEquals(0.5, LanesRunConfiguration.parseDelay("0.5"), 0.0001)
    }

    @Test(expected = NumberFormatException::class)
    fun delayRejectsNonNumericInput() {
        LanesRunConfiguration.parseDelay("abc")
    }

    // --- environment profiles (env file dropdown) ------------------------------------------

    @Test
    @Suppress("UNCHECKED_CAST")
    fun envProfilesRoundTripThroughXml() {
        val element = Element("configuration")
        // a null entry is intentional here: writeEnvProfiles must tolerate it like a Java caller would
        val profilesWithNull = listOf("/envs/.local.env", "../eparts-tools/.ede.env", "", null) as List<String>
        LanesRunConfiguration.writeEnvProfiles(element, profilesWithNull)

        val read = LanesRunConfiguration.readEnvProfiles(element)

        assertEquals("blank entries must be dropped on write",
                     listOf("/envs/.local.env", "../eparts-tools/.ede.env"), read)
    }

    @Test
    fun envProfilesReadSkipsDuplicatesAndBlanks() {
        val element = Element("configuration")
        LanesRunConfiguration.writeEnvProfiles(element, listOf("/a/.env", "/a/.env", "  "))

        assertEquals(listOf("/a/.env"), LanesRunConfiguration.readEnvProfiles(element))
    }

    // --- parseReadyCondition (docker-compose-like readiness gate) ---------------------------

    @Test
    fun parsesPortReadyCondition() {
        val condition = RunConfigurationHelper.parseReadyCondition("port:3003")

        assertEquals(RunConfigurationHelper.ReadyCondition.Type.PORT, condition.type)
        assertEquals(3003, condition.port)
    }

    @Test
    fun parsesHttpAndLogReadyConditions() {
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.HTTP,
                     RunConfigurationHelper.parseReadyCondition("http://localhost:3003/health").type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.HTTP,
                     RunConfigurationHelper.parseReadyCondition("https://localhost/health").type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.LOG,
                     RunConfigurationHelper.parseReadyCondition("log:Server started").type)
        assertEquals("Server started", RunConfigurationHelper.parseReadyCondition("log:Server started").value)
        assertEquals("free text is the friendliest default: treat it as a log substring",
                     RunConfigurationHelper.ReadyCondition.Type.LOG,
                     RunConfigurationHelper.parseReadyCondition("Server started").type)
    }

    @Test
    fun blankOrInvalidReadyConditionMeansNone() {
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.NONE,
                     RunConfigurationHelper.parseReadyCondition(null).type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.NONE,
                     RunConfigurationHelper.parseReadyCondition("  ").type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.NONE,
                     RunConfigurationHelper.parseReadyCondition("port:abc").type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.NONE,
                     RunConfigurationHelper.parseReadyCondition("port:99999").type)
        assertEquals(RunConfigurationHelper.ReadyCondition.Type.NONE,
                     RunConfigurationHelper.parseReadyCondition("log:").type)
    }

    @Test
    fun portOpenReflectsARealListeningSocket() {
        ServerSocket(0).use { server ->
            assertTrue("a bound server socket must be detected as open",
                       RunConfigurationHelper.isPortOpen(server.localPort))
        }
    }

    @Test
    fun closedPortIsReportedAsNotOpen() {
        val freePort: Int
        ServerSocket(0).use { server ->
            freePort = server.localPort
        }

        assertFalse("a released port must be reported closed", RunConfigurationHelper.isPortOpen(freePort))
    }

    @Test
    fun envFileDisplayNameShowsTheFileNameOnly() {
        assertEquals(".local.env", RunConfigurationHelper.envFileDisplayName("/home/user/eparts-tools/.local.env"))
        assertEquals(".ede.env", RunConfigurationHelper.envFileDisplayName("relative/.ede.env"))
        assertEquals("-", RunConfigurationHelper.envFileDisplayName(""))
        assertEquals("-", RunConfigurationHelper.envFileDisplayName("   "))
        assertEquals("-", RunConfigurationHelper.envFileDisplayName(null))
    }
}
