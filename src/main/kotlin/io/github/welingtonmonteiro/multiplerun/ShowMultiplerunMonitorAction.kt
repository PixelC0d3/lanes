package io.github.welingtonmonteiro.multiplerun

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import io.github.welingtonmonteiro.multiplerun.ui.MultiplerunMonitorToolWindowFactory

/** Run menu action that opens the "Multiple Run Monitor" tool window. */
class ShowMultiplerunMonitorAction : AnAction(), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getProject() ?: return
        val toolWindow =
            ToolWindowManager.getInstance(project).getToolWindow(MultiplerunMonitorToolWindowFactory.TOOL_WINDOW_ID)
        toolWindow?.activate(null)
    }

    override fun update(event: AnActionEvent) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null)
    }
}
