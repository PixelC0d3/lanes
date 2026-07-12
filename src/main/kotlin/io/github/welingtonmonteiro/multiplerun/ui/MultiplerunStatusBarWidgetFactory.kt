package io.github.welingtonmonteiro.multiplerun.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/** Registers the [MultiplerunStatusBarWidget] in the IDE status bar (toggle in the widget menu). */
class MultiplerunStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = MultiplerunStatusBarWidget.ID

    override fun getDisplayName(): String = "Multiple Run"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = MultiplerunStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
