package io.github.welingtonmonteiro.multiplerun.ui;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;

/** Registers the {@link MultirunStatusBarWidget} in the IDE status bar (toggle in the widget menu). */
public class MultirunStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @NotNull
    @Override
    public String getId() {
        return MultirunStatusBarWidget.ID;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Multiple Run";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @NotNull
    @Override
    public StatusBarWidget createWidget(@NotNull Project project) {
        return new MultirunStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
