package io.github.welingtonmonteiro.lanes

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import javax.swing.Icon

class LanesConfigurationType : ConfigurationTypeBase(
    // Type id changed from "Multirun" to "Lanes" (this fork had no real users yet), so it
    // can never collide with the original Multirun plugin's own type id if both are ever
    // installed together. Existing configurations saved under the old "Multirun" id will need to
    // be recreated.
    "Lanes", "Lanes", "Run multiple run configurations at once",
    NotNullLazyValue.createValue { LanesIcons.Mark }
) {
    init {
        addFactory(LanesConfigurationFactory(this))
    }

    /**
     * Not [com.intellij.execution.configurations.SimpleConfigurationType] (which used to double as
     * both type and factory here, until 2.1.4): its `getIcon(RunConfiguration)` is `final` and
     * always returns the type-level icon, discarding the configuration argument. Both the "Edit
     * Configurations" tree's non-edited leaf nodes and the toolbar Play/Debug widget resolve their
     * icon through `ProgramRunnerUtil.getConfigurationIcon() -> getRawIcon() ->
     * settings.getFactory().getIcon(settings.getConfiguration())` - with a `SimpleConfigurationType`
     * that call could never reach `LanesRunConfiguration.getIcon()` no matter what it returned,
     * which is why the "configured" icon (2.1.3) never showed up there for already-saved instances
     * and clearing the icon cache (first attempt at 2.1.4) didn't help either: the platform kept
     * recomputing the same wrong value. A plain [ConfigurationFactory] has no such restriction.
     */
    private class LanesConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            LanesRunConfiguration(project, this, "Lanes")

        override fun getIcon(configuration: RunConfiguration): Icon =
            configuration.getIcon() ?: super.getIcon(configuration)

        // Stable and decoupled from the (localizable) display name, unlike ConfigurationFactory's
        // own deprecated default for getId() (which just delegates to getName()). Safe for configs
        // already saved under the old SimpleConfigurationType-based type: writeExternal skips the
        // factoryName attribute entirely whenever the type is a SimpleConfigurationType, so none of
        // them have one persisted, and RunManagerImpl.getFactory() treats a missing factoryName as
        // an automatic match for a type's only factory - this id only governs configs saved from
        // now on.
        override fun getId(): String = "Lanes"
    }
}
