package io.github.welingtonmonteiro.multiplerun.ui;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/** Registers the "Multiple Run Monitor" tool window (bottom stripe of the IDE). */
public class MultirunMonitorToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String TOOL_WINDOW_ID = "Multiple Run Monitor";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        final ContentFactory contentFactory = ContentFactory.getInstance();

        final MultirunMonitorPanel monitorPanel = new MultirunMonitorPanel(project);
        final Content processes = contentFactory.createContent(monitorPanel, "Processes", false);
        processes.setDisposer(monitorPanel);
        toolWindow.getContentManager().addContent(processes);

        // second tab: aggregated console output of every running app (docker compose logs -f style)
        final AggregatedLogPanel logPanel = new AggregatedLogPanel(project);
        final Content logs = contentFactory.createContent(logPanel, "Logs", false);
        logs.setDisposer(logPanel);
        toolWindow.getContentManager().addContent(logs);
    }
}
