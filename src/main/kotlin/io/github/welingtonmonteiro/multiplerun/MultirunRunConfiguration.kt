package io.github.welingtonmonteiro.multiplerun

import java.util.LinkedHashMap
import java.util.LinkedHashSet

import org.jdom.Element

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.ConfigurationPerRunnerSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

import io.github.welingtonmonteiro.multiplerun.ui.MultirunRunConfigurationEditor

class MultirunRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<RunConfigurationOptions>(project, factory, name), RunnerSettings {

    private var delayTime: Double = 0.0
    private var reuseTabs: Boolean = true
    private var reuseTabsWithFailure: Boolean = false
    private var startOneByOne: Boolean = true
    private var markFailedProcess: Boolean = true
    private var hideSuccessProcess: Boolean = false
    private var restartRunning: Boolean = true

    /** docker "restart: on-failure": relaunch an app that exits with a crash code (max 3 tries). */
    private var restartOnCrash: Boolean = false

    /** Percent of the memory limit that triggers the alert/action (docker-like OOM watermark). */
    private var memAlertThreshold: Int = 90

    /** true = restart the app when it crosses the threshold; false = just notify. */
    private var memLimitRestart: Boolean = false

    /** Sustained CPU % that triggers an alert (0 = disabled); can exceed 100 on multi-core. */
    private var cpuAlertThreshold: Int = 0
    private var envFilePath: String = ""

    /** Known .env files (environment profiles); envFilePath holds the active one. */
    private var envProfiles: MutableList<String> = ArrayList()
    private var saveOutputDir: String = ""
    private var envData: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    /** Per-child memory (heap) cap in MB, keyed by configuration name; absent or <=0 means no limit. */
    private var memoryLimits: MutableMap<String, Int> = LinkedHashMap()

    /** Names of applications temporarily excluded from the run (unchecked in the list). */
    private var disabledApps: MutableSet<String> = LinkedHashSet()

    /** Per-child readiness condition ("port:3003", "log:started", http url), keyed by name. */
    private var readyConditions: MutableMap<String, String> = LinkedHashMap()

    /** Per-child env file overriding the group environment for that app, keyed by app name. */
    private var appEnvFiles: MutableMap<String, String> = LinkedHashMap()

    /** Named execution presets (which apps are On/Off + which env profile), keyed by preset name. */
    private var presets: MutableMap<String, Preset> = LinkedHashMap()
    private var runConfigurations: MutableList<RunConfigurationInternal> = ArrayList()

    /**
     * A named execution preset: a saved combination of which applications are enabled and which
     * environment profile is active, so a group can be flipped between scenarios ("backend only",
     * "full stack", ...) from a dropdown without re-checking boxes.
     */
    class Preset(
        @JvmField val name: String,
        disabledApps: Set<String>?,
        envFilePath: String?,
    ) {
        @JvmField val disabledApps: Set<String> =
            if (disabledApps == null) LinkedHashSet() else LinkedHashSet(disabledApps)

        @JvmField val envFilePath: String = envFilePath?.trim() ?: ""
    }

    fun getRunConfigurations(): List<RunConfiguration> {
        val result = ArrayList<RunConfiguration>()
        val allConfigurations = RunManager.getInstance(getProject()).allConfigurationsList
        for (runConfiguration in runConfigurations) {
            for (configuration in allConfigurations) {
                if (configuration.getName() == runConfiguration.name && typeMatches(configuration, runConfiguration)) {
                    if (configuration is MultirunRunConfiguration) {
                        if (configuration == this) {
                            // exclude itself
                            break
                        }
                        if (RunConfigurationHelper.containsLoopies(configuration, this)) {
                            // disallow adding multirun configuration that causes looping
                            break
                        }
                    }
                    result.add(configuration)
                    break
                }
            }
        }
        return result
    }

    fun setRunConfigurations(runConfigurations: List<RunConfiguration>?) {
        this.runConfigurations = ArrayList()
        if (runConfigurations == null) {
            return
        }

        for (configuration in runConfigurations) {
            this.runConfigurations.add(RunConfigurationInternal(configuration.getName(),
                                                                configuration.getType().getDisplayName(),
                                                                configuration.getType().getId()))
        }
    }

    fun isReuseTabs(): Boolean = reuseTabs

    fun setReuseTabs(reuseTabs: Boolean) {
        this.reuseTabs = reuseTabs
    }

