package io.github.pixelcodes.lanes

import javax.swing.JComponent

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project

/**
 * Toolbar combo (next to the run configuration selector, both classic and new UI - see
 * `ToolbarRunGroup`/`MainToolbarRight` in plugin.xml, same anchoring `RunConfigurationsComboBoxAction`
 * itself uses) that lets you pick which `.env` profile the *selected* Lanes group runs with next,
 * without opening the Lanes Monitor. Rendered exactly like the run configuration selector (icon,
 * current profile name, dropdown arrow) since it extends the same [ComboBoxAction] base. Shown
 * only once the group has 2+ saved profiles.
 *
 * A pre-Play picker was first attempted via `ExecutionTargetProvider` (the mechanism Flutter/
 * Android use for their device picker), but that widget only renders once a *non-default* target
 * is already active - there is no visible way to pick one for the first time, so it never showed
 * up in practice. This combo button has no such hidden gate.
 */
class LanesEnvProfileToolbarAction : ComboBoxAction() {

    init {
        templatePresentation.icon = LanesIcons.EnvVariables
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val group = selectedGroup(e)
        val profiles = group?.getEnvProfiles() ?: emptyList()
        e.presentation.isEnabledAndVisible = profiles.size >= 2
        if (group != null) {
            e.presentation.text = RunConfigurationHelper.envFileDisplayName(group.getEnvFilePath())
            e.presentation.description = "Pick which .env profile \"${group.getName()}\" runs with next"
        }
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        val project = CommonDataKeys.PROJECT.getData(context) ?: return actionGroup
        val group = selectedGroup(project) ?: return actionGroup
        for (profile in group.getEnvProfiles()) {
            actionGroup.add(ProfileAction(group, profile))
        }
        return actionGroup
    }

    private fun selectedGroup(e: AnActionEvent): LanesRunConfiguration? = e.project?.let { selectedGroup(it) }

    private fun selectedGroup(project: Project): LanesRunConfiguration? =
        RunManager.getInstance(project).selectedConfiguration?.configuration as? LanesRunConfiguration

    private class ProfileAction(
        private val group: LanesRunConfiguration,
        private val profile: String,
    ) : AnAction(RunConfigurationHelper.envFileDisplayName(profile)) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text =
                RunConfigurationHelper.envFileDisplayName(profile) + (if (profile == group.getEnvFilePath()) "  (active)" else "")
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (profile != group.getEnvFilePath()) {
                group.setEnvFilePath(profile)
            }
        }
    }
}
