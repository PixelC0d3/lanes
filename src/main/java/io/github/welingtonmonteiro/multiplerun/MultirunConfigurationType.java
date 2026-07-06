package io.github.welingtonmonteiro.multiplerun;

import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.SimpleConfigurationType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

public class MultirunConfigurationType extends SimpleConfigurationType implements ConfigurationType {

    public MultirunConfigurationType() {
        // the type id stays "Multirun" on purpose: it is persisted in every saved run
        // configuration, so changing it would orphan them; only the display name is rebranded
        super("Multirun", "Multiple Run", "Run multiple run configurations at once",
              NotNullLazyValue.createValue(() -> AllIcons.Actions.Rerun));
    }

    @Override
    public @NotNull
    RunConfiguration createTemplateConfiguration(@NotNull final Project project) {
        return new MultirunRunConfiguration(project, this, "Multiple Run");
    }
}
