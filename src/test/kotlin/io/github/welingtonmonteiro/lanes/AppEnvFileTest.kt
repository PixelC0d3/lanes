package io.github.welingtonmonteiro.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.intellij.execution.configuration.EnvironmentVariablesData

class AppEnvFileTest {

    @Test
    fun perAppEnvFileWinsOverTheGroupEnvironment() {
        val envFile = File.createTempFile("app", ".env")
        Files.write(envFile.toPath(), "SHARED=app\nAPP_ONLY=x\n".toByteArray(StandardCharsets.UTF_8))

        val group = LinkedHashMap<String, String>()
        group["SHARED"] = "group"
        group["GROUP_ONLY"] = "g"
        val groupData = EnvironmentVariablesData.create(group, true)

        // null project is fine: the temp file path is absolute, so no project base dir is needed
        val result = RunConfigurationHelper.withAppEnvFile(groupData, envFile.path, null)

        assertEquals("app", result.getEnvs()["SHARED"])      // per-app file wins
        assertEquals("x", result.getEnvs()["APP_ONLY"])      // added by the app file
        assertEquals("g", result.getEnvs()["GROUP_ONLY"])    // group-only entry preserved
        // the pass-parent-envs flag comes from the group data
        assertTrue(result.isPassParentEnvs())

        envFile.delete()
    }

    @Test
    fun blankPathReturnsTheSameDataUnchanged() {
        val data = EnvironmentVariablesData.create(mapOf("A" to "1"), false)
        assertSame(data, RunConfigurationHelper.withAppEnvFile(data, "  ", null))
        assertSame(data, RunConfigurationHelper.withAppEnvFile(data, null, null))
    }

    @Test
    fun missingFileIsSkippedNotFatal() {
        val data = EnvironmentVariablesData.create(mapOf("A" to "1"), true)
        val result = RunConfigurationHelper.withAppEnvFile(data, "/no/such/file-xyz.env", null)
        // unreadable file -> original environment kept, run still proceeds
        assertEquals("1", result.getEnvs()["A"])
    }
}
