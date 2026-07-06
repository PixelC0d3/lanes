package com.khmelyuk.multirun;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.intellij.execution.configuration.EnvironmentVariablesData;

import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers the env file loading scenario: dotenv parsing, merge precedence (manual variables win
 * over the file) and the fallbacks for a missing/empty file.
 */
public class RunConfigurationHelperTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private File envFile(String content) throws IOException {
        // a dotfile, exactly like the real-world .env / .local.env files
        final File file = tempDir.newFile(".local.env");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // --- parseEnvFile ---------------------------------------------------------------------

    @Test
    public void parsesPlainKeyValueLines() throws IOException {
        final File file = envFile("DISTRIBUTION_CHANNEL=10\nALLOWED_DISTRIBUTION_CHANNEL=10\nENV_PREFIX=-com\n");

        final Map<String, String> vars = RunConfigurationHelper.parseEnvFile(file);

        assertEquals(3, vars.size());
        assertEquals("10", vars.get("DISTRIBUTION_CHANNEL"));
        assertEquals("10", vars.get("ALLOWED_DISTRIBUTION_CHANNEL"));
        assertEquals("-com", vars.get("ENV_PREFIX"));
    }

    @Test
    public void skipsBlankLinesAndComments() throws IOException {
        final File file = envFile("\n# comment line\n  \nKEY=value\n#OTHER=ignored\n");

        final Map<String, String> vars = RunConfigurationHelper.parseEnvFile(file);

        assertEquals(singletonMap("KEY", "value"), vars);
    }

    @Test
    public void acceptsExportPrefix() throws IOException {
        final File file = envFile("export KEY=value\n");

        assertEquals(singletonMap("KEY", "value"), RunConfigurationHelper.parseEnvFile(file));
    }

    @Test
    public void stripsMatchingQuotes() throws IOException {
        final File file = envFile("DOUBLE=\"quoted value\"\nSINGLE='single quoted'\nMIXED=\"not'stripped\n");

        final Map<String, String> vars = RunConfigurationHelper.parseEnvFile(file);

        assertEquals("quoted value", vars.get("DOUBLE"));
        assertEquals("single quoted", vars.get("SINGLE"));
        assertEquals("\"not'stripped", vars.get("MIXED"));
    }

    @Test
    public void keepsEqualsSignInsideValue() throws IOException {
        final File file = envFile("CONNECTION=host=localhost;port=5432\n");

        assertEquals("host=localhost;port=5432", RunConfigurationHelper.parseEnvFile(file).get("CONNECTION"));
    }

    @Test
    public void ignoresLinesWithoutKey() throws IOException {
        final File file = envFile("=value\nno-equals-line\nVALID=1\n");

        assertEquals(singletonMap("VALID", "1"), RunConfigurationHelper.parseEnvFile(file));
    }

    @Test
    public void trimsWhitespaceAroundKeyAndValue() throws IOException {
        final File file = envFile("  KEY  =  value  \n");

        assertEquals(singletonMap("KEY", "value"), RunConfigurationHelper.parseEnvFile(file));
    }

    // --- withEnvFile (merge precedence) ---------------------------------------------------

    @Test
    public void fileVariablesAreAppliedWhenNoManualOnesExist() throws IOException {
        final File file = envFile("ENV_PREFIX=-com\nDISTRIBUTION_CHANNEL=10\n");

        final EnvironmentVariablesData result =
                RunConfigurationHelper.withEnvFile(EnvironmentVariablesData.DEFAULT, file.getAbsolutePath(), null);

        assertEquals("-com", result.getEnvs().get("ENV_PREFIX"));
        assertEquals("10", result.getEnvs().get("DISTRIBUTION_CHANNEL"));
        assertTrue(result.isPassParentEnvs());
    }

