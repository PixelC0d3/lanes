package io.github.welingtonmonteiro.multiplerun

import com.intellij.coverage.CoverageExecutor
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.executeState
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.profiler.DefaultProfilerExecutorGroup

import io.github.welingtonmonteiro.multiplerun.ui.MultirunRunConfigurationEditor

/**
 * Runner for Multirun configurations.
 *
 * Extends [GenericProgramRunner] so the platform performs the `startRunProfile` call itself: the
 * plugin no longer touches the internal `ExecutionManager.startRunProfile` API (flagged by the
 * JetBrains Marketplace verifier). This runner only hands the state to the platform's default
 * state execution.
 *
 * @author Ruslan Khmelyuk
 */
class MultirunRunner : GenericProgramRunner<MultirunRunConfiguration>() {

    override fun getRunnerId(): String = "multirun"

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        return executeState(state, environment, this)
    }

    override fun getSettingsEditor(
        executor: Executor,
        configuration: RunConfiguration,
    ): SettingsEditor<MultirunRunConfiguration>? {
        return MultirunRunConfigurationEditor(configuration.getProject())
    }

    override fun canRun(executorId: String, runProfile: RunProfile): Boolean {
        return runProfile is MultirunRunConfiguration && isSupportedExecutor(executorId)
    }

    companion object {
        const val JREBEL_EXECUTOR_ID = "JRebel Executor"
        const val JREBEL_DEBUG_ID = "JRebel Debug"

        /** True for the executors a Multirun group can be launched with (case-insensitive). */
        @JvmStatic
        fun isSupportedExecutor(executorId: String?): Boolean {
            return DefaultRunExecutor.EXECUTOR_ID.equals(executorId, ignoreCase = true)
                || DefaultDebugExecutor.EXECUTOR_ID.equals(executorId, ignoreCase = true)
                || DefaultProfilerExecutorGroup.EXECUTOR_ID.equals(executorId, ignoreCase = true)
                || CoverageExecutor.EXECUTOR_ID.equals(executorId, ignoreCase = true)
                || JREBEL_EXECUTOR_ID.equals(executorId, ignoreCase = true)
                || JREBEL_DEBUG_ID.equals(executorId, ignoreCase = true)
        }
    }
}
