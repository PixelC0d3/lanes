package io.github.welingtonmonteiro.lanes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import io.github.welingtonmonteiro.lanes.ui.LanesMonitorToolWindowFactory

/** Run menu action that opens the "Lanes Monitor" tool window. */
class ShowLanesMonitorAction : AnAction(), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getProject() ?: return
        val toolWindow =
            ToolWindowManager.getInstance(project).getToolWindow(LanesMonitorToolWindowFactory.TOOL_WINDOW_ID)
        toolWindow?.activate(null)
    }

    override fun update(event: AnActionEvent) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null)
    }
}
