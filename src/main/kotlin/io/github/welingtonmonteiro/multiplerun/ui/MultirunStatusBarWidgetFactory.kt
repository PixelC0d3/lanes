package io.github.welingtonmonteiro.multiplerun.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Registers the [MultirunStatusBarWidget] in the IDE status bar (toggle in the widget menu). */
class MultirunStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = MultirunStatusBarWidget.ID

    override fun getDisplayName(): String = "Multiple Run"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = MultirunStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