    fun isReuseTabsWithFailure(): Boolean = reuseTabsWithFailure

    fun setReuseTabsWithFailure(reuseTabs: Boolean) {
        this.reuseTabsWithFailure = reuseTabs
    }

    fun isStartOneByOne(): Boolean = startOneByOne

    fun setStartOneByOne(startOneByOne: Boolean) {
        this.startOneByOne = startOneByOne
    }

    fun isMarkFailedProcess(): Boolean = markFailedProcess

    fun setMarkFailedProcess(markFailedProcess: Boolean) {
        this.markFailedProcess = markFailedProcess
    }

    fun isHideSuccessProcess(): Boolean = hideSuccessProcess

    fun setHideSuccessProcess(hideSuccessProcess: Boolean) {
        this.hideSuccessProcess = hideSuccessProcess
    }

    fun getDelayTime(): Double = delayTime

    fun setDelayTime(delayTime: Double) {
        this.delayTime = delayTime
    }

    fun isRestartRunning(): Boolean = restartRunning

    fun setRestartRunning(restartRunning: Boolean) {
        this.restartRunning = restartRunning
    }

    fun isRestartOnCrash(): Boolean = restartOnCrash

    fun setRestartOnCrash(restartOnCrash: Boolean) {
        this.restartOnCrash = restartOnCrash
    }

    fun getMemAlertThreshold(): Int = memAlertThreshold

    fun setMemAlertThreshold(memAlertThreshold: Int) {
        this.memAlertThreshold = clampMemAlertThreshold(memAlertThreshold)
    }

    fun isMemLimitRestart(): Boolean = memLimitRestart

    fun setMemLimitRestart(memLimitRestart: Boolean) {
        this.memLimitRestart = memLimitRestart
    }

    fun getCpuAlertThreshold(): Int = cpuAlertThreshold

    fun setCpuAlertThreshold(cpuAlertThreshold: Int) {
        this.cpuAlertThreshold = clampCpuAlertThreshold(cpuAlertThreshold)
    }

    fun getEnvFilePath(): String = envFilePath

    fun setEnvFilePath(envFilePath: String?) {
        this.envFilePath = envFilePath?.trim() ?: ""
    }

    fun getEnvProfiles(): List<String> = ArrayList(envProfiles)

    fun setEnvProfiles(profiles: List<String>?) {
        this.envProfiles = ArrayList()
        if (profiles != null) {
            for (profile in profiles) {
                val trimmed = profile?.trim()
                if (!trimmed.isNullOrEmpty() && !this.envProfiles.contains(trimmed)) {
                    this.envProfiles.add(trimmed)
                }
            }
        }
    }

    fun getMemoryLimits(): Map<String, Int> = LinkedHashMap(memoryLimits)

    fun setMemoryLimits(memoryLimits: Map<String, Int>?) {
        this.memoryLimits = LinkedHashMap()
        if (memoryLimits != null) {
            for ((key, value) in memoryLimits) {
                if (value > 0) {
                    this.memoryLimits[key] = value
                }
            }
        }
    }

    fun getDisabledApps(): Set<String> = LinkedHashSet(disabledApps)

    fun setDisabledApps(disabledApps: Set<String>?) {
        this.disabledApps = if (disabledApps == null) LinkedHashSet() else LinkedHashSet(disabledApps)
    }

    fun getReadyConditions(): Map<String, String> = LinkedHashMap(readyConditions)

    fun setReadyConditions(readyConditions: Map<String, String>?) {
        this.readyConditions = LinkedHashMap()
        if (readyConditions != null) {
            for ((key, value) in readyConditions) {
                if (value.isNotBlank()) {
                    this.readyConditions[key] = value.trim()
                }
            }
        }
    }

    fun getPresets(): Map<String, Preset> = LinkedHashMap(presets)

    fun setPresets(presets: Map<String, Preset>?) {
        this.presets = LinkedHashMap()
        if (presets != null) {
            for ((key, value) in presets) {
                if (key.isNotBlank()) {
                    this.presets[key] = value
                }
            }
        }
    }

    fun getAppEnvFiles(): Map<String, String> = LinkedHashMap(appEnvFiles)

    fun setAppEnvFiles(appEnvFiles: Map<String, String>?) {
        this.appEnvFiles = LinkedHashMap()
        if (appEnvFiles != null) {
            for ((key, value) in appEnvFiles) {
                if (value.isNotBlank()) {
                    this.appEnvFiles[key] = value.trim()
                }
            }
        }
    }

