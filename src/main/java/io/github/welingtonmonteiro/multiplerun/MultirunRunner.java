package io.github.welingtonmonteiro.multiplerun;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.coverage.CoverageExecutor;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.DefaultProgramRunnerKt;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.profiler.DefaultProfilerExecutorGroup;
import io.github.welingtonmonteiro.multiplerun.ui.MultirunRunConfigurationEditor;

/**
 * Runner for Multirun configurations.
 *
 * @author Ruslan Khmelyuk
 */
public class MultirunRunner implements ProgramRunner<MultirunRunConfiguration> {

    public static final String JREBEL_EXECUTOR_ID = "JRebel Executor";
    public static final String JREBEL_DEBUG_ID = "JRebel Debug";

    @NotNull
    @Override
    public String getRunnerId() {
        return "multirun";
    }

    @Override
    public void execute(@NotNull final ExecutionEnvironment environment) throws ExecutionException {
        final RunProfileState runProfileState = environment.getState();
        if (runProfileState != null) {
            final Project project = environment.getProject();

            ExecutionManager.Companion.getInstance(project).startRunProfile(
                    environment,
                    runProfileState,
                    runProfileState1 -> DefaultProgramRunnerKt.executeState(runProfileState1, environment, MultirunRunner.this)
            );
        }
    }

    @Override
    public @Nullable
    SettingsEditor<MultirunRunConfiguration> getSettingsEditor(final Executor executor, final RunConfiguration configuration) {
        return new MultirunRunConfigurationEditor(configuration.getProject());
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile runProfile) {
        return runProfile instanceof MultirunRunConfiguration && isSupportedExecutor(executorId);
    }

    /** True for the executors a Multirun group can be launched with (case-insensitive). */
    static boolean isSupportedExecutor(String executorId) {
        return DefaultRunExecutor.EXECUTOR_ID.equalsIgnoreCase(executorId)
                || DefaultDebugExecutor.EXECUTOR_ID.equalsIgnoreCase(executorId)
                || DefaultProfilerExecutorGroup.EXECUTOR_ID.equalsIgnoreCase(executorId)
                || CoverageExecutor.EXECUTOR_ID.equalsIgnoreCase(executorId)
                || JREBEL_EXECUTOR_ID.equalsIgnoreCase(executorId)
                || JREBEL_DEBUG_ID.equalsIgnoreCase(executorId);
    }
}
