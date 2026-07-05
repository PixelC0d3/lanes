package com.khmelyuk.multirun;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.ExecutionTarget;
import com.intellij.execution.ExecutionTargetManager;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.content.Content;

/**
 * @author Ruslan Khmelyuk
 */
public class MultirunRunnerState implements RunProfileState {

    /** How long to wait for the previously running processes to die before starting again. */
    private static final long RESTART_TERMINATION_TIMEOUT_MS = 10_000;

    private final double delayTime;
    private final boolean reuseTabs;
    private final boolean reuseTabsWithFailure;
    private final boolean startOneByOne;
    private final boolean markFailedProcess;
    private final boolean hideSuccessProcess;
    private final boolean restartRunning;
    private final EnvironmentVariablesData envData;
    private final Project project;
    private final String configurationName;
    private final List<RunConfiguration> runConfigurations;
    private final StopRunningMultirunConfigurationsAction stopRunningMultirunConfiguration;

    public MultirunRunnerState(List<RunConfiguration> runConfigurations,
                               boolean startOneByOne, double delayTime,
                               boolean reuseTabs, boolean reuseTabsWithFailure,
                               boolean markFailedProcess, boolean hideSuccessProcess,
                               EnvironmentVariablesData envData,
                               boolean restartRunning, Project project, String configurationName) {

        this.delayTime = delayTime;
        this.reuseTabs = reuseTabs;
        this.reuseTabsWithFailure = reuseTabsWithFailure;
        this.startOneByOne = startOneByOne;
        this.runConfigurations = runConfigurations;
        this.markFailedProcess = markFailedProcess;
        this.hideSuccessProcess = hideSuccessProcess;
        this.envData = envData == null ? EnvironmentVariablesData.DEFAULT : envData;
        this.restartRunning = restartRunning;
        this.project = project;
        this.configurationName = configurationName;

        ActionManager actionManager = ActionManager.getInstance();
        stopRunningMultirunConfiguration = (StopRunningMultirunConfigurationsAction)
                actionManager.getAction(StopRunningMultirunConfigurationsAction.ACTION_ID);
    }

