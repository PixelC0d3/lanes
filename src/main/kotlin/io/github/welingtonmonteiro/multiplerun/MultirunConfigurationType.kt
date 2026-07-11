package io.github.welingtonmonteiro.multiplerun

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

class MultirunConfigurationType : SimpleConfigurationType(
    // the type id stays "Multirun" on purpose: it is persisted in every saved run
    // configuration, so changing it would orphan them; only the display name is rebranded
    "Multirun", "Multiple Run", "Run multiple run configurations at once",
    NotNullLazyValue.createValue { AllIcons.Actions.Rerun }
), ConfigurationType {

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return MultirunRunConfiguration(project, this, "Multiple Run")
    }
}
