package io.github.pixelcodes.lanes.nodejs

import com.intellij.execution.RunManager
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

import io.github.pixelcodes.lanes.LanesIcons
import io.github.pixelcodes.lanes.RunConfigurationHelper

/**
 * Toolbar button (see [io.github.pixelcodes.lanes.LanesEnvProfileToolbarAction] for the Lanes
 * group counterpart and why this is a plain button + popup rather than a pre-Play target picker)
 * that lets you pick which `.env` profile the *selected* Node-based run configuration runs with
 * next, without restarting an already-running instance. Shown only once the configuration has 2+
 * saved profiles.
 *
 * Part of the optional module (`META-INF/lanes-nodejs.xml`) - only loaded when the host IDE has
 * the JavaScript plugin.
 */
class NodeEnvProfileToolbarAction : AnAction(
    "Pick Env Profile", "Choose which .env profile the selected Node run configuration runs with next",
    LanesIcons.EnvVariables,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val config = selectedConfig(e)
        val settings = config?.let { NodeEnvFileSettings.of(it) }
        e.presentation.isEnabledAndVisible = (settings?.profiles?.size ?: 0) >= 2
        if (settings != null) {
            e.presentation.description = "Env: ${RunConfigurationHelper.envFileDisplayName(settings.active)} - click to change"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = selectedConfig(e) ?: return
        val settings = NodeEnvFileSettings.of(config)
        if (settings.profiles.size < 2) {
            return
        }
        val step = object : BaseListPopupStep<String>("Environment for \"${config.getName()}\"", settings.profiles) {
            override fun getTextFor(value: String): String =
                RunConfigurationHelper.envFileDisplayName(value) + (if (value == settings.active) "  (active)" else "")

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != settings.active) {
                    NodeEnvFileSettings.store(config, settings.withActive(selectedValue))
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    private fun selectedConfig(e: AnActionEvent): AbstractNodeTargetRunProfile? {
        val project = e.project ?: return null
        return RunManager.getInstance(project).selectedConfiguration?.configuration as? AbstractNodeTargetRunProfile
    }
}
