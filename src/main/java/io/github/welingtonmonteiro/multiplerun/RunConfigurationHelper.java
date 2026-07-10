package io.github.welingtonmonteiro.multiplerun;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.intellij.execution.CommonProgramRunConfigurationParameters;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

public class RunConfigurationHelper {

    private static final Logger LOG = Logger.getInstance(RunConfigurationHelper.class);

    /** This to avoid problems with one multirun configuration A contains multirun configuration B, which itself contains A. */
    public static boolean containsLoopies(MultirunRunConfiguration configuration, MultirunRunConfiguration target) {
        if (configuration.equals(target)) {
            return true;
        }
        for (RunConfiguration each : configuration.getRunConfigurations()) {
            if (each.equals(target)) {
                return true;
            }
            if (each instanceof MultirunRunConfiguration) {
                if (containsLoopies((MultirunRunConfiguration) each, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the given data overrides anything: has variables or disables passing system environment variables. */
    public static boolean isEnvOverrideActive(EnvironmentVariablesData envData) {
        return envData != null && !(envData.getEnvs().isEmpty() && envData.isPassParentEnvs());
    }

    /**
     * Adds a per-application memory (heap) cap to the environment override - the closest process-level
     * equivalent of Docker's mem_limit. Node.js processes honor NODE_OPTIONS --max-old-space-size and
     * JVM processes honor JAVA_TOOL_OPTIONS -Xmx; both are appended to any value already present, so
     * user-provided options are preserved. Swap/reservation limits have no per-process equivalent.
     */
    public static EnvironmentVariablesData withMemoryLimit(EnvironmentVariablesData envData, int limitMb) {
        final Map<String, String> merged = new LinkedHashMap<>(envData.getEnvs());
        merged.put("NODE_OPTIONS", appendOption(merged.get("NODE_OPTIONS"), "--max-old-space-size=" + limitMb));
        merged.put("JAVA_TOOL_OPTIONS", appendOption(merged.get("JAVA_TOOL_OPTIONS"), "-Xmx" + limitMb + "m"));
        return EnvironmentVariablesData.create(merged, envData.isPassParentEnvs());
    }

    private static String appendOption(String current, String option) {
        return current == null || current.trim().isEmpty() ? option : current + " " + option;
    }

    /**
     * Parses a dotenv-style file: KEY=VALUE lines; blank lines and "#" comment lines are skipped,
     * an optional "export " prefix is accepted and matching single/double quotes around values are stripped.
     */
    public static Map<String, String> parseEnvFile(File file) throws IOException {
        final Map<String, String> result = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }

    /**
     * A per-application readiness condition, docker-compose depends_on style. Syntax typed by
     * the user in the "Ready when" column:
     * <ul>
     *   <li>{@code port:3003} - a TCP port on localhost accepts connections</li>
     *   <li>{@code http://localhost:3003/health} - an HTTP GET answers with a 2xx/3xx status</li>
     *   <li>{@code log:Server started} - the console output contains the text (also the
     *       fallback for any other input)</li>
     * </ul>
     */
    public static final class ReadyCondition {
        public enum Type { NONE, PORT, LOG, HTTP }

        public static final ReadyCondition NONE = new ReadyCondition(Type.NONE, "", 0);

        public final Type type;
        public final String value;
        public final int port;

        private ReadyCondition(Type type, String value, int port) {
            this.type = type;
            this.value = value;
            this.port = port;
        }
    }

    /** Parses the "Ready when" text; blank or invalid input degrades gracefully (NONE / LOG). */
    public static ReadyCondition parseReadyCondition(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ReadyCondition.NONE;
        }
        final String text = raw.trim();
        final String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("port:")) {
            try {
                final int port = Integer.parseInt(text.substring("port:".length()).trim());
                if (port > 0 && port <= 65535) {
                    return new ReadyCondition(ReadyCondition.Type.PORT, text, port);
                }
            } catch (NumberFormatException ignored) {
                // fall through: not a valid port
            }
            return ReadyCondition.NONE;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new ReadyCondition(ReadyCondition.Type.HTTP, text, 0);
        }
        if (lower.startsWith("log:")) {
            final String needle = text.substring("log:".length());
            return needle.isEmpty() ? ReadyCondition.NONE
                                    : new ReadyCondition(ReadyCondition.Type.LOG, needle, 0);
        }
        // any other text is treated as a log substring - the friendliest default
        return new ReadyCondition(ReadyCondition.Type.LOG, text, 0);
    }

    /** true when a TCP port on localhost accepts connections. */
    public static boolean isPortOpen(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** true when an HTTP GET on the url answers with a 2xx/3xx status within a short timeout. */
    public static boolean isHttpHealthy(String url) {
        try {
            final java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(750);
            connection.setReadTimeout(750);
            connection.setRequestMethod("GET");
            final int code = connection.getResponseCode();
            connection.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * true when an exit code looks like a crash rather than an intentional stop: 0 is success,
     * 130/137/143 are SIGINT/SIGKILL/SIGTERM - what the IDE stop button, Stop Multiple Run and
     * the monitor kill actions produce. Anything else is treated as a crash.
     */
    public static boolean isCrashExit(int exitCode) {
        return exitCode != 0 && exitCode != 130 && exitCode != 137 && exitCode != 143;
    }

    /** Short display name of the active env profile (its file name), or "-" when none is set. */
    public static String envFileDisplayName(String envFilePath) {
        if (envFilePath == null || envFilePath.trim().isEmpty()) {
            return "-";
        }
        return new File(envFilePath.trim()).getName();
    }

    /** Resolves the configured env file path; relative paths are resolved against the project base directory. */
    public static File resolveEnvFile(String envFilePath, Project project) {
        final File file = new File(envFilePath.trim());
        if (file.isAbsolute() || project == null || project.getBasePath() == null) {
            return file;
        }
        return new File(project.getBasePath(), envFilePath.trim());
    }

    /**
     * Applies the env file (when configured) under the manually configured variables: file values are
     * the base and the table entries win on conflicts. Returns {@code envData} unchanged when no file is
     * configured; a missing or unreadable file is logged and skipped, so the run still starts.
     */
    public static EnvironmentVariablesData withEnvFile(EnvironmentVariablesData envData, String envFilePath, Project project) {
        if (envFilePath == null || envFilePath.trim().isEmpty()) {
            return envData;
        }
        final File file = resolveEnvFile(envFilePath, project);
        try {
            final Map<String, String> fileVars = parseEnvFile(file);
            // info level on purpose: key names only (never values), to diagnose injection issues from idea.log
            LOG.info("Multirun env file '" + file + "' loaded, keys=" + fileVars.keySet());
            if (fileVars.isEmpty()) {
                return envData;
            }
            final Map<String, String> merged = new LinkedHashMap<>(fileVars);
            merged.putAll(envData.getEnvs());
            return EnvironmentVariablesData.create(merged, envData.isPassParentEnvs());
        } catch (IOException e) {
            LOG.warn("Multirun: cannot read env file '" + file + "', continuing without it", e);
            return envData;
        }
    }

    /**
     * Applies a per-application env file ON TOP of the given data: the file values win over the
     * group environment for that application, because a per-app file is more specific than the
     * group. Returns {@code envData} unchanged when no file is configured; a missing or unreadable
     * file is logged and skipped, so the run still starts.
     */
    public static EnvironmentVariablesData withAppEnvFile(EnvironmentVariablesData envData, String envFilePath, Project project) {
        if (envFilePath == null || envFilePath.trim().isEmpty()) {
            return envData;
        }
        final File file = resolveEnvFile(envFilePath, project);
        try {
            final Map<String, String> fileVars = parseEnvFile(file);
            // info level on purpose: key names only (never values), to diagnose injection from idea.log
            LOG.info("Multirun per-app env file '" + file + "' loaded, keys=" + fileVars.keySet());
            if (fileVars.isEmpty()) {
                return envData;
            }
            final Map<String, String> merged = new LinkedHashMap<>(envData.getEnvs());
            merged.putAll(fileVars); // the per-app file wins over the group environment
            return EnvironmentVariablesData.create(merged, envData.isPassParentEnvs());
        } catch (IOException e) {
            LOG.warn("Multirun: cannot read per-app env file '" + file + "', continuing without it", e);
            return envData;
        }
    }

    /** File name (without directory) used when saving a child configuration console; safe across OSes. */
    public static String consoleLogFileName(String configurationName) {
        final String sanitized = configurationName == null
                ? "" : configurationName.trim().replaceAll("[^a-zA-Z0-9-_. ]", "_");
        return (sanitized.isEmpty() ? "configuration" : sanitized) + ".log";
    }

    /**
     * Enables the platform's "save console output to file" (same mechanism as the Logs tab of
     * individual run configurations) on the given, already cloned, configuration - writing to
     * {@code directory}/{@code <configuration name>}.log. Returns false (leaving the configuration
     * untouched) for types not based on RunConfigurationBase.
     */
    public static boolean applySaveOutput(RunConfiguration configuration, String directory, String configurationName) {
        if (!(configuration instanceof RunConfigurationBase)) {
            LOG.warn("Multirun save console for '" + configurationName
                             + "': configuration type does not support output files, skipping");
            return false;
        }
        final RunConfigurationBase<?> base = (RunConfigurationBase<?>) configuration;
        base.setSaveOutputToFile(true);
        base.setFileOutputPath(new File(directory, consoleLogFileName(configurationName)).getPath());
        return true;
    }

    /** Base variables first, then {@code override} wins on conflicts; the pass-parent-envs flag comes from {@code override}. */
    public static EnvironmentVariablesData mergeEnvData(EnvironmentVariablesData base, EnvironmentVariablesData override) {
        final Map<String, String> merged = new LinkedHashMap<>(base.getEnvs());
        merged.putAll(override.getEnvs());
        return EnvironmentVariablesData.create(merged, override.isPassParentEnvs());
    }

    /**
     * Returns a copy of the configuration with the Multirun environment variables applied on top of its own
     * (Multirun values win on conflicts). The original configuration is never modified. When the override is
     * not active, or the configuration type does not expose environment variables, the original instance is
     * returned unchanged.
     */
    public static RunConfiguration withEnvironmentOverride(RunConfiguration configuration, EnvironmentVariablesData override) {
        // info-level logs below carry key names only (never values); they exist to diagnose,
        // straight from idea.log, which injection strategy each child configuration took
        final String logPrefix = "Multirun env override for '" + configuration.getName()
                + "' (" + configuration.getClass().getSimpleName() + "): ";
        if (!isEnvOverrideActive(override)) {
            LOG.info(logPrefix + "inactive, running unchanged");
            return configuration;
        }

        if (configuration instanceof MultirunRunConfiguration) {
            // propagate to nested Multirun configurations; their own runner state applies it to their children
            final MultirunRunConfiguration clone = (MultirunRunConfiguration) configuration.clone();
            clone.setEnvData(mergeEnvData(clone.getEnvData(), override));
            LOG.info(logPrefix + "propagated to nested Multirun, keys=" + clone.getEnvData().getEnvs().keySet());
            return clone;
        }

        final RunConfiguration clone = configuration.clone();
        if (clone instanceof CommonProgramRunConfigurationParameters) {
            final CommonProgramRunConfigurationParameters params = (CommonProgramRunConfigurationParameters) clone;
            final Map<String, String> merged = new LinkedHashMap<>(params.getEnvs());
            merged.putAll(override.getEnvs());
            params.setEnvs(merged);
            params.setPassParentEnvs(override.isPassParentEnvs());
            LOG.info(logPrefix + "applied via CommonProgramRunConfigurationParameters, keys=" + merged.keySet());
            return clone;
        }

        // Some configuration types (e.g. Node.js in WebStorm) expose environment variables without
        // implementing CommonProgramRunConfigurationParameters - handle them reflectively.
        if (applyViaEnvData(clone, override)) {
            LOG.info(logPrefix + "applied via setEnvData reflection, keys=" + override.getEnvs().keySet());
            return clone;
        }
        if (applyViaEnvsMap(clone, override)) {
            LOG.info(logPrefix + "applied via setEnvs reflection, keys=" + override.getEnvs().keySet());
            return clone;
        }
        if (applyViaRunSettings(clone, override)) {
            LOG.info(logPrefix + "applied via run settings builder reflection, keys=" + override.getEnvs().keySet());
            return clone;
        }

        LOG.warn(logPrefix + "configuration type does not expose environment variables, running unchanged");
        return configuration;
    }

    /**
     * getRunSettings()/setRunSettings(...) convention where the settings object is immutable and
     * rebuilt through toBuilder()/build(), with an envData property (npm/pnpm/yarn run configurations).
     */
    private static boolean applyViaRunSettings(RunConfiguration configuration, EnvironmentVariablesData override) {
        try {
            final Object settings = configuration.getClass().getMethod("getRunSettings").invoke(configuration);
            if (settings == null) {
                return false;
            }
            final Object current = settings.getClass().getMethod("getEnvData").invoke(settings);
            final EnvironmentVariablesData base = current instanceof EnvironmentVariablesData
                    ? (EnvironmentVariablesData) current
                    : EnvironmentVariablesData.DEFAULT;

            final Object builder = settings.getClass().getMethod("toBuilder").invoke(settings);
            final Method envDataSetter = findSingleArgMethod(builder.getClass(), EnvironmentVariablesData.class,
                                                             "setEnvData", "envData");
            if (envDataSetter == null) {
                return false;
            }
            envDataSetter.invoke(builder, mergeEnvData(base, override));
            final Object newSettings = builder.getClass().getMethod("build").invoke(builder);

            final Method setter = findSingleArgMethod(configuration.getClass(), newSettings.getClass(), "setRunSettings");
            if (setter == null) {
                return false;
            }
            setter.invoke(configuration, newSettings);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** First public method with one of the given names taking exactly one parameter compatible with {@code argType}. */
    private static Method findSingleArgMethod(Class<?> owner, Class<?> argType, String... names) {
        for (String name : names) {
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(argType)) {
                    return method;
                }
            }
        }
        return null;
    }

    /** getEnvData()/setEnvData(EnvironmentVariablesData) convention (Node.js and other JS run configurations). */
    private static boolean applyViaEnvData(RunConfiguration configuration, EnvironmentVariablesData override) {
        try {
            final Method getter = configuration.getClass().getMethod("getEnvData");
            final Method setter = configuration.getClass().getMethod("setEnvData", EnvironmentVariablesData.class);
            final Object current = getter.invoke(configuration);
            final EnvironmentVariablesData base = current instanceof EnvironmentVariablesData
                    ? (EnvironmentVariablesData) current
                    : EnvironmentVariablesData.DEFAULT;
            setter.invoke(configuration, mergeEnvData(base, override));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** getEnvs()/setEnvs(Map) convention, with an optional setPassParentEnvs(boolean). */
    @SuppressWarnings("unchecked")
    private static boolean applyViaEnvsMap(RunConfiguration configuration, EnvironmentVariablesData override) {
        try {
            final Method getter = configuration.getClass().getMethod("getEnvs");
            final Method setter = configuration.getClass().getMethod("setEnvs", Map.class);
            final Object current = getter.invoke(configuration);
            final Map<String, String> merged = new LinkedHashMap<>();
            if (current instanceof Map) {
                merged.putAll((Map<String, String>) current);
            }
            merged.putAll(override.getEnvs());
            setter.invoke(configuration, merged);
            try {
                configuration.getClass().getMethod("setPassParentEnvs", boolean.class).invoke(configuration, override.isPassParentEnvs());
            } catch (Exception ignored) {
                // not every configuration type has the flag
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
