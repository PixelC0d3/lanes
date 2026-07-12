package io.github.welingtonmonteiro.multiplerun.nodejs

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import io.github.welingtonmonteiro.multiplerun.MultiplerunIcons

/**
 * Run-toolbar button (the same toolbar as Rerun/Stop, for a Node app started with the IDE's own
 * Play/Debug) that switches which `.env` profile is active and re-runs the configuration with it.
 * Shown only once at least two profiles are configured - otherwise there is nothing to switch.
 */
class SwitchNodeEnvAction(
    private val configuration: AbstractNodeTargetRunProfile,
    private val environment: ExecutionEnvironment,
) : AnAction("Switch .env File", "Pick which .env profile to run with and restart", MultiplerunIcons.EnvVariables) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = NodeEnvFileSettings.of(configuration).profiles.size >= 2
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = NodeEnvFileSettings.of(configuration)
        if (settings.profiles.size < 2) {
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(settings.profiles)
            .setTitle("Switch .env File")
            .setSelectedValue(settings.active, true)
            .setItemChosenCallback { chosen -> switchTo(chosen) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    private fun switchTo(chosen: String) {
        val current = NodeEnvFileSettings.of(configuration)
        if (chosen == current.active) {
            return
        }
        val updated = current.withActive(chosen)
        // Update the canonical configuration the re-run will use, so the new choice both persists
        // and is picked up on the restart. The session's configuration may be a clone, so update
        // both when they differ.
        NodeEnvFileSettings.store(configuration, updated)
        val canonical = environment.runnerAndConfigurationSettings?.configuration
        if (canonical is RunConfigurationBase<*> && canonical !== configuration) {
            NodeEnvFileSettings.store(canonical, updated)
        }
        val runSettings = environment.runnerAndConfigurationSettings ?: return
        ProgramRunnerUtil.executeConfiguration(runSettings, environment.executor)
    }
}
