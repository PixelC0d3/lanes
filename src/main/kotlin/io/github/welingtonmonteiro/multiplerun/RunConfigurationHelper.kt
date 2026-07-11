package io.github.welingtonmonteiro.multiplerun

import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class RunConfigurationHelper private constructor() {

    /**
     * A per-application readiness condition, docker-compose depends_on style. Syntax typed by the
     * user in the "Ready when" column:
     * - `port:3003` - a TCP port on localhost accepts connections
     * - `http://localhost:3003/health` - an HTTP GET answers with a 2xx/3xx status
     * - `log:Server started` - the console output contains the text (also the fallback for any
     *   other input)
     */
    class ReadyCondition internal constructor(
        @JvmField val type: Type,
        @JvmField val value: String,
        @JvmField val port: Int,
    ) {
        enum class Type { NONE, PORT, LOG, HTTP }

        companion object {
            @JvmField
            val NONE = ReadyCondition(Type.NONE, "", 0)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RunConfigurationHelper::class.java)

        /** This to avoid problems with one multirun configuration A contains multirun configuration B, which itself contains A. */
        @JvmStatic
        fun containsLoopies(configuration: MultirunRunConfiguration, target: MultirunRunConfiguration): Boolean {
            if (configuration == target) {
                return true
            }
            for (each in configuration.getRunConfigurations()) {
                if (each == target) {
                    return true
                }
                if (each is MultirunRunConfiguration && containsLoopies(each, target)) {
                    return true
                }
            }
            return false
        }

        /** Whether the given data overrides anything: has variables or disables passing system environment variables. */
        @JvmStatic
        fun isEnvOverrideActive(envData: EnvironmentVariablesData?): Boolean {
            return envData != null && !(envData.getEnvs().isEmpty() && envData.isPassParentEnvs())
        }

        /**
         * Adds a per-application memory (heap) cap to the environment override - the closest process-level
         * equivalent of Docker's mem_limit. Node.js processes honor NODE_OPTIONS --max-old-space-size and
         * JVM processes honor JAVA_TOOL_OPTIONS -Xmx; both are appended to any value already present, so
         * user-provided options are preserved. Swap/reservation limits have no per-process equivalent.
         */
        @JvmStatic
        fun withMemoryLimit(envData: EnvironmentVariablesData, limitMb: Int): EnvironmentVariablesData {
            val merged = LinkedHashMap(envData.getEnvs())
            merged["NODE_OPTIONS"] = appendOption(merged["NODE_OPTIONS"], "--max-old-space-size=$limitMb")
            merged["JAVA_TOOL_OPTIONS"] = appendOption(merged["JAVA_TOOL_OPTIONS"], "-Xmx${limitMb}m")
            return EnvironmentVariablesData.create(merged, envData.isPassParentEnvs())
        }

        private fun appendOption(current: String?, option: String): String {
            return if (current.isNullOrBlank()) option else "$current $option"
        }

        /**
         * Parses a dotenv-style file: KEY=VALUE lines; blank lines and "#" comment lines are skipped,
         * an optional "export " prefix is accepted and matching single/double quotes around values are stripped.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun parseEnvFile(file: File): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (rawLine in Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                var line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    continue
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length).trim()
                }
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    continue
                }
                val key = line.substring(0, eq).trim()
                var value = line.substring(eq + 1).trim()
                if (value.length >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length - 1)
                }
                result[key] = value
            }
            return result
        }

        /** Parses the "Ready when" text; blank or invalid input degrades gracefully (NONE / LOG). */
        @JvmStatic
        fun parseReadyCondition(raw: String?): ReadyCondition {
            if (raw.isNullOrBlank()) {
                return ReadyCondition.NONE
            }
            val text = raw.trim()
            val lower = text.lowercase(Locale.ROOT)
            if (lower.startsWith("port:")) {
                try {
                    val port = text.substring("port:".length).trim().toInt()
                    if (port in 1..65535) {
                        return ReadyCondition(ReadyCondition.Type.PORT, text, port)
                    }
                } catch (ignored: NumberFormatException) {
                    // fall through: not a valid port
                }
                return ReadyCondition.NONE
            }
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return ReadyCondition(ReadyCondition.Type.HTTP, text, 0)
            }
            if (lower.startsWith("log:")) {
                val needle = text.substring("log:".length)
                return if (needle.isEmpty()) ReadyCondition.NONE
                       else ReadyCondition(ReadyCondition.Type.LOG, needle, 0)
            }
            // any other text is treated as a log substring - the friendliest default
            return ReadyCondition(ReadyCondition.Type.LOG, text, 0)
        }

        /** true when a TCP port on localhost accepts connections. */
        @JvmStatic
        fun isPortOpen(port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    true
                }
            } catch (e: IOException) {
                false
            }
        }

        /** true when an HTTP GET on the url answers with a 2xx/3xx status within a short timeout. */
        @JvmStatic
        fun isHttpHealthy(url: String): Boolean {
            return try {
                val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                connection.setConnectTimeout(750)
                connection.setReadTimeout(750)
                connection.setRequestMethod("GET")
                val code = connection.getResponseCode()
                connection.disconnect()
                code in 200..399
            } catch (e: Exception) {
                false
            }
        }

        /**
         * true when an exit code looks like a crash rather than an intentional stop: 0 is success,
         * 130/137/143 are SIGINT/SIGKILL/SIGTERM - what the IDE stop button, Stop Multiple Run and
         * the monitor kill actions produce. Anything else is treated as a crash.
         */
        @JvmStatic
        fun isCrashExit(exitCode: Int): Boolean {
            return exitCode != 0 && exitCode != 130 && exitCode != 137 && exitCode != 143
        }

        /** Short display name of the active env profile (its file name), or "-" when none is set. */
        @JvmStatic
        fun envFileDisplayName(envFilePath: String?): String {
            if (envFilePath.isNullOrBlank()) {
                return "-"
            }
            return File(envFilePath.trim()).getName()
        }

        /** Resolves the configured env file path; relative paths are resolved against the project base directory. */
        @JvmStatic
        fun resolveEnvFile(envFilePath: String, project: Project?): File {
            val file = File(envFilePath.trim())
            val basePath = project?.getBasePath()
            if (file.isAbsolute() || project == null || basePath == null) {
                return file
            }
            return File(basePath, envFilePath.trim())
        }

        /**
         * Applies the env file (when configured) under the manually configured variables: file values are
         * the base and the table entries win on conflicts. Returns `envData` unchanged when no file is
         * configured; a missing or unreadable file is logged and skipped, so the run still starts.
         */
        @JvmStatic
        fun withEnvFile(envData: EnvironmentVariablesData, envFilePath: String?, project: Project?): EnvironmentVariablesData {
            if (envFilePath.isNullOrBlank()) {
                return envData
            }
            val file = resolveEnvFile(envFilePath, project)
            return try {
                val fileVars = parseEnvFile(file)
                // info level on purpose: key names only (never values), to diagnose injection issues from idea.log
                LOG.info("Multirun env file '$file' loaded, keys=${fileVars.keys}")
                if (fileVars.isEmpty()) {
                    return envData
                }
                val merged = LinkedHashMap(fileVars)
                merged.putAll(envData.getEnvs())
                EnvironmentVariablesData.create(merged, envData.isPassParentEnvs())
            } catch (e: IOException) {
                LOG.warn("Multirun: cannot read env file '$file', continuing without it", e)
                envData
            }
        }

        /**
         * Applies a per-application env file ON TOP of the given data: the file values win over the
         * group environment for that application, because a per-app file is more specific than the
         * group. Returns `envData` unchanged when no file is configured; a missing or unreadable
         * file is logged and skipped, so the run still starts.
         */
        @JvmStatic
        fun withAppEnvFile(envData: EnvironmentVariablesData, envFilePath: String?, project: Project?): EnvironmentVariablesData {
            if (envFilePath.isNullOrBlank()) {
                return envData
            }
            val file = resolveEnvFile(envFilePath, project)
            return try {
                val fileVars = parseEnvFile(file)
                // info level on purpose: key names only (never values), to diagnose injection from idea.log
                LOG.info("Multirun per-app env file '$file' loaded, keys=${fileVars.keys}")
                if (fileVars.isEmpty()) {
                    return envData
                }
                val merged = LinkedHashMap(envData.getEnvs())
                merged.putAll(fileVars) // the per-app file wins over the group environment
                EnvironmentVariablesData.create(merged, envData.isPassParentEnvs())
            } catch (e: IOException) {
                LOG.warn("Multirun: cannot read per-app env file '$file', continuing without it", e)
                envData
            }
        }

        /** File name (without directory) used when saving a child configuration console; safe across OSes. */
        @JvmStatic
        fun consoleLogFileName(configurationName: String?): String {
            val sanitized = (configurationName ?: "").trim().replace(Regex("[^a-zA-Z0-9-_. ]"), "_")
            return (if (sanitized.isEmpty()) "configuration" else sanitized) + ".log"
        }

        /**
         * Enables the platform's "save console output to file" (same mechanism as the Logs tab of
         * individual run configurations) on the given, already cloned, configuration - writing to
         * `directory`/`<configuration name>`.log. Returns false (leaving the configuration
         * untouched) for types not based on RunConfigurationBase.
         */
        @JvmStatic
        fun applySaveOutput(configuration: RunConfiguration, directory: String, configurationName: String): Boolean {
            if (configuration !is RunConfigurationBase<*>) {
                LOG.warn("Multirun save console for '$configurationName': configuration type does not support output files, skipping")
                return false
            }
            configuration.setSaveOutputToFile(true)
            configuration.setFileOutputPath(File(directory, consoleLogFileName(configurationName)).getPath())
            return true
        }

        /** Base variables first, then `override` wins on conflicts; the pass-parent-envs flag comes from `override`. */
        @JvmStatic
        fun mergeEnvData(base: EnvironmentVariablesData, override: EnvironmentVariablesData): EnvironmentVariablesData {
            val merged = LinkedHashMap(base.getEnvs())
            merged.putAll(override.getEnvs())
            return EnvironmentVariablesData.create(merged, override.isPassParentEnvs())
        }

        /**
         * Returns a copy of the configuration with the Multirun environment variables applied on top of its own
         * (Multirun values win on conflicts). The original configuration is never modified. When the override is
         * not active, or the configuration type does not expose environment variables, the original instance is
         * returned unchanged.
         */
        @JvmStatic
        fun withEnvironmentOverride(configuration: RunConfiguration, override: EnvironmentVariablesData): RunConfiguration {
            // info-level logs below carry key names only (never values); they exist to diagnose,
            // straight from idea.log, which injection strategy each child configuration took
            val logPrefix = "Multirun env override for '${configuration.getName()}' (${configuration.javaClass.simpleName}): "
            if (!isEnvOverrideActive(override)) {
                LOG.info(logPrefix + "inactive, running unchanged")
                return configuration
            }

            if (configuration is MultirunRunConfiguration) {
                // propagate to nested Multirun configurations; their own runner state applies it to their children
                val clone = configuration.clone() as MultirunRunConfiguration
                clone.setEnvData(mergeEnvData(clone.getEnvData(), override))
                LOG.info(logPrefix + "propagated to nested Multirun, keys=" + clone.getEnvData().getEnvs().keys)
                return clone
            }

            val clone = configuration.clone()
            if (clone is CommonProgramRunConfigurationParameters) {
                val merged = LinkedHashMap(clone.getEnvs())
                merged.putAll(override.getEnvs())
                clone.setEnvs(merged)
                clone.setPassParentEnvs(override.isPassParentEnvs())
                LOG.info(logPrefix + "applied via CommonProgramRunConfigurationParameters, keys=" + merged.keys)
                return clone
            }

            // Some configuration types (e.g. Node.js in WebStorm) expose environment variables without
            // implementing CommonProgramRunConfigurationParameters - handle them reflectively.
            if (applyViaEnvData(clone, override)) {
                LOG.info(logPrefix + "applied via setEnvData reflection, keys=" + override.getEnvs().keys)
                return clone
            }
            if (applyViaEnvsMap(clone, override)) {
                LOG.info(logPrefix + "applied via setEnvs reflection, keys=" + override.getEnvs().keys)
                return clone
            }
            if (applyViaRunSettings(clone, override)) {
                LOG.info(logPrefix + "applied via run settings builder reflection, keys=" + override.getEnvs().keys)
                return clone
            }

            LOG.warn(logPrefix + "configuration type does not expose environment variables, running unchanged")
            return configuration
        }

        /**
         * getRunSettings()/setRunSettings(...) convention where the settings object is immutable and
         * rebuilt through toBuilder()/build(), with an envData property (npm/pnpm/yarn run configurations).
         */
        private fun applyViaRunSettings(configuration: RunConfiguration, override: EnvironmentVariablesData): Boolean {
            return try {
                val settings = configuration.javaClass.getMethod("getRunSettings").invoke(configuration) ?: return false
                val current = settings.javaClass.getMethod("getEnvData").invoke(settings)
                val base = if (current is EnvironmentVariablesData) current else EnvironmentVariablesData.DEFAULT

                val builder = settings.javaClass.getMethod("toBuilder").invoke(settings)
                val envDataSetter = findSingleArgMethod(builder.javaClass, EnvironmentVariablesData::class.java, "setEnvData", "envData")
                    ?: return false
                envDataSetter.invoke(builder, mergeEnvData(base, override))
                val newSettings = builder.javaClass.getMethod("build").invoke(builder)

                val setter = findSingleArgMethod(configuration.javaClass, newSettings.javaClass, "setRunSettings") ?: return false
                setter.invoke(configuration, newSettings)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** First public method with one of the given names taking exactly one parameter compatible with `argType`. */
        private fun findSingleArgMethod(owner: Class<*>, argType: Class<*>, vararg names: String): Method? {
            for (name in names) {
                for (method in owner.methods) {
                    if (method.name == name
                        && method.parameterCount == 1
                        && method.parameterTypes[0].isAssignableFrom(argType)) {
                        return method
                    }
                }
            }
            return null
        }

        /** getEnvData()/setEnvData(EnvironmentVariablesData) convention (Node.js and other JS run configurations). */
        private fun applyViaEnvData(configuration: RunConfiguration, override: EnvironmentVariablesData): Boolean {
            return try {
                val getter = configuration.javaClass.getMethod("getEnvData")
                val setter = configuration.javaClass.getMethod("setEnvData", EnvironmentVariablesData::class.java)
                val current = getter.invoke(configuration)
                val base = if (current is EnvironmentVariablesData) current else EnvironmentVariablesData.DEFAULT
                setter.invoke(configuration, mergeEnvData(base, override))
                true
            } catch (e: Exception) {
                false
            }
        }

        /** getEnvs()/setEnvs(Map) convention, with an optional setPassParentEnvs(boolean). */
        @Suppress("UNCHECKED_CAST")
        private fun applyViaEnvsMap(configuration: RunConfiguration, override: EnvironmentVariablesData): Boolean {
            return try {
                val getter = configuration.javaClass.getMethod("getEnvs")
                val setter = configuration.javaClass.getMethod("setEnvs", Map::class.java)
                val current = getter.invoke(configuration)
                val merged = LinkedHashMap<String, String>()
                if (current is Map<*, *>) {
                    merged.putAll(current as Map<String, String>)
                }
                merged.putAll(override.getEnvs())
                setter.invoke(configuration, merged)
                try {
                    configuration.javaClass.getMethod("setPassParentEnvs", java.lang.Boolean.TYPE)
                        .invoke(configuration, override.isPassParentEnvs())
                } catch (ignored: Exception) {
                    // not every configuration type has the flag
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
