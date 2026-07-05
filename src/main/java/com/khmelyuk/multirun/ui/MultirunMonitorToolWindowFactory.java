package com.khmelyuk.multirun.ui;

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
        final MultirunMonitorPanel panel = new MultirunMonitorPanel(project);
        final Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
