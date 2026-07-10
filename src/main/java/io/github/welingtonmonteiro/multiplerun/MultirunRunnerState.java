package io.github.welingtonmonteiro.multiplerun;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
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
    /** Cap for a "Ready when" wait; when reached the next configuration starts anyway. */
    private static final long READY_TIMEOUT_MS = 120_000;
    /** Max automatic relaunches of a crashed app per run session (docker restart: on-failure). */
    private static final int MAX_CRASH_RESTARTS = 3;

    private final double delayTime;
    private final boolean reuseTabs;
    private final boolean reuseTabsWithFailure;
    private final boolean startOneByOne;
    private final boolean markFailedProcess;
    private final boolean hideSuccessProcess;
    private final boolean restartRunning;
    private final EnvironmentVariablesData envData;
    private final String envFilePath;
    private final String saveOutputDir;
    private final Map<String, Integer> memoryLimits;
    private final Map<String, String> readyConditions;
    /** Per-app env file overriding the group environment for that app, keyed by app name. */
    private final Map<String, String> appEnvFiles;
    private final boolean restartOnCrash;
    private final int memAlertThreshold;
    private final boolean memLimitRestart;
    private final int cpuAlertThreshold;
    private final Project project;
    private final String configurationName;
    private final List<RunConfiguration> runConfigurations;
    private final StopRunningMultirunConfigurationsAction stopRunningMultirunConfiguration;

    /** envData with the env file applied under it; recomputed on every run so file edits are picked up. */
    private volatile EnvironmentVariablesData effectiveEnvData;

    public MultirunRunnerState(List<RunConfiguration> runConfigurations,
                               boolean startOneByOne, double delayTime,
                               boolean reuseTabs, boolean reuseTabsWithFailure,
                               boolean markFailedProcess, boolean hideSuccessProcess,
                               EnvironmentVariablesData envData, String envFilePath,
                               String saveOutputDir, Map<String, Integer> memoryLimits,
                               Map<String, String> readyConditions,
                               Map<String, String> appEnvFiles,
                               boolean restartRunning, boolean restartOnCrash,
                               int memAlertThreshold, boolean memLimitRestart,
                               int cpuAlertThreshold,
                               Project project, String configurationName) {

        this.delayTime = delayTime;
        this.reuseTabs = reuseTabs;
        this.reuseTabsWithFailure = reuseTabsWithFailure;
        this.startOneByOne = startOneByOne;
        this.runConfigurations = runConfigurations;
        this.markFailedProcess = markFailedProcess;
        this.hideSuccessProcess = hideSuccessProcess;
        this.envData = envData == null ? EnvironmentVariablesData.DEFAULT : envData;
        this.envFilePath = envFilePath == null ? "" : envFilePath;
        this.effectiveEnvData = this.envData;
        // resolved here once: relative folders behave like the env file (project-root based)
        this.saveOutputDir = saveOutputDir == null || saveOutputDir.trim().isEmpty()
                ? "" : RunConfigurationHelper.resolveEnvFile(saveOutputDir, project).getPath();
        this.memoryLimits = memoryLimits == null ? Collections.emptyMap() : memoryLimits;
        this.readyConditions = readyConditions == null ? Collections.emptyMap() : readyConditions;
        this.appEnvFiles = appEnvFiles == null ? Collections.emptyMap() : appEnvFiles;
        this.restartOnCrash = restartOnCrash;
        this.memAlertThreshold = memAlertThreshold;
        this.memLimitRestart = memLimitRestart;
        this.cpuAlertThreshold = cpuAlertThreshold;
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
            // read the env file fresh on every run (file IO, so off the EDT)
            effectiveEnvData = RunConfigurationHelper.withEnvFile(envData, envFilePath, project);
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

        // docker-compose-like readiness gate: with a condition set, the next configuration
        // only starts after this one is ready (port open / log text seen / http healthy)
        final RunConfigurationHelper.ReadyCondition readyCondition =
                RunConfigurationHelper.parseReadyCondition(readyConditions.get(runConfiguration.getName()));
        final AtomicBoolean readyLogSeen = new AtomicBoolean(false);

        boolean started = false;
        try {
            // apply the Multirun environment variables on top of the child configuration; works on a clone,
            // so the user's configuration is never permanently modified
            EnvironmentVariablesData childEnvData = effectiveEnvData;
            final Integer memoryLimitMb = memoryLimits.get(runConfiguration.getName());
            if (memoryLimitMb != null && memoryLimitMb > 0) {
                // per-application heap cap (~docker mem_limit) via NODE_OPTIONS/JAVA_TOOL_OPTIONS
                childEnvData = RunConfigurationHelper.withMemoryLimit(childEnvData, memoryLimitMb);
            }
            // per-application env file: overrides the group environment for this app (more specific)
            final String appEnvFile = appEnvFiles.get(runConfiguration.getName());
            childEnvData = RunConfigurationHelper.withAppEnvFile(childEnvData, appEnvFile, project);
            // the env file actually in effect for THIS app - its per-app override when set, else the
            // group profile - so the monitor's Env column shows the app's real environment, not just
            // the group's (matters after a per-app switch from the monitor).
            final String effectiveEnvFilePath =
                    (appEnvFile != null && !appEnvFile.trim().isEmpty()) ? appEnvFile : envFilePath;
            // effectively-final snapshot of what Multiple Run injected, for the monitor's Env viewer
            final EnvironmentVariablesData loadedEnvData = childEnvData;
            RunConfiguration effectiveConfiguration = RunConfigurationHelper.withEnvironmentOverride(runConfiguration, childEnvData);
            if (!saveOutputDir.isEmpty()) {
                final RunConfiguration target = effectiveConfiguration == runConfiguration
                        ? runConfiguration.clone()
                        : effectiveConfiguration;
                if (RunConfigurationHelper.applySaveOutput(target, saveOutputDir, runConfiguration.getName())) {
                    effectiveConfiguration = target;
                }
            }

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

            // The callback needs the environment (crash-restart) and the environment is built with
            // the callback attached (the public API that replaces the deprecated execute(env, callback)).
            // A holder breaks that cycle: it is set right after the environment is built, below.
            final java.util.concurrent.atomic.AtomicReference<ExecutionEnvironment> environmentRef =
                    new java.util.concurrent.atomic.AtomicReference<>();

            // pass the callback to runner.execute(env, callback) instead of the internal
            // ExecutionEnvironment.setCallback - same effect, public API
            final ProgramRunner.Callback multirunCallback =
                    new ProgramRunner.Callback() {
                        private final AtomicBoolean processTerminated = new AtomicBoolean(false);
                        private final AtomicBoolean firstStart = new AtomicBoolean(true);
                        /** crash restarts of this app in this run session (docker restart: on-failure). */
                        private final java.util.concurrent.atomic.AtomicInteger crashRestarts =
                                new java.util.concurrent.atomic.AtomicInteger();

                        @SuppressWarnings("ConstantConditions")
                        @Override
                        public void processStarted(final RunContentDescriptor descriptor) {
                            // false when the app is restarted individually from the monitor tool
                            // window: the callback fires again, but the one-by-one chain must not
                            final boolean initialStart = firstStart.compareAndSet(true, false);
                            if (descriptor == null) {
                                if (initialStart && startOneByOne) {
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
                                    public void onTextAvailable(@NotNull final ProcessEvent processEvent,
                                                                @NotNull final com.intellij.openapi.util.Key outputType) {
                                        // feeds the "log:" readiness condition
                                        if (readyCondition.type == RunConfigurationHelper.ReadyCondition.Type.LOG
                                                && !readyLogSeen.get()
                                                && processEvent.getText() != null
                                                && processEvent.getText().contains(readyCondition.value)) {
                                            readyLogSeen.set(true);
                                        }
                                    }

                                    @Override
                                    public void processTerminated(@NotNull final ProcessEvent processEvent) {
                                        onTermination(processEvent);
                                        processTerminated.set(true);
                                        stopRunningMultirunConfiguration.removeProcess(project, processEvent.getProcessHandler());

                                        // docker "restart: on-failure": intentional stops (0/130/137/143) never restart
                                        if (RunConfigurationHelper.isCrashExit(processEvent.getExitCode())
                                                && !stopRunningMultirunConfiguration.isStopMultirunTriggered()) {
                                            if (restartOnCrash && crashRestarts.incrementAndGet() <= MAX_CRASH_RESTARTS) {
                                                // relaunch automatically, at most MAX_CRASH_RESTARTS times
                                                final int attempt = crashRestarts.get();
                                                com.intellij.notification.NotificationGroupManager.getInstance()
                                                        .getNotificationGroup("Multiple Run")
                                                        .createNotification(
                                                                "Application restarted after crash",
                                                                "'" + runConfiguration.getName() + "' exited with code "
                                                                        + processEvent.getExitCode() + " - restarting (attempt "
                                                                        + attempt + "/" + MAX_CRASH_RESTARTS + ").",
                                                                com.intellij.notification.NotificationType.WARNING)
                                                        .notify(project);
                                                ApplicationManager.getApplication().invokeLater(
                                                        () -> ExecutionUtil.restart(environmentRef.get()));
                                            } else {
                                                // no auto-restart (disabled, or attempts exhausted): surface the
                                                // crash with a one-click Restart action
                                                final com.intellij.notification.Notification notification =
                                                        com.intellij.notification.NotificationGroupManager.getInstance()
                                                                .getNotificationGroup("Multiple Run")
                                                                .createNotification(
                                                                        "Application crashed",
                                                                        "'" + runConfiguration.getName() + "' exited with code "
                                                                                + processEvent.getExitCode() + ".",
                                                                        com.intellij.notification.NotificationType.WARNING);
                                                notification.addAction(
                                                        com.intellij.notification.NotificationAction.createSimpleExpiring(
                                                                "Restart",
                                                                () -> ExecutionUtil.restart(environmentRef.get())));
                                                notification.notify(project);
                                            }
                                        }
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
                            if (processHandler != null) {
                                // feed the "Multiple Run Monitor" tool window with live processes
                                MultirunProcessRegistry.register(project, configurationName,
                                                                 runConfiguration.getName(), processHandler,
                                                                 memoryLimitMb, environmentRef.get(),
                                                                 RunConfigurationHelper.envFileDisplayName(effectiveEnvFilePath),
                                                                 loadedEnvData.getEnvs(), loadedEnvData.isPassParentEnvs(),
                                                                 readyConditions.get(runConfiguration.getName()),
                                                                 memAlertThreshold, memLimitRestart, cpuAlertThreshold);
                            }
                            if (!initialStart) {
                                // individual restart from the monitor: only re-track the new
                                // process, never chain the next configurations again
                                return;
                            }

                            final boolean moreConfigurationsToRun = index + 1 < runConfigurations.size();
                            if (startOneByOne && moreConfigurationsToRun) {
                                // start next configuration..

                                if (readyCondition.type != RunConfigurationHelper.ReadyCondition.Type.NONE) {
                                    // wait until this app is ready (or dies / times out), then chain
                                    ProgressManager.getInstance().run(new Task.Backgroundable(
                                            project, "Waiting for '" + runConfiguration.getName() + "' to be ready") {
                                        @Override
                                        public void run(@NotNull ProgressIndicator progressIndicator) {
                                            progressIndicator.setIndeterminate(true);
                                            progressIndicator.setText(
                                                    "waiting for '" + runConfiguration.getName() + "' (" + readyCondition.value + ")");
                                            final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
                                            try {
                                                while (System.currentTimeMillis() < deadline) {
                                                    if (progressIndicator.isCanceled() || processTerminated.get() || isReady()) {
                                                        break;
                                                    }
                                                    Thread.sleep(500);
                                                }
                                            } catch (InterruptedException ignored) {
                                                return;
                                            }
                                            ApplicationManager.getApplication().executeOnPooledThread(
                                                    () -> runConfigurations(executor, runConfigurations, index + 1));
                                        }

                                        private boolean isReady() {
                                            switch (readyCondition.type) {
                                                case PORT: return RunConfigurationHelper.isPortOpen(readyCondition.port);
                                                case HTTP: return RunConfigurationHelper.isHttpHealthy(readyCondition.value);
                                                case LOG:  return readyLogSeen.get();
                                                default:   return true;
                                            }
                                        }
                                    });
                                } else if (delayTime > 0) {
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
                    };

            // build the environment with the callback attached, then run it - public API replacing
            // the deprecated ProgramRunner.execute(environment, callback)
            final ExecutionEnvironment executionEnvironment = new ExecutionEnvironmentBuilder(project, executor)
                    .runnerAndSettings(runner, configuration)
                    .build(multirunCallback);
            environmentRef.set(executionEnvironment);
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
