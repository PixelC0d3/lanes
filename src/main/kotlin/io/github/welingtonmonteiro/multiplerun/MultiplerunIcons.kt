package io.github.welingtonmonteiro.multiplerun

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The "Lanes" icon set (see /brand/BRAND_GUIDELINES.md at the repo root). [IconLoader] resolves
 * the matching `_dark` sibling automatically on dark themes - same mechanism already used for
 * pluginIcon.svg/pluginIcon_dark.svg, no per-icon dark variant needed here.
 */
object MultiplerunIcons {
    // A dedicated 16x16-declared file, NOT pluginIcon.svg: that one is required to declare
    // width="40" height="40" (the Settings > Plugins list size), and Icon.getIconWidth()/
    // getIconHeight() read that declared size, not the viewBox - reusing it here rendered every
    // 16px UI slot (ConfigurationType icon, New Configuration list, ...) at 2.5x size.
    @JvmField val Mark: Icon = IconLoader.getIcon("/icons/mark.svg", MultiplerunIcons::class.java)
    @JvmField val Refresh: Icon = IconLoader.getIcon("/icons/refresh.svg", MultiplerunIcons::class.java)
    @JvmField val Restart: Icon = IconLoader.getIcon("/icons/restart.svg", MultiplerunIcons::class.java)
    @JvmField val RestartAll: Icon = IconLoader.getIcon("/icons/restart-all.svg", MultiplerunIcons::class.java)
    @JvmField val Stop: Icon = IconLoader.getIcon("/icons/stop.svg", MultiplerunIcons::class.java)
    @JvmField val StopAll: Icon = IconLoader.getIcon("/icons/stop-all.svg", MultiplerunIcons::class.java)
    @JvmField val ColumnChooser: Icon = IconLoader.getIcon("/icons/column-chooser.svg", MultiplerunIcons::class.java)
    @JvmField val EnvVariables: Icon = IconLoader.getIcon("/icons/env-variables.svg", MultiplerunIcons::class.java)
    @JvmField val Docker: Icon = IconLoader.getIcon("/icons/docker.svg", MultiplerunIcons::class.java)
    @JvmField val OpenDashboard: Icon = IconLoader.getIcon("/icons/open-dashboard.svg", MultiplerunIcons::class.java)
    @JvmField val Logs: Icon = IconLoader.getIcon("/icons/logs.svg", MultiplerunIcons::class.java)
    @JvmField val Process: Icon = IconLoader.getIcon("/icons/process.svg", MultiplerunIcons::class.java)
    @JvmField val MemoryLeak: Icon = IconLoader.getIcon("/icons/memory-leak.svg", MultiplerunIcons::class.java)

    // Trimmed down from brand/empty-state.svg: the two <text> lines and the CSS <style>/@media
    // block were dropped (real JBLabels handle the text and theme colors on the Swing side; see
    // MultiplerunMonitorPanel's empty-state panel), leaving just the illustration.
    @JvmField val EmptyState: Icon = IconLoader.getIcon("/icons/empty-state.svg", MultiplerunIcons::class.java)

    // Shown instead of Mark for any saved Multiple Run instance (MultiplerunRunConfiguration.getIcon()
    // and grouped rows in the Monitor) - the lanes wrapped in a restart/orchestration arrow. Not the
    // type-level icon: MultiplerunConfigurationType.getIcon() (the "Add New Configuration" entry,
    // the tree's category node) keeps using Mark, since it represents the type, not one instance.
    @JvmField val Configured: Icon = IconLoader.getIcon("/icons/configured.svg", MultiplerunIcons::class.java)
}