    fun getSaveOutputDir(): String = saveOutputDir

    fun setSaveOutputDir(saveOutputDir: String?) {
        this.saveOutputDir = saveOutputDir?.trim() ?: ""
    }

    fun getEnvData(): EnvironmentVariablesData = envData

    fun setEnvData(envData: EnvironmentVariablesData?) {
        this.envData = envData ?: EnvironmentVariablesData.DEFAULT
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return MultirunRunConfigurationEditor(getProject())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)

        if (element.getAttributeValue(PROP_SEPARATE_TABS) != null) {
            reuseTabs = !element.getAttributeValue(PROP_SEPARATE_TABS).toBoolean()
        }
        if (element.getAttributeValue(PROP_REUSE_TABS_WITH_FAILURE) != null) {
            reuseTabsWithFailure = element.getAttributeValue(PROP_REUSE_TABS_WITH_FAILURE).toBoolean()
        }
        if (element.getAttributeValue(PROP_START_ONE_BY_ONE) != null) {
            startOneByOne = element.getAttributeValue(PROP_START_ONE_BY_ONE).toBoolean()
        }
        if (element.getAttributeValue(PROP_MARK_FAILED_PROCESS) != null) {
            markFailedProcess = element.getAttributeValue(PROP_MARK_FAILED_PROCESS).toBoolean()
        }
        if (element.getAttributeValue(PROP_HIDE_SUCCESS_PROCESS) != null) {
            hideSuccessProcess = element.getAttributeValue(PROP_HIDE_SUCCESS_PROCESS).toBoolean()
        }
        if (element.getAttributeValue(PROP_RESTART_RUNNING) != null) {
            restartRunning = element.getAttributeValue(PROP_RESTART_RUNNING).toBoolean()
        }
        if (element.getAttributeValue(PROP_RESTART_ON_CRASH) != null) {
            restartOnCrash = element.getAttributeValue(PROP_RESTART_ON_CRASH).toBoolean()
        }
        if (element.getAttributeValue(PROP_MEM_ALERT_THRESHOLD) != null) {
            try {
                setMemAlertThreshold(element.getAttributeValue(PROP_MEM_ALERT_THRESHOLD).toInt())
            } catch (ignored: NumberFormatException) {
                // keep the default
            }
        }
        if (element.getAttributeValue(PROP_MEM_LIMIT_RESTART) != null) {
            memLimitRestart = element.getAttributeValue(PROP_MEM_LIMIT_RESTART).toBoolean()
        }
        if (element.getAttributeValue(PROP_CPU_ALERT_THRESHOLD) != null) {
            try {
                setCpuAlertThreshold(element.getAttributeValue(PROP_CPU_ALERT_THRESHOLD).toInt())
            } catch (ignored: NumberFormatException) {
                // keep the default (disabled)
            }
        }
        if (element.getAttributeValue(PROP_ENV_FILE) != null) {
            setEnvFilePath(element.getAttributeValue(PROP_ENV_FILE))
        }
        envProfiles = readEnvProfiles(element).toMutableList()
        // the active file is always part of the profile list
        if (envFilePath.isNotEmpty() && !envProfiles.contains(envFilePath)) {
            envProfiles.add(envFilePath)
        }
        presets = readPresets(element).toMutableMap()
        if (element.getAttributeValue(PROP_SAVE_OUTPUT_DIR) != null) {
            setSaveOutputDir(element.getAttributeValue(PROP_SAVE_OUTPUT_DIR))
        }
        if (element.getAttributeValue(PROP_DELAY_TIME) != null) {
            delayTime = parseDelay(element.getAttributeValue(PROP_DELAY_TIME))
        }

