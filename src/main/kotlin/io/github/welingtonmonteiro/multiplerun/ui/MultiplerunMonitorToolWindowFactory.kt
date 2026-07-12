package io.github.welingtonmonteiro.multiplerun.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.welingtonmonteiro.multiplerun.MultiplerunIcons

/** Registers the "Multiple Run Monitor" tool window (bottom stripe of the IDE). */
class MultiplerunMonitorToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        const val TOOL_WINDOW_ID = "Multiple Run Monitor"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val monitorPanel = MultiplerunMonitorPanel(project)
        val processes = contentFactory.createContent(monitorPanel, "Processes", false)
        processes.setIcon(MultiplerunIcons.Process)
        processes.setDisposer(monitorPanel)
        toolWindow.getContentManager().addContent(processes)

        // second tab: aggregated console output of every running app (docker compose logs -f style)
        val logPanel = AggregatedLogPanel(project)
        val logs = contentFactory.createContent(logPanel, "Logs", false)
        logs.setIcon(MultiplerunIcons.Logs)
        logs.setDisposer(logPanel)
        toolWindow.getContentManager().addContent(logs)
    }
}