    @Test
    public void manualVariablesWinOverTheFile() throws IOException {
        final File file = envFile("ENV_PREFIX=-com\nDISTRIBUTION_CHANNEL=10\n");
        final EnvironmentVariablesData manual =
                EnvironmentVariablesData.create(singletonMap("ENV_PREFIX", "-def"), true);

        final EnvironmentVariablesData result =
                RunConfigurationHelper.withEnvFile(manual, file.getAbsolutePath(), null);

        assertEquals("manual entry must win on conflicts", "-def", result.getEnvs().get("ENV_PREFIX"));
        assertEquals("file entry must fill the gaps", "10", result.getEnvs().get("DISTRIBUTION_CHANNEL"));
    }

    @Test
    public void passParentEnvsFlagIsPreserved() throws IOException {
        final File file = envFile("KEY=value\n");
        final EnvironmentVariablesData manual =
                EnvironmentVariablesData.create(java.util.Collections.emptyMap(), false);

        final EnvironmentVariablesData result =
                RunConfigurationHelper.withEnvFile(manual, file.getAbsolutePath(), null);

        assertFalse(result.isPassParentEnvs());
        assertEquals("value", result.getEnvs().get("KEY"));
    }

    @Test
    public void missingFileKeepsEnvDataUnchanged() {
        final EnvironmentVariablesData manual =
                EnvironmentVariablesData.create(singletonMap("KEY", "value"), true);

        final EnvironmentVariablesData result =
                RunConfigurationHelper.withEnvFile(manual, "/does/not/exist/.env", null);

        assertSame("the run must still start with the manual variables", manual, result);
    }

