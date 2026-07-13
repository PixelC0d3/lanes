package io.github.pixelcodes.lanes.nodejs

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.project.Project

import io.github.pixelcodes.lanes.EnvProfileExecutionTarget

/**
 * Offers one [EnvProfileExecutionTarget] per environment profile saved on a Node-based run
 * configuration (Node.js, npm/pnpm/yarn, Karma, Jest, Mocha, ...), so the run widget shows a
 * picker before Play/Debug once at least two profiles are configured. See
 * [EnvProfileExecutionTarget] for how the choice reaches the actual launch (read in
 * [LanesNodeEnvFileExtension.EnvFileLaunchSession.addNodeOptionsTo]).
 *
 * Part of the optional module (`META-INF/lanes-nodejs.xml`) - only loaded when the host IDE has
 * the JavaScript plugin.
 */
class NodeEnvExecutionTargetProvider : ExecutionTargetProvider() {

    override fun getTargets(project: Project, configuration: RunConfiguration): List<ExecutionTarget> {
        if (configuration !is AbstractNodeTargetRunProfile) {
            return emptyList()
        }
        val settings = NodeEnvFileSettings.of(configuration)
        if (settings.profiles.size < 2) {
            return emptyList()
        }
        val typeId = configuration.getType().getId()
        val name = configuration.getName()
        return settings.profiles.map { profile ->
            EnvProfileExecutionTarget(profile, name, typeId, profile == settings.active) { cfg ->
                if (cfg is RunConfigurationBase<*>) NodeEnvFileSettings.of(cfg).profiles else emptyList()
            }
        }
    }
}
