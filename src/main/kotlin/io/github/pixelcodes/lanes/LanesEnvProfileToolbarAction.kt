package io.github.pixelcodes.lanes

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

/**
 * Toolbar button (next to the run configuration selector, both classic and new UI - see
 * `ToolbarRunGroup`/`MainToolbarRight` in plugin.xml) that lets you pick which `.env` profile the
 * *selected* Lanes group runs with next, without opening the Lanes Monitor. Shown only once the
 * group has 2+ saved profiles.
 *
 * A pre-Play picker was first attempted via `ExecutionTargetProvider` (the mechanism Flutter/
 * Android use for their device picker), but that widget only renders once a *non-default* target
 * is already active - there is no visible way to pick one for the first time, so it never showed
 * up in practice. This plain toolbar button + popup has no such hidden gate.
 */
class LanesEnvProfileToolbarAction : AnAction(
    "Pick Env Profile", "Choose which .env profile the selected Lanes group runs with next", LanesIcons.EnvVariables,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val group = selectedGroup(e)
        val profiles = group?.getEnvProfiles() ?: emptyList()
        e.presentation.isEnabledAndVisible = profiles.size >= 2
        if (group != null) {
            e.presentation.description = "Env: ${RunConfigurationHelper.envFileDisplayName(group.getEnvFilePath())} - click to change"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val group = selectedGroup(e) ?: return
        val profiles = group.getEnvProfiles()
        if (profiles.size < 2) {
            return
        }
        val step = object : BaseListPopupStep<String>("Environment for \"${group.getName()}\"", profiles) {
            override fun getTextFor(value: String): String =
                RunConfigurationHelper.envFileDisplayName(value) + (if (value == group.getEnvFilePath()) "  (active)" else "")

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != group.getEnvFilePath()) {
                    group.setEnvFilePath(selectedValue)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    private fun selectedGroup(e: AnActionEvent): LanesRunConfiguration? {
        val project = e.project ?: return null
        return RunManager.getInstance(project).selectedConfiguration?.configuration as? LanesRunConfiguration
    }
}