    @Nullable
    @Override
    public ExecutionResult execute(Executor executor, @NotNull ProgramRunner programRunner) {
        stopRunningMultirunConfiguration.beginStartingConfigurations();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (restartRunning) {
                // like the built-in Compound configuration: stop what this Multirun started
                // before and only then start again, so ports/resources are released
                waitForTermination(stopRunningMultirunConfiguration.stopProcessesOf(project, configurationName));
            }
            runConfigurations(executor, runConfigurations, 0);
        });

        return null;
    }

    private static void waitForTermination(List<ProcessHandler> handlers) {
        final long deadline = System.currentTimeMillis() + RESTART_TERMINATION_TIMEOUT_MS;
        for (ProcessHandler handler : handlers) {
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            handler.waitFor(remaining);
        }
    }

    private void runConfigurations(final Executor executor, final List<RunConfiguration> runConfigurations, final int index) {
        if (index >= runConfigurations.size()) {
            stopRunningMultirunConfiguration.doneStaringConfigurations();
            return;
        }
        if (!stopRunningMultirunConfiguration.canContinueStartingConfigurations()) {
            stopRunningMultirunConfiguration.doneStaringConfigurations();
            // don't start more configurations if user stopped the plugin work.
            return;
        }

        final RunConfiguration runConfiguration = runConfigurations.get(index);
        final Project project = runConfiguration.getProject();

        boolean started = false;
        try {
            // apply the Multirun environment variables on top of the child configuration; works on a clone,
            // so the user's configuration is never permanently modified
            final RunConfiguration effectiveConfiguration = RunConfigurationHelper.withEnvironmentOverride(runConfiguration, envData);

            final RunnerAndConfigurationSettings configuration;
            if (effectiveConfiguration == runConfiguration) {
                // configuration may not be registered anymore (e.g. it was removed) - skip it;
                // the finally block still chains to the next configuration.
                configuration = RunManager.getInstance(project).findSettings(runConfiguration);
            } else {
                // a clone is not registered in the RunManager, wrap it in fresh settings
                configuration = RunManager.getInstance(project).createConfiguration(effectiveConfiguration, effectiveConfiguration.getFactory());
            }
            if (configuration == null) {return;}

            final ProgramRunner runner = ProgramRunner.getRunner(executor.getId(), effectiveConfiguration);
            if (runner == null) {return;}
            if (!checkRunConfiguration(executor, project, configuration)) {return;}

            final ExecutionEnvironment executionEnvironment = new ExecutionEnvironment(executor, runner, configuration, project);

            executionEnvironment.setCallback(
                    new ProgramRunner.Callback() {
                        private final AtomicBoolean processTerminated = new AtomicBoolean(false);

                        @SuppressWarnings("ConstantConditions")
                        @Override
                        public void processStarted(final RunContentDescriptor descriptor) {
                            if (descriptor == null) {
                                if (startOneByOne) {
                                    // start next configuration..
                                    ApplicationManager.getApplication().executeOnPooledThread(
                                            () -> runConfigurations(executor, runConfigurations, index + 1));
                                }
                                return;
                            }

                            final ProcessHandler processHandler = descriptor.getProcessHandler();
                            if (processHandler != null) {
                                processHandler.addProcessListener(new ProcessListener() {
                                    @SuppressWarnings("ConstantConditions")
                                    @Override
                                    public void startNotified(@NotNull final ProcessEvent processEvent) {
                                        final Content content = descriptor.getAttachedContent();
                                        if (content == null) {
                                            return;
                                        }

                                        final boolean canContinue = stopRunningMultirunConfiguration.canContinueStartingConfigurations();
                                        if (!canContinue) {
                                            // Multirun was stopped - destroy processes that are still starting up
                                            processHandler.destroyProcess();
                                        }

                                        // All Content (tab) mutations must run on the EDT.
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            content.setIcon(descriptor.getIcon());
                                            if (!canContinue) {
                                                if (!content.isPinned() && !startOneByOne) {
                                                    // checks if not pinned, to avoid destroying already existed tab
                                                    // checks if start one by one - no need to close the console tab, as it's won't be shown
                                                    // as other checks disallow starting it

                                                    // content.getManager() can be null, if content is removed already as part of destroy above
                                                    if (content.getManager() != null) {
                                                        content.getManager().removeContent(content, false);
                                                    }
                                                }
                                            } else {
                                                // ensure tab is not pinned
                                                content.setPinned(false);

                                                // mark running process tab with *
                                                content.setDisplayName(descriptor.getDisplayName() + "*");
                                            }
                                        });
                                    }

                                    @Override
                                    public void processTerminated(@NotNull final ProcessEvent processEvent) {
                                        onTermination(processEvent);
                                        processTerminated.set(true);
                                        stopRunningMultirunConfiguration.removeProcess(project, processEvent.getProcessHandler());
                                    }

                                    private void onTermination(final ProcessEvent processEvent) {
                                        final Content content = descriptor.getAttachedContent();
                                        if (content == null) {
                                            return;
                                        }

                                        // exit code is 0 if the process completed successfully
                                        final boolean completedSuccessfully = (processEvent.getExitCode() == 0);

                                        if (hideSuccessProcess && completedSuccessfully) {
                                            // close the tab for the success process and exit - nothing else could be done
                                            ApplicationManager.getApplication().invokeLater(() -> {
                                                if (content.getManager() != null) {
                                                    content.getManager().removeContent(content, false);
                                                }
                                            });
                                            return;
                                        }

                                        // attempt to pin tab if not completed successfully or asked not to reuse tabs
                                        final boolean pinTab = (completedSuccessfully && !reuseTabs) || (!completedSuccessfully && !reuseTabsWithFailure);
                                        // add the alert icon in case if process exited with non-0 status
                                        final boolean markFailed = markFailedProcess && !completedSuccessfully;

                                        // All Content (tab) mutations must run on the EDT.
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            if (pinTab && !stopRunningMultirunConfiguration.isStopMultirunTriggered()) {
                                                // ... do not pin if multirun stopped by "Stop Multirun" action.
                                                content.setPinned(true);
                                            }

                                            // remove the * used to identify running process
                                            content.setDisplayName(descriptor.getDisplayName());

                                            if (markFailed) {
                                                content.setIcon(LayeredIcon.create(content.getIcon(), AllIcons.Nodes.TabAlert));
                                            }
                                        });
                                    }
                                });
                            }
                            stopRunningMultirunConfiguration.addProcess(project, configurationName, processHandler);

                            final boolean moreConfigurationsToRun = index + 1 < runConfigurations.size();
                            if (startOneByOne && moreConfigurationsToRun) {
                                // start next configuration..

                                if (delayTime > 0) {
                                    final long start = System.currentTimeMillis();
                                    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Waiting for delay") {
                                        @Override
                                        public void run(@NotNull ProgressIndicator progressIndicator) {
                                            try {
                                                progressIndicator.setIndeterminate(false);
                                                while (System.currentTimeMillis() - start < delayTime * 1000) {
                                                    if (processTerminated.get()) {
                                                        break;
                                                    }
                                                    if (progressIndicator.isCanceled()) {
                                                        return;
                                                    }
                                                    final double passed = (double) (System.currentTimeMillis() - start) / 1000;
                                                    final String seconds = (delayTime - passed == 1) ? "second" : "seconds";
                                                    progressIndicator.setFraction(passed / delayTime);
                                                    final String waitingPeriod = String.format("%.1f", delayTime - passed);
                                                    progressIndicator.setText("waiting " + waitingPeriod + " " + seconds);
                                                    Thread.sleep(100);
                                                }
                                            } catch (InterruptedException ignored) {
                                                return;
                                            }
                                            ApplicationManager.getApplication().executeOnPooledThread(
                                                    () -> runConfigurations(executor, runConfigurations, index + 1));
                                        }
                                    });
                                } else if (delayTime < 0) {
                                    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Waiting for process to complete") {
                                        @Override
                                        public void run(@NotNull ProgressIndicator progressIndicator) {
                                            try {
                                                while (!processTerminated.get()) {
                                                    if (progressIndicator.isCanceled()) {
                                                        return;
                                                    }
                                                    Thread.sleep(200);
                                                }
                                            } catch (InterruptedException ignored) {
                                                return;
                                            }
                                            ApplicationManager.getApplication().executeOnPooledThread(
                                                    () -> runConfigurations(executor, runConfigurations, index + 1));
                                        }
                                    });
                                } else {
                                    ApplicationManager.getApplication().executeOnPooledThread(
                                            () -> runConfigurations(executor, runConfigurations, index + 1));
//                                    runConfigurations(executor, runConfigurations, index + 1);
                                }
                            } else {
                                stopRunningMultirunConfiguration.doneStaringConfigurations();
                            }
                        }
                    }
            );

            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        try {
                            runner.execute(executionEnvironment);
                        } catch (ExecutionException e) {
                            ExecutionUtil.handleExecutionError(project, executor.getToolWindowId(), configuration.getConfiguration(), e);
                        }
                    });
            started = true;
        } finally {
            // start the next one
            if (!startOneByOne) {
                ApplicationManager.getApplication().executeOnPooledThread(
                        () -> runConfigurations(executor, runConfigurations, index + 1));
            } else if (!started) {
                // failed to start current, means the chain is broken
                ApplicationManager.getApplication().executeOnPooledThread(
                        () -> runConfigurations(executor, runConfigurations, index + 1));
            }
        }
    }

    private boolean checkRunConfiguration(Executor executor, Project project, RunnerAndConfigurationSettings configuration) {
        ExecutionTarget target = ExecutionTargetManager.getActiveTarget(project);

        if (!ExecutionTargetManager.canRun(configuration.getConfiguration(), target)) {
            ExecutionUtil.handleExecutionError(
                    project, executor.getToolWindowId(), configuration.getConfiguration(),
                    new ExecutionException(StringUtil.escapeXmlEntities("Cannot run '" + configuration.getName()
                                                                                + "' on '" + target.getDisplayName() + "'")));
            return false;
        }

        if (!RunManagerImpl.canRunConfiguration(configuration, executor) || configuration.isEditBeforeRun()) {
            if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                return false;
            }

            while (!RunManagerImpl.canRunConfiguration(configuration, executor)) {
                if (0 == Messages.showYesNoDialog(project, "Configuration is still incorrect. Do you want to edit it again?",
                                                  "Change Configuration Settings",
                                                  "Edit", "Continue Anyway", Messages.getErrorIcon())) {
                    if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        return true;
    }
}
