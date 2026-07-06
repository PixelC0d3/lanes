package io.github.welingtonmonteiro.multiplerun;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import io.github.welingtonmonteiro.multiplerun.ui.MultirunMonitorToolWindowFactory;

/** Run menu action that opens the "Multiple Run Monitor" tool window. */
public class ShowMultirunMonitorAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        final Project project = event.getProject();
        if (project == null) {
            return;
        }
        final ToolWindow toolWindow =
                ToolWindowManager.getInstance(project).getToolWindow(MultirunMonitorToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null);
    }
}
