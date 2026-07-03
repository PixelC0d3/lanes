package com.khmelyuk.multirun;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import com.intellij.execution.CommonProgramRunConfigurationParameters;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.diagnostic.Logger;

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
        if (!isEnvOverrideActive(override)) {
            return configuration;
        }

        if (configuration instanceof MultirunRunConfiguration) {
            // propagate to nested Multirun configurations; their own runner state applies it to their children
            final MultirunRunConfiguration clone = (MultirunRunConfiguration) configuration.clone();
            clone.setEnvData(mergeEnvData(clone.getEnvData(), override));
            return clone;
        }

        final RunConfiguration clone = configuration.clone();
        if (clone instanceof CommonProgramRunConfigurationParameters) {
            final CommonProgramRunConfigurationParameters params = (CommonProgramRunConfigurationParameters) clone;
            final Map<String, String> merged = new LinkedHashMap<>(params.getEnvs());
            merged.putAll(override.getEnvs());
            params.setEnvs(merged);
            params.setPassParentEnvs(override.isPassParentEnvs());
            return clone;
        }

        // Some configuration types (e.g. Node.js in WebStorm) expose environment variables without
        // implementing CommonProgramRunConfigurationParameters - handle them reflectively.
        if (applyViaEnvData(clone, override) || applyViaEnvsMap(clone, override)) {
            return clone;
        }

        LOG.debug("Multirun: configuration type does not expose environment variables, running unchanged: "
                          + configuration.getType().getDisplayName());
        return configuration;
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
