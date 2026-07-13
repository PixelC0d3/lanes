package io.github.pixelcodes.lanes

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.intellij.execution.KillableProcess
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * The action to stop the running Lanes configurations.
 *
 * @author Ruslan Khmelyuk
 */
class StopRunningLanesConfigurationsAction : AnAction() {

    companion object {
        /**
         * Namespaced action id, unique to this fork. The original Multirun plugin registers its action
         * as "stopRunningMultirunConfiguration"; reusing that id caused an ID collision PluginException
         * on startup when both plugins were installed.
         */
        const val ACTION_ID = "Lanes.StopRunning"

        private val LOG = Logger.getInstance(StopRunningLanesConfigurationsAction::class.java)
    }

    /** Processes started by Lanes, grouped by project and by the configuration that started them. */
    private val processes = ConcurrentHashMap<Project, ConcurrentHashMap<String, MutableList<ProcessHandler>>>()
    private val stopStartingConfigurations = AtomicBoolean(false)
    private val startingCounter = AtomicInteger(0)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.getProject() ?: return

        val presentation = e.getPresentation()
        presentation.setEnabled(startingCounter.get() > 0 || hasNonTerminatedProcesses(project))
    }

    private fun hasNonTerminatedProcesses(project: Project): Boolean {
        val byConfiguration = processes[project] ?: return false

        for (list in byConfiguration.values) {
            for (each in list) {
                if (!each.isProcessTerminated()) {
                    return true
                }
            }
        }
        return false
    }

    override fun actionPerformed(e: AnActionEvent) {
        stopAll(e.getProject())
    }

    /** Stops every process this plugin started for the project; safe to call from the monitor toolbar. */
    fun stopAll(project: Project?) {
        if (project == null) return

        stopStartingConfigurations.set(true)
        LOG.debug("Asked to stop running multirun configurations.")
        val byConfiguration = processes[project]
        if (byConfiguration == null || byConfiguration.isEmpty()) {
            LOG.debug("Nothing to stop")
            return
        }
        var stoppedCount = 0
        for (list in byConfiguration.values) {
            val stoppedProcesses = ArrayList<ProcessHandler>()
            for (process in list) {
                stop(process)
                stoppedProcesses.add(process)
            }
            list.removeAll(stoppedProcesses)
            stoppedCount += stoppedProcesses.size
        }

        LOG.debug("Stopped $stoppedCount processes")
    }

    /**
     * Stops the still-running processes started earlier by the given Lanes configuration
     * and returns them, so the caller can wait for their termination. Used by the restart
     * behavior; does not raise the stop flag, so a starting Lanes is not interrupted.
     */
    fun stopProcessesOf(project: Project, configurationName: String): List<ProcessHandler> {
        val byConfiguration = processes[project] ?: return emptyList()
        val list = byConfiguration[configurationName]
        if (list == null || list.isEmpty()) return emptyList()

        val stopped = ArrayList<ProcessHandler>()
        for (each in list) {
            if (!each.isProcessTerminated()) {
                stop(each)
                stopped.add(each)
            }
        }
        list.clear()
        LOG.debug("Restart: stopped ${stopped.size} processes of '$configurationName'")
        return stopped
    }

    fun addProcess(project: Project, configurationName: String, process: ProcessHandler?) {
        if (process == null) return

        if (stopStartingConfigurations.get()) {
            stop(process)
            return
        }
        processes.computeIfAbsent(project) { ConcurrentHashMap() }
                 .computeIfAbsent(configurationName) { CopyOnWriteArrayList() }
                 .add(process)
    }

    fun removeProcess(project: Project, process: ProcessHandler?) {
        if (process == null) return

        val byConfiguration = processes[project] ?: return
        for (list in byConfiguration.values) {
            list.remove(process)
        }
    }

    private fun stop(processHandler: ProcessHandler) {
        if (processHandler is KillableProcess && processHandler.isProcessTerminating()) {
            processHandler.killProcess()
            return
        }

        if (processHandler.detachIsDefault()) {
            processHandler.detachProcess()
        } else {
            processHandler.destroyProcess()
        }
    }

    fun canContinueStartingConfigurations(): Boolean = !stopStartingConfigurations.get()

    fun isStopLanesTriggered(): Boolean = stopStartingConfigurations.get()

    // TODO - move to some component

    fun beginStartingConfigurations() {
        stopStartingConfigurations.set(false)
        startingCounter.incrementAndGet()
    }

    fun doneStaringConfigurations() {
        startingCounter.decrementAndGet()
    }
}