        for (each in element.getContent()) {
            if (each !is Element) {
                continue
            }
            if (each.getName() == "runConfiguration") {
                runConfigurations.add(RunConfigurationInternal(each.getAttributeValue("name"),
                                                               each.getAttributeValue("type"),
                                                               each.getAttributeValue("typeId")))
                val memLimit = each.getAttributeValue(PROP_MEM_LIMIT_MB)
                if (memLimit != null) {
                    try {
                        memoryLimits[each.getAttributeValue("name")] = memLimit.toInt()
                    } catch (ignored: NumberFormatException) {
                        // a malformed limit simply means no limit
                    }
                }
                if (each.getAttributeValue(PROP_DISABLED).toBoolean()) {
                    disabledApps.add(each.getAttributeValue("name"))
                }
                val readyWhen = each.getAttributeValue(PROP_READY_WHEN)
                if (!readyWhen.isNullOrBlank()) {
                    readyConditions[each.getAttributeValue("name")] = readyWhen.trim()
                }
                val appEnvFile = each.getAttributeValue(PROP_APP_ENV_FILE)
                if (!appEnvFile.isNullOrBlank()) {
                    appEnvFiles[each.getAttributeValue("name")] = appEnvFile.trim()
                }
            } else if (each.getName() == ELEMENT_ENVS) {
                val envs = LinkedHashMap<String, String>()
                for (env in each.getChildren(ELEMENT_ENV)) {
                    val name = env.getAttributeValue("name")
                    if (name != null) {
                        envs[name] = env.getAttributeValue("value", "")
                    }
                }
                val passParentEnvs = "false" != each.getAttributeValue(PROP_PASS_PARENT_ENVS)
                envData = EnvironmentVariablesData.create(envs, passParentEnvs)
            }
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)

        element.setAttribute(PROP_SEPARATE_TABS, (!reuseTabs).toString())
        element.setAttribute(PROP_REUSE_TABS_WITH_FAILURE, reuseTabsWithFailure.toString())
        element.setAttribute(PROP_START_ONE_BY_ONE, startOneByOne.toString())
        element.setAttribute(PROP_MARK_FAILED_PROCESS, markFailedProcess.toString())
        element.setAttribute(PROP_HIDE_SUCCESS_PROCESS, hideSuccessProcess.toString())
        element.setAttribute(PROP_RESTART_RUNNING, restartRunning.toString())
        element.setAttribute(PROP_RESTART_ON_CRASH, restartOnCrash.toString())
        element.setAttribute(PROP_MEM_ALERT_THRESHOLD, memAlertThreshold.toString())
        element.setAttribute(PROP_MEM_LIMIT_RESTART, memLimitRestart.toString())
        element.setAttribute(PROP_CPU_ALERT_THRESHOLD, cpuAlertThreshold.toString())
        element.setAttribute(PROP_DELAY_TIME, delayTime.toString())
        if (envFilePath.isNotEmpty()) {
            element.setAttribute(PROP_ENV_FILE, envFilePath)
        }
        if (saveOutputDir.isNotEmpty()) {
            element.setAttribute(PROP_SAVE_OUTPUT_DIR, saveOutputDir)
        }

        val configurations = ArrayList<Element>()
        for (each in runConfigurations) {
            val runConfiguration = Element("runConfiguration")
            runConfiguration.setAttribute("name", each.name)
            if (each.type != null) {
                runConfiguration.setAttribute("type", each.type)
            }
            if (each.typeId != null) {
                runConfiguration.setAttribute("typeId", each.typeId)
            }
            val memLimit = memoryLimits[each.name]
            if (memLimit != null && memLimit > 0) {
                runConfiguration.setAttribute(PROP_MEM_LIMIT_MB, memLimit.toString())
            }
            if (disabledApps.contains(each.name)) {
                runConfiguration.setAttribute(PROP_DISABLED, "true")
            }
            val readyWhen = readyConditions[each.name]
            if (!readyWhen.isNullOrEmpty()) {
                runConfiguration.setAttribute(PROP_READY_WHEN, readyWhen)
            }
            val appEnvFile = appEnvFiles[each.name]
            if (!appEnvFile.isNullOrEmpty()) {
                runConfiguration.setAttribute(PROP_APP_ENV_FILE, appEnvFile)
            }
            configurations.add(runConfiguration)
        }
        element.setContent(configurations)

        if (RunConfigurationHelper.isEnvOverrideActive(envData)) {
            val envsElement = Element(ELEMENT_ENVS)
            envsElement.setAttribute(PROP_PASS_PARENT_ENVS, envData.isPassParentEnvs().toString())
            for ((key, value) in envData.getEnvs()) {
                val env = Element(ELEMENT_ENV)
                env.setAttribute("name", key)
                env.setAttribute("value", value)
                envsElement.addContent(env)
            }
            element.addContent(envsElement)
        }

