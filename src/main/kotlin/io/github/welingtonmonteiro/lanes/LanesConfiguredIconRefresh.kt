package io.github.welingtonmonteiro.lanes

import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Forces the platform to recompute the "configured" icon for every saved Lanes instance on
 * project open, instead of trusting whatever `RunManagerImpl`'s icon cache
 * (`RunConfigurationIconAndInvalidCache`) already holds for that configuration id.
 *
 * That cache computes `configuration.getIcon()` once per id and never recomputes it on its own -
 * confirmed by disassembling `RunConfigurationIconAndInvalidCache.get()`. Two surfaces stayed on the
 * plain mark even after `getIcon()` became unconditional in 2.1.3 (the "Edit Configurations" tree's
 * non-edited leaf nodes and the toolbar run/debug widget); disassembling
 * `RunConfigurableTreeRenderer.customizeCellRenderer()` and
 * `RunConfigurationsComboBoxAction.updatePresentation()` shows both read the icon via
 * `RunManagerEx.getConfigurationIcon(settings, ...)`, which is backed by that same cache. Any
 * configuration whose id was already cached before this plugin version shipped the "configured"
 * icon keeps showing the old one forever, since nothing about a plugin upgrade invalidates it on its
 * own. Explicitly clearing our own configurations' entries once per project open guarantees the next
 * read is a cache miss, which recomputes from the current (always-correct)
 * `LanesRunConfiguration.getIcon()`.
 */
class LanesConfiguredIconRefreshActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val runManager = RunManager.getInstance(project) as? RunManagerImpl ?: return
        val cache = runManager.iconCache
        for (settings in runManager.allSettings) {
            if (settings.configuration is LanesRunConfiguration) {
                cache.remove(settings.uniqueID)
            }
        }
    }
}
