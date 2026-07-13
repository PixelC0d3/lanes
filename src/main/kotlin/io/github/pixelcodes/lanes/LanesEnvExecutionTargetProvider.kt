package io.github.pixelcodes.lanes

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

/**
 * Offers one [EnvProfileExecutionTarget] per environment profile saved on a Lanes group
 * configuration, so the run widget shows a picker before Play/Debug once at least two profiles
 * are configured. See [EnvProfileExecutionTarget] for how the choice reaches the actual launch
 * (read in [LanesRunConfiguration.getState]).
 */
class LanesEnvExecutionTargetProvider : ExecutionTargetProvider() {

    override fun getTargets(project: Project, configuration: RunConfiguration): List<ExecutionTarget> {
        if (configuration !is LanesRunConfiguration) {
            return emptyList()
        }
        val profiles = configuration.getEnvProfiles()
        if (profiles.size < 2) {
            return emptyList()
        }
        val typeId = configuration.getType().getId()
        val name = configuration.getName()
        val active = configuration.getEnvFilePath()
        return profiles.map { profile ->
            EnvProfileExecutionTarget(profile, name, typeId, profile == active) { cfg ->
                (cfg as? LanesRunConfiguration)?.getEnvProfiles() ?: emptyList()
            }
        }
    }
}