        writeEnvProfiles(element, envProfiles)
        writePresets(element, presets)
    }

    override fun createRunnerSettings(configurationInfoProvider: ConfigurationInfoProvider?): ConfigurationPerRunnerSettings? {
        return null
    }

    override fun getRunnerSettingsEditor(programRunner: ProgramRunner<*>?): SettingsEditor<ConfigurationPerRunnerSettings>? {
        return null
    }

    override fun getState(executor: Executor, executionEnvironment: ExecutionEnvironment): RunProfileState {
        // unchecked (disabled) applications stay in the configuration but are not launched
        val enabled = ArrayList<RunConfiguration>()
        for (each in getRunConfigurations()) {
            if (!disabledApps.contains(each.getName())) {
                enabled.add(each)
            }
        }
        return MultirunRunnerState(enabled, startOneByOne, delayTime,
                                   reuseTabs, reuseTabsWithFailure,
                                   markFailedProcess, hideSuccessProcess, envData, envFilePath,
                                   saveOutputDir, getMemoryLimits(), getReadyConditions(),
                                   getAppEnvFiles(),
                                   restartRunning, restartOnCrash, memAlertThreshold, memLimitRestart,
                                   cpuAlertThreshold,
                                   getProject(), getName())
    }

    /**
     * A runner state that launches only `apps` (a subset of this group), reusing all of the
     * group's current settings and environment but **without** stopping the rest of the group
     * (`restartRunning` and `startOneByOne` are forced off). Used by the Multiple Run
     * Monitor to relaunch a single application - e.g. after switching its environment - while
     * keeping it tracked, grouped and shown with its live environment exactly like a normal launch.
     */
    fun createStateForApps(apps: List<RunConfiguration>): MultirunRunnerState {
        return MultirunRunnerState(apps, false, delayTime,
                                   reuseTabs, reuseTabsWithFailure,
                                   markFailedProcess, hideSuccessProcess, envData, envFilePath,
                                   saveOutputDir, getMemoryLimits(), getReadyConditions(),
                                   getAppEnvFiles(),
                                   false, restartOnCrash, memAlertThreshold, memLimitRestart,
                                   cpuAlertThreshold,
                                   getProject(), getName())
    }

    override fun checkConfiguration() {
        if (runConfigurations.isEmpty()) {
            throw RuntimeConfigurationError("No run configuration chosen")
        }
        var anyEnabled = false
        for (each in runConfigurations) {
            if (!disabledApps.contains(each.name)) {
                anyEnabled = true
                break
            }
        }
        if (!anyEnabled) {
            throw RuntimeConfigurationError("All run configurations are disabled - enable at least one")
        }
        if (envFilePath.isNotEmpty()) {
            val envFile = RunConfigurationHelper.resolveEnvFile(envFilePath, getProject())
            if (!envFile.isFile()) {
                // warning, not error: the run is still allowed, the file is simply skipped
                throw RuntimeConfigurationWarning("Environment file not found: $envFile")
            }
        }
    }

    private class RunConfigurationInternal(
        val name: String,
        /** Configuration type display name - legacy reference, kept for downgrade compatibility. */
        val type: String?,
        /** Configuration type id - unique and stable, preferred for matching. */
        val typeId: String?,
    )

    companion object {
        const val PROP_SEPARATE_TABS = "separateTabs"
        const val PROP_REUSE_TABS_WITH_FAILURE = "reuseTabsWithFailures"
        const val PROP_START_ONE_BY_ONE = "startOneByOne"
        const val PROP_MARK_FAILED_PROCESS = "markFailedProcess"
        const val PROP_HIDE_SUCCESS_PROCESS = "hideSuccessProcess"
        const val PROP_DELAY_TIME = "delayTime"
        const val PROP_RESTART_RUNNING = "restartRunning"
        const val PROP_ENV_FILE = "envFile"
        const val PROP_SAVE_OUTPUT_DIR = "saveOutputDir"
        const val ELEMENT_ENVS = "envs"
        const val ELEMENT_ENV = "env"
        const val PROP_PASS_PARENT_ENVS = "passParentEnvs"
        const val PROP_MEM_LIMIT_MB = "memLimitMb"
        const val ELEMENT_ENV_PROFILE = "envProfile"
        const val ELEMENT_PRESET = "preset"
        const val ELEMENT_PRESET_DISABLED = "disabled"
        const val PROP_DISABLED = "disabled"
        const val PROP_READY_WHEN = "readyWhen"
        const val PROP_APP_ENV_FILE = "appEnvFile"
        const val PROP_RESTART_ON_CRASH = "restartOnCrash"
        const val PROP_MEM_ALERT_THRESHOLD = "memAlertThreshold"
        const val PROP_MEM_LIMIT_RESTART = "memLimitRestart"
        const val PROP_CPU_ALERT_THRESHOLD = "cpuAlertThreshold"

        private fun typeMatches(configuration: RunConfiguration, saved: RunConfigurationInternal): Boolean {
            if (saved.typeId != null) {
                return saved.typeId == configuration.getType().getId()
            }
            // entries saved by older versions reference the type by its display name, which is
            // not unique, may change between releases and is translated by language packs;
            // they are migrated to the type id on the next save. The id comparison also covers
            // display names that were later rebranded (e.g. "Multirun" -> "Multiple Run", where
            // the id is still "Multirun").
            return configuration.getType().getDisplayName() == saved.type
                || configuration.getType().getId() == saved.type
        }

        /**
         * Parses a delay value accepting both '.' and ',' as the decimal separator.
         * Locale-aware NumberFormat cannot be used here: in comma-decimal locales (German, pt-BR)
         * its lenient parsing treats '.' as a grouping separator and turns "-1.0" into -10, which
         * corrupts values persisted by writeExternal (always dot-formatted) and hand-typed input.
         */
        @JvmStatic
        fun parseDelay(text: String): Double {
            return text.trim().replace(',', '.').toDouble()
        }

        /** Memory alert threshold is a percentage of the limit: clamped to 1..100. */
        @JvmStatic
        fun clampMemAlertThreshold(value: Int): Int = value.coerceIn(1, 100)

        /** CPU alert: 0 disables it; the upper bound is generous because multi-core CPU % can exceed 100. */
        @JvmStatic
        fun clampCpuAlertThreshold(value: Int): Int = value.coerceIn(0, 1000)

        /** Reads the environment profile list persisted as `<envProfile path="..."/>` children. */
        @JvmStatic
        fun readEnvProfiles(element: Element): List<String> {
            val profiles = ArrayList<String>()
            for (child in element.getChildren(ELEMENT_ENV_PROFILE)) {
                val path = child.getAttributeValue("path")
                val trimmed = path?.trim()
                if (!trimmed.isNullOrEmpty() && !profiles.contains(trimmed)) {
                    profiles.add(trimmed)
                }
            }
            return profiles
        }

        /** Persists the environment profile list as `<envProfile path="..."/>` children. */
        @JvmStatic
        fun writeEnvProfiles(element: Element, profiles: List<String>) {
            for (profile in profiles) {
                val trimmed = profile?.trim()
                if (!trimmed.isNullOrEmpty()) {
                    val child = Element(ELEMENT_ENV_PROFILE)
                    child.setAttribute("path", trimmed)
                    element.addContent(child)
                }
            }
        }

        /** Reads the presets persisted as `<preset name=".." envFile=".."><disabled name=".."/></preset>`. */
        @JvmStatic
        fun readPresets(element: Element): Map<String, Preset> {
            val result = LinkedHashMap<String, Preset>()
            for (child in element.getChildren(ELEMENT_PRESET)) {
                val name = child.getAttributeValue("name")
                if (name.isNullOrBlank()) {
                    continue
                }
                val envFile = child.getAttributeValue("envFile", "")
                val disabled = LinkedHashSet<String>()
                for (disabledChild in child.getChildren(ELEMENT_PRESET_DISABLED)) {
                    val appName = disabledChild.getAttributeValue("name")
                    if (!appName.isNullOrEmpty()) {
                        disabled.add(appName)
                    }
                }
                result[name] = Preset(name, disabled, envFile)
            }
            return result
        }

        /** Persists the presets as `<preset name=".." envFile=".."><disabled name=".."/></preset>` children. */
        @JvmStatic
        fun writePresets(element: Element, presets: Map<String, Preset>) {
            for (preset in presets.values) {
                if (preset.name.isBlank()) {
                    continue
                }
                val presetElement = Element(ELEMENT_PRESET)
                presetElement.setAttribute("name", preset.name)
                if (preset.envFilePath.isNotEmpty()) {
                    presetElement.setAttribute("envFile", preset.envFilePath)
                }
                for (appName in preset.disabledApps) {
                    val disabledChild = Element(ELEMENT_PRESET_DISABLED)
                    disabledChild.setAttribute("name", appName)
                    presetElement.addContent(disabledChild)
                }
                element.addContent(presetElement)
            }
        }
    }
}
