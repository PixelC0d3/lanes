package io.github.pixelcodes.lanes

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.impl.RunDialog
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.LayeredIcon
import com.intellij.ui.content.Content

/**
 * @author Ruslan Khmelyuk
 */
class LanesRunnerState(
    private val runConfigurations: List<RunConfiguration>,
    private val startOneByOne: Boolean,
    private val delayTime: Double,
    private val reuseTabs: Boolean,
    private val reuseTabsWithFailure: Boolean,
    private val markFailedProcess: Boolean,
    private val hideSuccessProcess: Boolean,
    envData: EnvironmentVariablesData?,
    envFilePath: String?,
    saveOutputDir: String?,
    memoryLimits: Map<String, Int>?,
    readyConditions: Map<String, String>?,
    appEnvFiles: Map<String, String>?,
    private val restartRunning: Boolean,
    private val restartOnCrash: Boolean,
    private val memAlertThreshold: Int,
    private val memLimitRestart: Boolean,
    private val cpuAlertThreshold: Int,
    private val project: Project,
    private val configurationName: String,
    /** When this group runs nested, the top-level group the user started; null when it IS top-level. */
    private val rootGroupName: String? = null,
    /** When this group runs nested, the top-level group's env profile path; null when top-level. */
    private val rootEnvFilePath: String? = null,
) : RunProfileState {

    private val envData: EnvironmentVariablesData = envData ?: EnvironmentVariablesData.DEFAULT
    private val envFilePath: String = envFilePath ?: ""

    /** resolved here once: relative folders behave like the env file (project-root based) */
    private val saveOutputDir: String =
        if (saveOutputDir.isNullOrBlank()) ""
        else RunConfigurationHelper.resolveEnvFile(saveOutputDir, project).getPath()
    private val memoryLimits: Map<String, Int> = memoryLimits ?: emptyMap()
    private val readyConditions: Map<String, String> = readyConditions ?: emptyMap()

    /** Per-app env file overriding the group environment for that app, keyed by app name. */
    private val appEnvFiles: Map<String, String> = appEnvFiles ?: emptyMap()

    private val stopRunningLanesConfiguration: StopRunningLanesConfigurationsAction =
        ActionManager.getInstance()
            .getAction(StopRunningLanesConfigurationsAction.ACTION_ID) as StopRunningLanesConfigurationsAction

    /** envData with the env file applied under it; recomputed on every run so file edits are picked up. */
    @Volatile
    private var effectiveEnvData: EnvironmentVariablesData = this.envData

    override fun execute(executor: Executor, programRunner: ProgramRunner<*>): ExecutionResult? {
        stopRunningLanesConfiguration.beginStartingConfigurations()
        ApplicationManager.getApplication().executeOnPooledThread {
            // read the env file fresh on every run (file IO, so off the EDT)
            effectiveEnvData = RunConfigurationHelper.withEnvFile(envData, envFilePath, project)
            if (restartRunning) {
                // like the built-in Compound configuration: stop what this Lanes started
                // before and only then start again, so ports/resources are released
                waitForTermination(stopRunningLanesConfiguration.stopProcessesOf(project, configurationName))
            }
            runConfigurations(executor, runConfigurations, 0)
        }

        return null
    }

    private fun runConfigurations(executor: Executor, runConfigurations: List<RunConfiguration>, index: Int) {
        if (index >= runConfigurations.size) {
            stopRunningLanesConfiguration.doneStaringConfigurations()
            return
        }
        if (!stopRunningLanesConfiguration.canContinueStartingConfigurations()) {
            stopRunningLanesConfiguration.doneStaringConfigurations()
            // don't start more configurations if user stopped the plugin work.
            return
        }

        val runConfiguration = runConfigurations[index]
        val project = runConfiguration.getProject()

        // docker-compose-like readiness gate: with a condition set, the next configuration
        // only starts after this one is ready (port open / log text seen / http healthy)
        val readyCondition = RunConfigurationHelper.parseReadyCondition(readyConditions[runConfiguration.getName()])
        val readyLogSeen = AtomicBoolean(false)

        var started = false
        try {
            // apply the Lanes environment variables on top of the child configuration; works on a clone,
            // so the user's configuration is never permanently modified
            var childEnvData = effectiveEnvData
            val memoryLimitMb = memoryLimits[runConfiguration.getName()]
            if (memoryLimitMb != null && memoryLimitMb > 0) {
                // per-application heap cap (~docker mem_limit) via NODE_OPTIONS/JAVA_TOOL_OPTIONS
                childEnvData = RunConfigurationHelper.withMemoryLimit(childEnvData, memoryLimitMb)
            }
            // per-application env file: overrides the group environment for this app (more specific)
            val appEnvFile = appEnvFiles[runConfiguration.getName()]
            childEnvData = RunConfigurationHelper.withAppEnvFile(childEnvData, appEnvFile, project)
            // the env file actually in effect for THIS app - its per-app override when set, else the
            // group profile - so the monitor's Env column shows the app's real environment, not just
            // the group's (matters after a per-app switch from the monitor).
            val effectiveEnvFilePath = if (!appEnvFile.isNullOrBlank()) appEnvFile else envFilePath
            // effectively-final snapshot of what Lanes injected, for the monitor's Env viewer
            val loadedEnvData = childEnvData
            var effectiveConfiguration = RunConfigurationHelper.withEnvironmentOverride(runConfiguration, childEnvData)
            if (runConfiguration is LanesRunConfiguration) {
                // a nested Lanes group: tell its clone the top-level group + env the user actually
                // started, so its own apps register (and show in the monitor) under that group and
                // env - not this nested config's own name/profile. Always a clone, never the original.
                val nestedClone = if (effectiveConfiguration === runConfiguration)
                    runConfiguration.clone() as LanesRunConfiguration
                else effectiveConfiguration as LanesRunConfiguration
                nestedClone.setRootGroupContext(rootGroupName ?: configurationName, rootEnvFilePath ?: envFilePath)
                effectiveConfiguration = nestedClone
            }
            if (saveOutputDir.isNotEmpty()) {
                val target = if (effectiveConfiguration === runConfiguration) runConfiguration.clone() else effectiveConfiguration
                if (RunConfigurationHelper.applySaveOutput(target, saveOutputDir, runConfiguration.getName())) {
                    effectiveConfiguration = target
                }
            }

            val configuration: RunnerAndConfigurationSettings? =
                if (effectiveConfiguration === runConfiguration) {
                    // configuration may not be registered anymore (e.g. it was removed) - skip it;
                    // the finally block still chains to the next configuration.
                    RunManager.getInstance(project).findSettings(runConfiguration)
                } else {
                    // a clone is not registered in the RunManager, wrap it in fresh settings
                    RunManager.getInstance(project).createConfiguration(effectiveConfiguration, effectiveConfiguration.getFactory()!!)
                }
            if (configuration == null) { return }

            val runner = ProgramRunner.getRunner(executor.getId(), effectiveConfiguration) ?: return
            if (!checkRunConfiguration(executor, project, configuration)) { return }

            // The callback needs the environment (crash-restart) and the environment is built with
            // the callback attached (the public API that replaces the deprecated execute(env, callback)).
            // A holder breaks that cycle: it is set right after the environment is built, below.
            val environmentRef = AtomicReference<ExecutionEnvironment>()

            // pass the callback to runner.execute(env, callback) instead of the internal
            // ExecutionEnvironment.setCallback - same effect, public API
            val lanesCallback = object : ProgramRunner.Callback {
                private val processTerminated = AtomicBoolean(false)
                private val firstStart = AtomicBoolean(true)

                /** crash restarts of this app in this run session (docker restart: on-failure). */
                private val crashRestarts = AtomicInteger()

                override fun processStarted(descriptor: RunContentDescriptor?) {
                    // false when the app is restarted individually from the monitor tool
                    // window: the callback fires again, but the one-by-one chain must not
                    val initialStart = firstStart.compareAndSet(true, false)
                    if (descriptor == null) {
                        if (initialStart && startOneByOne) {
                            // start next configuration..
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runConfigurations(executor, runConfigurations, index + 1)
                            }
                        }
                        return
                    }

                    val processHandler = descriptor.getProcessHandler()
                    if (processHandler != null) {
                        processHandler.addProcessListener(object : ProcessListener {
                            /** Set when stderr shows Node rejected a NODE_OPTIONS flag - see [onTermination]. */
                            private val nodeOptionsRejected = AtomicBoolean(false)

                            override fun startNotified(processEvent: ProcessEvent) {
                                val content = descriptor.getAttachedContent() ?: return

                                val canContinue = stopRunningLanesConfiguration.canContinueStartingConfigurations()
                                if (!canContinue) {
                                    // Lanes was stopped - destroy processes that are still starting up
                                    processHandler.destroyProcess()
                                }

                                // All Content (tab) mutations must run on the EDT.
                                ApplicationManager.getApplication().invokeLater {
                                    content.setIcon(descriptor.getIcon())
                                    if (!canContinue) {
                                        if (!content.isPinned() && !startOneByOne) {
                                            // checks if not pinned, to avoid destroying already existed tab
                                            // checks if start one by one - no need to close the console tab, as it's won't be shown
                                            // as other checks disallow starting it

                                            // content.getManager() can be null, if content is removed already as part of destroy above
                                            val manager = content.getManager()
                                            if (manager != null) {
                                                manager.removeContent(content, false)
                                            }
                                        }
                                    } else {
                                        // ensure tab is not pinned
                                        content.setPinned(false)

                                        // mark running process tab with *
                                        content.setDisplayName(descriptor.getDisplayName() + "*")
                                    }
                                }
                            }

                            override fun onTextAvailable(processEvent: ProcessEvent, outputType: Key<*>) {
                                // feeds the "log:" readiness condition
                                val text = processEvent.getText()
                                if (readyCondition.type == RunConfigurationHelper.ReadyCondition.Type.LOG
                                    && !readyLogSeen.get()
                                    && text != null
                                    && text.contains(readyCondition.value)) {
                                    readyLogSeen.set(true)
                                }
                                if (text != null && RunConfigurationHelper.isNodeOptionsRejection(text)) {
                                    nodeOptionsRejected.set(true)
                                }
                            }

                            override fun processTerminated(processEvent: ProcessEvent) {
                                onTermination(processEvent)
                                processTerminated.set(true)
                                stopRunningLanesConfiguration.removeProcess(project, processEvent.getProcessHandler())

                                // docker "restart: on-failure": intentional stops (0/130/137/143) never restart
                                if (RunConfigurationHelper.isCrashExit(processEvent.getExitCode())
                                    && !stopRunningLanesConfiguration.isStopLanesTriggered()) {
                                    // Node rejected a flag in NODE_OPTIONS - very likely this app's own
                                    // Memory limit (which sets NODE_OPTIONS) combined with its start
                                    // script reassigning NODE_OPTIONS without `export` (bash keeps an
                                    // already-exported variable exported across such a reassignment).
                                    val nodeOptionsHint = if (nodeOptionsRejected.get() && memoryLimitMb != null && memoryLimitMb > 0) {
                                        " Node rejected a flag in NODE_OPTIONS - this app's Memory limit " +
                                            "($memoryLimitMb MB) sets NODE_OPTIONS, and its start script likely " +
                                            "reassigns NODE_OPTIONS itself without \"export\", which keeps it " +
                                            "exported. Check the script for a flag Node disallows there (e.g. " +
                                            "--trace-gc), or use \"export NODE_OPTIONS=...\" / cross-env instead."
                                    } else {
                                        ""
                                    }
                                    if (restartOnCrash && crashRestarts.incrementAndGet() <= MAX_CRASH_RESTARTS) {
                                        // relaunch automatically, at most MAX_CRASH_RESTARTS times
                                        val attempt = crashRestarts.get()
                                        NotificationGroupManager.getInstance()
                                            .getNotificationGroup("Lanes")
                                            .createNotification(
                                                "Application restarted after crash",
                                                "'${runConfiguration.getName()}' exited with code " +
                                                    "${processEvent.getExitCode()} - restarting (attempt " +
                                                    "$attempt/$MAX_CRASH_RESTARTS).$nodeOptionsHint",
                                                NotificationType.WARNING)
                                            .notify(project)
                                        ApplicationManager.getApplication().invokeLater {
                                            ExecutionUtil.restart(environmentRef.get())
                                        }
                                    } else {
                                        // no auto-restart (disabled, or attempts exhausted): surface the
                                        // crash with a one-click Restart action
                                        val notification = NotificationGroupManager.getInstance()
                                            .getNotificationGroup("Lanes")
                                            .createNotification(
                                                "Application crashed",
                                                "'${runConfiguration.getName()}' exited with code ${processEvent.getExitCode()}.$nodeOptionsHint",
                                                NotificationType.WARNING)
                                        notification.addAction(
                                            NotificationAction.createSimpleExpiring("Restart") {
                                                ExecutionUtil.restart(environmentRef.get())
                                            })
                                        notification.notify(project)
                                    }
                                }
                            }

                            private fun onTermination(processEvent: ProcessEvent) {
                                val content = descriptor.getAttachedContent() ?: return

                                // exit code is 0 if the process completed successfully
                                val completedSuccessfully = processEvent.getExitCode() == 0

                                if (hideSuccessProcess && completedSuccessfully) {
                                    // close the tab for the success process and exit - nothing else could be done
                                    ApplicationManager.getApplication().invokeLater {
                                        val manager = content.getManager()
                                        if (manager != null) {
                                            manager.removeContent(content, false)
                                        }
                                    }
                                    return
                                }

                                // attempt to pin tab if not completed successfully or asked not to reuse tabs
                                val pinTab = (completedSuccessfully && !reuseTabs) || (!completedSuccessfully && !reuseTabsWithFailure)
                                // add the alert icon in case if process exited with non-0 status
                                val markFailed = markFailedProcess && !completedSuccessfully

                                // All Content (tab) mutations must run on the EDT.
                                ApplicationManager.getApplication().invokeLater {
                                    if (pinTab && !stopRunningLanesConfiguration.isStopLanesTriggered()) {
                                        // ... do not pin if Lanes was stopped by the "Stop Lanes" action.
                                        content.setPinned(true)
                                    }

                                    // remove the * used to identify running process
                                    content.setDisplayName(descriptor.getDisplayName())

                                    if (markFailed) {
                                        content.setIcon(LayeredIcon.create(content.getIcon(), AllIcons.Nodes.TabAlert))
                                    }
                                }
                            }
                        })
                    }
                    stopRunningLanesConfiguration.addProcess(project, configurationName, processHandler)
                    if (processHandler != null) {
                        // feed the "Lanes Monitor" tool window with live processes
                        LanesProcessRegistry.register(project, configurationName,
                                                         runConfiguration.getName(), processHandler,
                                                         memoryLimitMb, environmentRef.get(),
                                                         RunConfigurationHelper.envFileDisplayName(effectiveEnvFilePath),
                                                         loadedEnvData.getEnvs(), loadedEnvData.isPassParentEnvs(),
                                                         readyConditions[runConfiguration.getName()],
                                                         memAlertThreshold, memLimitRestart, cpuAlertThreshold,
                                                         rootGroupName,
                                                         rootEnvFilePath?.let { RunConfigurationHelper.envFileDisplayName(it) })
                    }
                    if (!initialStart) {
                        // individual restart from the monitor: only re-track the new
                        // process, never chain the next configurations again
                        return
                    }

                    val moreConfigurationsToRun = index + 1 < runConfigurations.size
                    if (startOneByOne && moreConfigurationsToRun) {
                        // start next configuration..

                        if (readyCondition.type != RunConfigurationHelper.ReadyCondition.Type.NONE) {
                            // wait until this app is ready (or dies / times out), then chain
                            ProgressManager.getInstance().run(object : Task.Backgroundable(
                                project, "Waiting for '${runConfiguration.getName()}' to be ready") {
                                override fun run(progressIndicator: ProgressIndicator) {
                                    progressIndicator.setIndeterminate(true)
                                    progressIndicator.setText(
                                        "waiting for '${runConfiguration.getName()}' (${readyCondition.value})")
                                    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
                                    try {
                                        while (System.currentTimeMillis() < deadline) {
                                            if (progressIndicator.isCanceled() || processTerminated.get() || isReady()) {
                                                break
                                            }
                                            Thread.sleep(500)
                                        }
                                    } catch (ignored: InterruptedException) {
                                        return
                                    }
                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        runConfigurations(executor, runConfigurations, index + 1)
                                    }
                                }

                                private fun isReady(): Boolean {
                                    return when (readyCondition.type) {
                                        RunConfigurationHelper.ReadyCondition.Type.PORT ->
                                            RunConfigurationHelper.isPortOpen(readyCondition.port)
                                        RunConfigurationHelper.ReadyCondition.Type.HTTP ->
                                            RunConfigurationHelper.isHttpHealthy(readyCondition.value)
                                        RunConfigurationHelper.ReadyCondition.Type.LOG -> readyLogSeen.get()
                                        else -> true
                                    }
                                }
                            })
                        } else if (delayTime > 0) {
                            val start = System.currentTimeMillis()
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Waiting for delay") {
                                override fun run(progressIndicator: ProgressIndicator) {
                                    try {
                                        progressIndicator.setIndeterminate(false)
                                        while (System.currentTimeMillis() - start < delayTime * 1000) {
                                            if (processTerminated.get()) {
                                                break
                                            }
                                            if (progressIndicator.isCanceled()) {
                                                return
                                            }
                                            val passed = (System.currentTimeMillis() - start).toDouble() / 1000
                                            val seconds = if (delayTime - passed == 1.0) "second" else "seconds"
                                            progressIndicator.setFraction(passed / delayTime)
                                            val waitingPeriod = String.format("%.1f", delayTime - passed)
                                            progressIndicator.setText("waiting $waitingPeriod $seconds")
                                            Thread.sleep(100)
                                        }
                                    } catch (ignored: InterruptedException) {
                                        return
                                    }
                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        runConfigurations(executor, runConfigurations, index + 1)
                                    }
                                }
                            })
                        } else if (delayTime < 0) {
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Waiting for process to complete") {
                                override fun run(progressIndicator: ProgressIndicator) {
                                    try {
                                        while (!processTerminated.get()) {
                                            if (progressIndicator.isCanceled()) {
                                                return
                                            }
                                            Thread.sleep(200)
                                        }
                                    } catch (ignored: InterruptedException) {
                                        return
                                    }
                                    ApplicationManager.getApplication().executeOnPooledThread {
                                        runConfigurations(executor, runConfigurations, index + 1)
                                    }
                                }
                            })
                        } else {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runConfigurations(executor, runConfigurations, index + 1)
                            }
                        }
                    } else {
                        stopRunningLanesConfiguration.doneStaringConfigurations()
                    }
                }
            }

            // build the environment with the callback attached, then run it - public API replacing
            // the deprecated ProgramRunner.execute(environment, callback)
            val executionEnvironment = ExecutionEnvironmentBuilder(project, executor)
                .runnerAndSettings(runner, configuration)
                .build(lanesCallback)
            environmentRef.set(executionEnvironment)
            ApplicationManager.getApplication().invokeLater {
                try {
                    runner.execute(executionEnvironment)
                } catch (e: ExecutionException) {
                    ExecutionUtil.handleExecutionError(project, executor.getToolWindowId(), configuration.getConfiguration(), e)
                }
            }
            started = true
        } finally {
            // start the next one
            if (!startOneByOne) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    runConfigurations(executor, runConfigurations, index + 1)
                }
            } else if (!started) {
                // failed to start current, means the chain is broken
                ApplicationManager.getApplication().executeOnPooledThread {
                    runConfigurations(executor, runConfigurations, index + 1)
                }
            }
        }
    }

    private fun checkRunConfiguration(executor: Executor, project: Project, configuration: RunnerAndConfigurationSettings): Boolean {
        val target = ExecutionTargetManager.getActiveTarget(project)

        if (!ExecutionTargetManager.canRun(configuration.getConfiguration(), target)) {
            ExecutionUtil.handleExecutionError(
                project, executor.getToolWindowId(), configuration.getConfiguration(),
                ExecutionException(StringUtil.escapeXmlEntities(
                    "Cannot run '${configuration.getName()}' on '${target.getDisplayName()}'")))
            return false
        }

        if (!RunManagerImpl.canRunConfiguration(configuration, executor) || configuration.isEditBeforeRun()) {
            // RunDialog.editConfiguration/Messages.showYesNoDialog are modal Swing dialogs and
            // require the EDT - but checkRunConfiguration also runs from background pooled
            // threads (the one-by-one delay/wait chaining in runConfigurations() above calls back
            // in via executeOnPooledThread). invokeAndWait blocks whichever thread called this
            // method until the dialog logic finishes on the EDT (it runs immediately, inline, if
            // already called from the EDT).
            var ok = true
            ApplicationManager.getApplication().invokeAndWait {
                if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                    ok = false
                    return@invokeAndWait
                }

                while (!RunManagerImpl.canRunConfiguration(configuration, executor)) {
                    if (Messages.showYesNoDialog(project, "Configuration is still incorrect. Do you want to edit it again?",
                                                 "Change Configuration Settings",
                                                 "Edit", "Continue Anyway", Messages.getErrorIcon()) == 0) {
                        if (!RunDialog.editConfiguration(project, configuration, "Edit Configuration", executor)) {
                            break
                        }
                    } else {
                        break
                    }
                }
            }
            if (!ok) {
                return false
            }
        }
        return true
    }

    companion object {
        /** How long to wait for the previously running processes to die before starting again. */
        private const val RESTART_TERMINATION_TIMEOUT_MS = 10_000L

        /** Cap for a "Ready when" wait; when reached the next configuration starts anyway. */
        private const val READY_TIMEOUT_MS = 120_000L

        /** Max automatic relaunches of a crashed app per run session (docker restart: on-failure). */
        private const val MAX_CRASH_RESTARTS = 3

        private fun waitForTermination(handlers: List<ProcessHandler>) {
            val deadline = System.currentTimeMillis() + RESTART_TERMINATION_TIMEOUT_MS
            for (handler in handlers) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    break
                }
                handler.waitFor(remaining)
            }
        }
    }
}