    @Test
    public void emptyOrNullPathKeepsEnvDataUnchanged() {
        final EnvironmentVariablesData manual =
                EnvironmentVariablesData.create(singletonMap("KEY", "value"), true);

        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, "", null));
        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, "   ", null));
        assertSame(manual, RunConfigurationHelper.withEnvFile(manual, null, null));
    }

    @Test
    public void envFileActivatesTheOverrideEvenWithoutManualVariables() throws IOException {
        final File file = envFile("KEY=value\n");

        final EnvironmentVariablesData result =
                RunConfigurationHelper.withEnvFile(EnvironmentVariablesData.DEFAULT, file.getAbsolutePath(), null);

        assertTrue("a loaded file must make the override active, otherwise nothing is injected",
                   RunConfigurationHelper.isEnvOverrideActive(result));
    }

    // --- resolveEnvFile -------------------------------------------------------------------

    @Test
    public void absolutePathIsUsedAsIs() {
        final File resolved = RunConfigurationHelper.resolveEnvFile("/tmp/some/.env", null);

        assertEquals(new File("/tmp/some/.env"), resolved);
    }

    // --- mergeEnvData ---------------------------------------------------------------------

    @Test
    public void mergeOverrideWinsOnConflicts() {
        final EnvironmentVariablesData base =
                EnvironmentVariablesData.create(singletonMap("ENV_PREFIX", "-def"), true);
        final EnvironmentVariablesData override =
                EnvironmentVariablesData.create(singletonMap("ENV_PREFIX", "-com"), false);

        final EnvironmentVariablesData merged = RunConfigurationHelper.mergeEnvData(base, override);

        assertEquals("-com", merged.getEnvs().get("ENV_PREFIX"));
        assertFalse("pass-parent-envs flag must come from the override", merged.isPassParentEnvs());
    }

    // --- withMemoryLimit (per-application memory cap) ---------------------------------------

    @Test
    public void memoryLimitSetsNodeAndJvmOptions() {
        final EnvironmentVariablesData result =
                RunConfigurationHelper.withMemoryLimit(EnvironmentVariablesData.DEFAULT, 1024);

        assertEquals("--max-old-space-size=1024", result.getEnvs().get("NODE_OPTIONS"));
        assertEquals("-Xmx1024m", result.getEnvs().get("JAVA_TOOL_OPTIONS"));
        assertTrue(result.isPassParentEnvs());
    }

    @Test
    public void memoryLimitAppendsToExistingOptions() {
        final EnvironmentVariablesData base =
                EnvironmentVariablesData.create(singletonMap("NODE_OPTIONS", "--enable-source-maps"), true);

        final EnvironmentVariablesData result = RunConfigurationHelper.withMemoryLimit(base, 512);

        assertEquals("user options must be preserved",
                     "--enable-source-maps --max-old-space-size=512", result.getEnvs().get("NODE_OPTIONS"));
    }

    @Test
    public void memoryLimitActivatesTheOverride() {
        final EnvironmentVariablesData result =
                RunConfigurationHelper.withMemoryLimit(EnvironmentVariablesData.DEFAULT, 256);

        assertTrue("a memory limit alone must trigger the injection",
                   RunConfigurationHelper.isEnvOverrideActive(result));
    }

    // --- consoleLogFileName (save console logs feature) ------------------------------------

    @Test
    public void consoleLogFileNameKeepsSafeNames() {
        assertEquals("eparts-api.log", RunConfigurationHelper.consoleLogFileName("eparts-api"));
        assertEquals("My App 2.log", RunConfigurationHelper.consoleLogFileName("My App 2"));
    }

    @Test
    public void consoleLogFileNameSanitizesUnsafeCharacters() {
        assertEquals("api_v2_ prod_.log", RunConfigurationHelper.consoleLogFileName("api/v2: prod*"));
    }

    @Test
    public void consoleLogFileNameFallsBackForBlankNames() {
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName(""));
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName("   "));
        assertEquals("configuration.log", RunConfigurationHelper.consoleLogFileName(null));
    }

    // --- parseDelay (locale tolerance) ----------------------------------------------------

    @Test
    public void delayAcceptsBothDecimalSeparators() {
        assertEquals(-1.0, MultirunRunConfiguration.parseDelay("-1,0"), 0.0001);
        assertEquals(-1.0, MultirunRunConfiguration.parseDelay("-1.0"), 0.0001);
        assertEquals(0.5, MultirunRunConfiguration.parseDelay("0,5"), 0.0001);
        assertEquals(0.5, MultirunRunConfiguration.parseDelay("0.5"), 0.0001);
    }

    @Test(expected = NumberFormatException.class)
    public void delayRejectsNonNumericInput() {
        MultirunRunConfiguration.parseDelay("abc");
    }

    // --- environment profiles (env file dropdown) ------------------------------------------

    @Test
    public void envProfilesRoundTripThroughXml() {
        final org.jdom.Element element = new org.jdom.Element("configuration");
        MultirunRunConfiguration.writeEnvProfiles(element,
                java.util.Arrays.asList("/envs/.local.env", "../eparts-tools/.ede.env", "", null));

        final java.util.List<String> read = MultirunRunConfiguration.readEnvProfiles(element);

        assertEquals("blank entries must be dropped on write",
                     java.util.Arrays.asList("/envs/.local.env", "../eparts-tools/.ede.env"), read);
    }

    @Test
    public void envProfilesReadSkipsDuplicatesAndBlanks() {
        final org.jdom.Element element = new org.jdom.Element("configuration");
        MultirunRunConfiguration.writeEnvProfiles(element,
                java.util.Arrays.asList("/a/.env", "/a/.env", "  "));

        assertEquals(java.util.Collections.singletonList("/a/.env"),
                     MultirunRunConfiguration.readEnvProfiles(element));
    }

    @Test
    public void envFileDisplayNameShowsTheFileNameOnly() {
        assertEquals(".local.env", RunConfigurationHelper.envFileDisplayName("/home/user/eparts-tools/.local.env"));
        assertEquals(".ede.env", RunConfigurationHelper.envFileDisplayName("relative/.ede.env"));
        assertEquals("-", RunConfigurationHelper.envFileDisplayName(""));
        assertEquals("-", RunConfigurationHelper.envFileDisplayName("   "));
        assertEquals("-", RunConfigurationHelper.envFileDisplayName(null));
    }
}
