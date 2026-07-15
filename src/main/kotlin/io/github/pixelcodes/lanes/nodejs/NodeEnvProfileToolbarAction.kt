package io.github.pixelcodes.lanes.nodejs

import javax.swing.JComponent

import com.intellij.execution.RunManager
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project

import io.github.pixelcodes.lanes.LanesIcons
import io.github.pixelcodes.lanes.RunConfigurationHelper

/**
 * Toolbar combo (see [io.github.pixelcodes.lanes.LanesEnvProfileToolbarAction] for the Lanes
 * group counterpart and why this extends [ComboBoxAction] instead of a plain button + popup)
 * that lets you pick which `.env` profile the *selected* Node-based run configuration runs with
 * next, without restarting an already-running instance. Rendered exactly like the run
 * configuration selector (icon, current profile name, dropdown arrow). Shown only once the
 * configuration has 2+ saved profiles.
 *
 * Part of the optional module (`META-INF/lanes-nodejs.xml`) - only loaded when the host IDE has
 * the JavaScript plugin.
 */
class NodeEnvProfileToolbarAction : ComboBoxAction() {

    init {
        templatePresentation.icon = LanesIcons.EnvVariables
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val config = selectedConfig(e)
        val settings = config?.let { NodeEnvFileSettings.of(it) }
        e.presentation.isEnabledAndVisible = (settings?.profiles?.size ?: 0) >= 2
        if (settings != null) {
            e.presentation.text = RunConfigurationHelper.envFileDisplayName(settings.active)
            e.presentation.description = "Pick which .env profile \"${config?.getName()}\" runs with next"
        }
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        val project = CommonDataKeys.PROJECT.getData(context) ?: return actionGroup
        val config = selectedConfig(project) ?: return actionGroup
        val settings = NodeEnvFileSettings.of(config)
        for (profile in settings.profiles) {
            actionGroup.add(ProfileAction(config, profile))
        }
        return actionGroup
    }

    private fun selectedConfig(e: AnActionEvent): AbstractNodeTargetRunProfile? = e.project?.let { selectedConfig(it) }

    private fun selectedConfig(project: Project): AbstractNodeTargetRunProfile? {
        val config = RunManager.getInstance(project).selectedConfiguration?.configuration as? AbstractNodeTargetRunProfile
            ?: return null
        // same gate as the editor field: only types whose launch actually loads the env file
        return if (NodeEnvFileSettings.isLaunchInjectionSupported(config)) config else null
    }

    private class ProfileAction(
        private val config: AbstractNodeTargetRunProfile,
        private val profile: String,
    ) : AnAction(RunConfigurationHelper.envFileDisplayName(profile)) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val active = NodeEnvFileSettings.of(config).active
            e.presentation.text = RunConfigurationHelper.envFileDisplayName(profile) + (if (profile == active) "  (active)" else "")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val settings = NodeEnvFileSettings.of(config)
            if (profile != settings.active) {
                NodeEnvFileSettings.store(config, settings.withActive(profile))
            }
        }
    }
}
