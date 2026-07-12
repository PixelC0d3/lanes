package io.github.welingtonmonteiro.multiplerun

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

class MultiplerunConfigurationType : SimpleConfigurationType(
    // Type id changed from "Multirun" to "Multiplerun" (this fork had no real users yet), so it
    // can never collide with the original Multirun plugin's own type id if both are ever
    // installed together. Existing configurations saved under the old "Multirun" id will need to
    // be recreated.
    "Multiplerun", "Multiple Run", "Run multiple run configurations at once",
    NotNullLazyValue.createValue { MultiplerunIcons.Mark }
), ConfigurationType {

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return MultiplerunRunConfiguration(project, this, "Multiple Run")
    }
}
