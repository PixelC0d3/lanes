package com.khmelyuk.multirun;

import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.khmelyuk.multirun.ui.MultirunRunConfigurationEditor;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MultirunRunConfiguration extends RunConfigurationBase implements RunnerSettings {

    public static final String PROP_SEPARATE_TABS = "separateTabs";
    public static final String PROP_REUSE_TABS_WITH_FAILURE = "reuseTabsWithFailures";
    public static final String PROP_START_ONE_BY_ONE = "startOneByOne";
    public static final String PROP_MARK_FAILED_PROCESS = "markFailedProcess";
    public static final String PROP_HIDE_SUCCESS_PROCESS = "hideSuccessProcess";
    public static final String PROP_DELAY_TIME = "delayTime";
    public static final String PROP_RESTART_RUNNING = "restartRunning";
    public static final String PROP_ENV_FILE = "envFile";
    public static final String PROP_SAVE_OUTPUT_DIR = "saveOutputDir";
    public static final String ELEMENT_ENVS = "envs";
    public static final String ELEMENT_ENV = "env";
    public static final String PROP_PASS_PARENT_ENVS = "passParentEnvs";
    public static final String PROP_MEM_LIMIT_MB = "memLimitMb";
    public static final String ELEMENT_ENV_PROFILE = "envProfile";

    private double delayTime = 0;
    private boolean reuseTabs = true;
    private boolean reuseTabsWithFailure = false;
    private boolean startOneByOne = true;
    private boolean markFailedProcess = true;
    private boolean hideSuccessProcess = false;
    private boolean restartRunning = true;
    private String envFilePath = "";
    /** Known .env files (environment profiles); envFilePath holds the active one. */
    private List<String> envProfiles = new ArrayList<>();
    private String saveOutputDir = "";
    private EnvironmentVariablesData envData = EnvironmentVariablesData.DEFAULT;
    /** Per-child memory (heap) cap in MB, keyed by configuration name; absent or <=0 means no limit. */
    private Map<String, Integer> memoryLimits = new LinkedHashMap<>();
    private List<RunConfigurationInternal> runConfigurations = new ArrayList<>();

    public MultirunRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    public List<RunConfiguration> getRunConfigurations() {
        final List<RunConfiguration> result = new ArrayList<>();
        final List<RunConfiguration> allConfigurations = RunManager.getInstance(getProject()).getAllConfigurationsList();
        for (RunConfigurationInternal runConfiguration : runConfigurations) {
            for (RunConfiguration configuration : allConfigurations) {
                if (configuration.getName().equals(runConfiguration.name) && typeMatches(configuration, runConfiguration)) {
                    if (configuration instanceof MultirunRunConfiguration) {
                        if (configuration.equals(this)) {
                            // exclude itself
                            break;
                        }
                        if (RunConfigurationHelper.containsLoopies((MultirunRunConfiguration) configuration, this)) {
                            // disallow adding multirun configuration that causes looping
                            break;
                        }
                    }
                    result.add(configuration);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean typeMatches(RunConfiguration configuration, RunConfigurationInternal saved) {
        if (saved.typeId != null) {
            return saved.typeId.equals(configuration.getType().getId());
        }
        // entries saved by older versions reference the type by its display name, which is
        // not unique, may change between releases and is translated by language packs;
        // they are migrated to the type id on the next save. The id comparison also covers
        // display names that were later rebranded (e.g. "Multirun" -> "Multiple Run", where
        // the id is still "Multirun").
        return configuration.getType().getDisplayName().equals(saved.type)
                || configuration.getType().getId().equals(saved.type);
    }

    public void setRunConfigurations(List<RunConfiguration> runConfigurations) {
        this.runConfigurations = new ArrayList<>();
        if (runConfigurations == null) {
            return;
        }

        for (RunConfiguration configuration : runConfigurations) {
            this.runConfigurations.add(new RunConfigurationInternal(configuration.getName(),
                                                                    configuration.getType().getDisplayName(),
                                                                    configuration.getType().getId()));
        }
    }

    public boolean isReuseTabs() {
        return reuseTabs;
    }

    public void setReuseTabs(boolean reuseTabs) {
        this.reuseTabs = reuseTabs;
    }

    public boolean isReuseTabsWithFailure() {
        return reuseTabsWithFailure;
    }

    public void setReuseTabsWithFailure(boolean reuseTabs) {
        this.reuseTabsWithFailure = reuseTabs;
    }

    public boolean isStartOneByOne() {
        return startOneByOne;
    }

    public void setStartOneByOne(boolean startOneByOne) {
        this.startOneByOne = startOneByOne;
    }

    public boolean isMarkFailedProcess() {
        return markFailedProcess;
    }

    public void setMarkFailedProcess(boolean markFailedProcess) {
        this.markFailedProcess = markFailedProcess;
    }

    public boolean isHideSuccessProcess() {
        return hideSuccessProcess;
    }

    public void setHideSuccessProcess(boolean hideSuccessProcess) {
        this.hideSuccessProcess = hideSuccessProcess;
    }

    public double getDelayTime() {
        return delayTime;
    }

    public void setDelayTime(double delayTime) {
        this.delayTime = delayTime;
    }

    /**
     * Parses a delay value accepting both '.' and ',' as the decimal separator.
     * Locale-aware NumberFormat cannot be used here: in comma-decimal locales (German, pt-BR)
     * its lenient parsing treats '.' as a grouping separator and turns "-1.0" into -10, which
     * corrupts values persisted by writeExternal (always dot-formatted) and hand-typed input.
     */
    public static double parseDelay(String text) throws NumberFormatException {
        return Double.parseDouble(text.trim().replace(',', '.'));
    }

    public boolean isRestartRunning() {
        return restartRunning;
    }

    public void setRestartRunning(boolean restartRunning) {
        this.restartRunning = restartRunning;
    }

    public String getEnvFilePath() {
        return envFilePath;
    }

    public void setEnvFilePath(String envFilePath) {
        this.envFilePath = envFilePath == null ? "" : envFilePath.trim();
    }

    public List<String> getEnvProfiles() {
        return new ArrayList<>(envProfiles);
    }

    public void setEnvProfiles(List<String> profiles) {
        this.envProfiles = new ArrayList<>();
        if (profiles != null) {
            for (String profile : profiles) {
                if (profile != null && !profile.trim().isEmpty() && !this.envProfiles.contains(profile.trim())) {
                    this.envProfiles.add(profile.trim());
                }
            }
        }
    }

    /** Reads the environment profile list persisted as {@code <envProfile path="..."/>} children. */
    public static List<String> readEnvProfiles(Element element) {
        final List<String> profiles = new ArrayList<>();
        for (Element child : element.getChildren(ELEMENT_ENV_PROFILE)) {
            final String path = child.getAttributeValue("path");
            if (path != null && !path.trim().isEmpty() && !profiles.contains(path.trim())) {
                profiles.add(path.trim());
            }
        }
        return profiles;
    }

    /** Persists the environment profile list as {@code <envProfile path="..."/>} children. */
    public static void writeEnvProfiles(Element element, List<String> profiles) {
        for (String profile : profiles) {
            if (profile != null && !profile.trim().isEmpty()) {
                final Element child = new Element(ELEMENT_ENV_PROFILE);
                child.setAttribute("path", profile.trim());
                element.addContent(child);
            }
        }
    }

    public Map<String, Integer> getMemoryLimits() {
        return new LinkedHashMap<>(memoryLimits);
    }

    public void setMemoryLimits(Map<String, Integer> memoryLimits) {
        this.memoryLimits = new LinkedHashMap<>();
        if (memoryLimits != null) {
            for (Map.Entry<String, Integer> each : memoryLimits.entrySet()) {
                if (each.getValue() != null && each.getValue() > 0) {
                    this.memoryLimits.put(each.getKey(), each.getValue());
                }
            }
        }
    }

    public String getSaveOutputDir() {
        return saveOutputDir;
    }

    public void setSaveOutputDir(String saveOutputDir) {
        this.saveOutputDir = saveOutputDir == null ? "" : saveOutputDir.trim();
    }

    public EnvironmentVariablesData getEnvData() {
        return envData;
    }

    public void setEnvData(EnvironmentVariablesData envData) {
        this.envData = envData == null ? EnvironmentVariablesData.DEFAULT : envData;
    }

    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new MultirunRunConfigurationEditor(getProject());
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);

        if (element.getAttributeValue(PROP_SEPARATE_TABS) != null) {
            reuseTabs = !Boolean.parseBoolean(element.getAttributeValue(PROP_SEPARATE_TABS));
        }
        if (element.getAttributeValue(PROP_REUSE_TABS_WITH_FAILURE) != null) {
            reuseTabsWithFailure = Boolean.parseBoolean(element.getAttributeValue(PROP_REUSE_TABS_WITH_FAILURE));
        }
        if (element.getAttributeValue(PROP_START_ONE_BY_ONE) != null) {
            startOneByOne = Boolean.parseBoolean(element.getAttributeValue(PROP_START_ONE_BY_ONE));
        }
        if (element.getAttributeValue(PROP_MARK_FAILED_PROCESS) != null) {
            markFailedProcess = Boolean.parseBoolean(element.getAttributeValue(PROP_MARK_FAILED_PROCESS));
        }
        if (element.getAttributeValue(PROP_HIDE_SUCCESS_PROCESS) != null) {
            hideSuccessProcess = Boolean.parseBoolean(element.getAttributeValue(PROP_HIDE_SUCCESS_PROCESS));
        }
        if (element.getAttributeValue(PROP_RESTART_RUNNING) != null) {
            restartRunning = Boolean.parseBoolean(element.getAttributeValue(PROP_RESTART_RUNNING));
        }
        if (element.getAttributeValue(PROP_ENV_FILE) != null) {
            setEnvFilePath(element.getAttributeValue(PROP_ENV_FILE));
        }
        envProfiles = readEnvProfiles(element);
        // the active file is always part of the profile list
        if (!envFilePath.isEmpty() && !envProfiles.contains(envFilePath)) {
            envProfiles.add(envFilePath);
        }
        if (element.getAttributeValue(PROP_SAVE_OUTPUT_DIR) != null) {
            setSaveOutputDir(element.getAttributeValue(PROP_SAVE_OUTPUT_DIR));
        }
        if (element.getAttributeValue(PROP_DELAY_TIME) != null) {
            delayTime = parseDelay(element.getAttributeValue(PROP_DELAY_TIME));
        }

        for (Object each : element.getContent()) {
            if (!(each instanceof Element)) {
                continue;
            }
            final Element eachElement = (Element) each;
            if (eachElement.getName().equals("runConfiguration")) {
                runConfigurations.add(new RunConfigurationInternal(eachElement.getAttributeValue("name"),
                                                                   eachElement.getAttributeValue("type"),
                                                                   eachElement.getAttributeValue("typeId")));
                final String memLimit = eachElement.getAttributeValue(PROP_MEM_LIMIT_MB);
                if (memLimit != null) {
                    try {
                        memoryLimits.put(eachElement.getAttributeValue("name"), Integer.parseInt(memLimit));
                    } catch (NumberFormatException ignored) {
                        // a malformed limit simply means no limit
                    }
                }
            } else if (eachElement.getName().equals(ELEMENT_ENVS)) {
                final Map<String, String> envs = new LinkedHashMap<>();
                for (Element env : eachElement.getChildren(ELEMENT_ENV)) {
                    final String name = env.getAttributeValue("name");
                    if (name != null) {
                        envs.put(name, env.getAttributeValue("value", ""));
                    }
                }
                final boolean passParentEnvs = !"false".equals(eachElement.getAttributeValue(PROP_PASS_PARENT_ENVS));
                envData = EnvironmentVariablesData.create(envs, passParentEnvs);
            }
        }
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        super.writeExternal(element);

        element.setAttribute(PROP_SEPARATE_TABS, String.valueOf(!reuseTabs));
        element.setAttribute(PROP_REUSE_TABS_WITH_FAILURE, String.valueOf(reuseTabsWithFailure));
        element.setAttribute(PROP_START_ONE_BY_ONE, String.valueOf(startOneByOne));
        element.setAttribute(PROP_MARK_FAILED_PROCESS, String.valueOf(markFailedProcess));
        element.setAttribute(PROP_HIDE_SUCCESS_PROCESS, String.valueOf(hideSuccessProcess));
        element.setAttribute(PROP_RESTART_RUNNING, String.valueOf(restartRunning));
        element.setAttribute(PROP_DELAY_TIME, String.valueOf(delayTime));
        if (!envFilePath.isEmpty()) {
            element.setAttribute(PROP_ENV_FILE, envFilePath);
        }
        if (!saveOutputDir.isEmpty()) {
            element.setAttribute(PROP_SAVE_OUTPUT_DIR, saveOutputDir);
        }

        final List<Element> configurations = new ArrayList<Element>();
        for (RunConfigurationInternal each : runConfigurations) {
            Element runConfiguration = new Element("runConfiguration");
            runConfiguration.setAttribute("name", each.name);
            if (each.type != null) {
                runConfiguration.setAttribute("type", each.type);
            }
            if (each.typeId != null) {
                runConfiguration.setAttribute("typeId", each.typeId);
            }
            final Integer memLimit = memoryLimits.get(each.name);
            if (memLimit != null && memLimit > 0) {
                runConfiguration.setAttribute(PROP_MEM_LIMIT_MB, String.valueOf(memLimit));
            }
            configurations.add(runConfiguration);
        }
        element.setContent(configurations);

        if (RunConfigurationHelper.isEnvOverrideActive(envData)) {
            final Element envsElement = new Element(ELEMENT_ENVS);
            envsElement.setAttribute(PROP_PASS_PARENT_ENVS, String.valueOf(envData.isPassParentEnvs()));
            for (Map.Entry<String, String> entry : envData.getEnvs().entrySet()) {
                final Element env = new Element(ELEMENT_ENV);
                env.setAttribute("name", entry.getKey());
                env.setAttribute("value", entry.getValue());
                envsElement.addContent(env);
            }
            element.addContent(envsElement);
        }

        writeEnvProfiles(element, envProfiles);
    }

    @Nullable
    @Override
    public ConfigurationPerRunnerSettings createRunnerSettings(ConfigurationInfoProvider configurationInfoProvider) {
        return null;
    }

    @Nullable
    @Override
    public SettingsEditor<ConfigurationPerRunnerSettings> getRunnerSettingsEditor(ProgramRunner programRunner) {
        return null;
    }

    @Nullable
    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) {
        return new MultirunRunnerState(getRunConfigurations(), startOneByOne, delayTime,
                                       reuseTabs, reuseTabsWithFailure,
                                       markFailedProcess, hideSuccessProcess, envData, envFilePath,
                                       saveOutputDir, getMemoryLimits(), restartRunning, getProject(), getName());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (runConfigurations.isEmpty()) {
            throw new RuntimeConfigurationError("No run configuration chosen");
        }
        if (!envFilePath.isEmpty()) {
            final java.io.File envFile = RunConfigurationHelper.resolveEnvFile(envFilePath, getProject());
            if (!envFile.isFile()) {
                // warning, not error: the run is still allowed, the file is simply skipped
                throw new RuntimeConfigurationWarning("Environment file not found: " + envFile);
            }
        }
    }

    private static class RunConfigurationInternal {
        String name;
        /** Configuration type display name - legacy reference, kept for downgrade compatibility. */
        String type;
        /** Configuration type id - unique and stable, preferred for matching. */
        String typeId;

        RunConfigurationInternal(String name, String type, String typeId) {
            this.name = name;
            this.type = type;
            this.typeId = typeId;
        }
    }
}
