package io.github.welingtonmonteiro.lanes.nodejs

import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI

import io.github.welingtonmonteiro.lanes.LanesIcons
import io.github.welingtonmonteiro.lanes.RunConfigurationHelper

/**
 * Run-toolbar button (next to Rerun/Stop, for a Node app started with the IDE's own Play/Debug) that
 * switches which `.env` profile is active and **restarts the same run** with it. Shown only once at
 * least two profiles are configured. Mirrors the Lanes monitor's "Switch Environment" dialog:
 * a movable dialog listing profiles by file name (full path as tooltip), the active one marked.
 */
class SwitchNodeEnvAction(
    private val configuration: AbstractNodeTargetRunProfile,
    private val environment: ExecutionEnvironment,
    /** The process handler of this run, so the switch restarts *this* instance instead of adding one. */
    private val runningProcess: () -> ProcessHandler?,
) : AnAction("Switch .env File", "Pick which .env profile to run with and restart", LanesIcons.EnvVariables) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = NodeEnvFileSettings.of(configuration).profiles.size >= 2
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = NodeEnvFileSettings.of(configuration)
        if (settings.profiles.size < 2) {
            return
        }
        val chosen = chooseProfile(settings) ?: return
        if (chosen != settings.active) {
            switchAndRestart(settings.withActive(chosen))
        }
    }

    /** Movable modal to pick a profile (null when cancelled), matching the monitor's switch dialog. */
    private fun chooseProfile(settings: NodeEnvFileSettings): String? {
        val combo = ComboBox(settings.profiles.toTypedArray())
        combo.renderer = EnvFileCellRenderer(settings.active)
        combo.selectedItem = settings.active.takeIf { settings.profiles.contains(it) } ?: settings.profiles.first()

        val dialog = object : DialogWrapper(environment.project, true) {
            init {
                title = "Switch .env File"
                setOKButtonText("Apply & Restart")
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout(8, 10))
                panel.border = JBUI.Borders.empty(8, 6)
                val header = JBLabel(
                    "Environment file for \"${configuration.name}\" - it will restart with the one you pick:",
                    LanesIcons.EnvVariables, JBLabel.LEFT)
                panel.add(header, BorderLayout.NORTH)
                panel.add(combo, BorderLayout.CENTER)
                return panel
            }

            override fun getPreferredFocusedComponent(): JComponent = combo
        }
        return if (dialog.showAndGet()) combo.selectedItem as String? else null
    }

    private fun switchAndRestart(updated: NodeEnvFileSettings) {
        // Update both the session's configuration and the canonical one the restart will read, so the
        // new choice persists and is picked up. copyable user data survives the clone() the run makes.
        NodeEnvFileSettings.store(configuration, updated)
        val runSettings = environment.runnerAndConfigurationSettings ?: return
        val canonical = runSettings.configuration
        if (canonical is RunConfigurationBase<*> && canonical !== configuration) {
            NodeEnvFileSettings.store(canonical, updated)
        }
        val project = environment.project
        // restartRunProfile with this run's process handler stops that instance and starts a fresh
        // one - a real restart, not a second process next to the first.
        ExecutionManager.getInstance(project).restartRunProfile(
            project,
            environment.executor,
            ExecutionTargetManager.getActiveTarget(project),
            runSettings,
            runningProcess())
    }

    /** Shows each profile by its file name, the active one marked, full path as the tooltip. */
    private class EnvFileCellRenderer(private val active: String) : SimpleListCellRenderer<String>() {
        override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value != null) {
                text = RunConfigurationHelper.envFileDisplayName(value) + (if (value == active) "  (active)" else "")
                toolTipText = value
            }
        }
    }
}
