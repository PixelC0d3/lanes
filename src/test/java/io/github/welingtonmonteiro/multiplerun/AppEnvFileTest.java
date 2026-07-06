package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.intellij.execution.configuration.EnvironmentVariablesData;

import org.junit.Test;

public class AppEnvFileTest {

    @Test
    public void perAppEnvFileWinsOverTheGroupEnvironment() throws IOException {
        final File envFile = File.createTempFile("app", ".env");
        Files.write(envFile.toPath(), "SHARED=app\nAPP_ONLY=x\n".getBytes(StandardCharsets.UTF_8));

        final Map<String, String> group = new LinkedHashMap<>();
        group.put("SHARED", "group");
        group.put("GROUP_ONLY", "g");
        final EnvironmentVariablesData groupData = EnvironmentVariablesData.create(group, true);

        // null project is fine: the temp file path is absolute, so no project base dir is needed
        final EnvironmentVariablesData result =
                RunConfigurationHelper.withAppEnvFile(groupData, envFile.getPath(), null);

        assertEquals("app", result.getEnvs().get("SHARED"));      // per-app file wins
        assertEquals("x", result.getEnvs().get("APP_ONLY"));      // added by the app file
        assertEquals("g", result.getEnvs().get("GROUP_ONLY"));    // group-only entry preserved
        // the pass-parent-envs flag comes from the group data
        org.junit.Assert.assertTrue(result.isPassParentEnvs());

        //noinspection ResultOfMethodCallIgnored
        envFile.delete();
    }

    @Test
    public void blankPathReturnsTheSameDataUnchanged() {
        final EnvironmentVariablesData data =
                EnvironmentVariablesData.create(java.util.Collections.singletonMap("A", "1"), false);
        assertSame(data, RunConfigurationHelper.withAppEnvFile(data, "  ", null));
        assertSame(data, RunConfigurationHelper.withAppEnvFile(data, null, null));
    }

    @Test
    public void missingFileIsSkippedNotFatal() {
        final EnvironmentVariablesData data =
                EnvironmentVariablesData.create(java.util.Collections.singletonMap("A", "1"), true);
        final EnvironmentVariablesData result =
                RunConfigurationHelper.withAppEnvFile(data, "/no/such/file-xyz.env", null);
        // unreadable file -> original environment kept, run still proceeds
        assertEquals("1", result.getEnvs().get("A"));
    }
}
