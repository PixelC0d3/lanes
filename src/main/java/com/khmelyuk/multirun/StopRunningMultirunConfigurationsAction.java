package com.khmelyuk.multirun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;

import com.intellij.execution.KillableProcess;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * The action to stop the running multirun configurations.
 *
 * @author Ruslan Khmelyuk
 */
public class StopRunningMultirunConfigurationsAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(StopRunningMultirunConfigurationsAction.class);

    /** Processes started by Multirun, grouped by project and by the Multirun configuration that started them. */
    private final ConcurrentHashMap<Project, ConcurrentHashMap<String, List<ProcessHandler>>> processes = new ConcurrentHashMap<>();
    private final AtomicBoolean stopStartingConfigurations = new AtomicBoolean(false);
    private final AtomicInteger startingCounter = new AtomicInteger(0);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override public void update(AnActionEvent e) {
        super.update(e);
        if (e.getProject() == null) return;

        final Presentation presentation = e.getPresentation();
        presentation.setEnabled(startingCounter.get() > 0 || hasNonTerminatedProcesses(e.getProject()));
    }

    private boolean hasNonTerminatedProcesses(Project project) {
        final Map<String, List<ProcessHandler>> byConfiguration = processes.get(project);
        if (byConfiguration == null) return false;

        for (List<ProcessHandler> list : byConfiguration.values()) {
            for (ProcessHandler each : list) {
                if (!each.isProcessTerminated()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void actionPerformed(AnActionEvent e) {
        if (e.getProject() == null) return;

        stopStartingConfigurations.set(true);
        LOG.debug("Asked to stop running multirun configurations.");
        final Map<String, List<ProcessHandler>> byConfiguration = processes.get(e.getProject());
        if (byConfiguration == null || byConfiguration.isEmpty()) {
            LOG.debug("Nothing to stop");
            return;
        }
        int stoppedCount = 0;
        for (List<ProcessHandler> list : byConfiguration.values()) {
            List<ProcessHandler> stoppedProcesses = new ArrayList<>();
            for (ProcessHandler process : list) {
                stop(process);
                stoppedProcesses.add(process);
            }
            list.removeAll(stoppedProcesses);
            stoppedCount += stoppedProcesses.size();
        }

        LOG.debug("Stopped " + stoppedCount + " processes");
    }

    /**
     * Stops the still-running processes started earlier by the given Multirun configuration
     * and returns them, so the caller can wait for their termination. Used by the restart
     * behavior; does not raise the stop flag, so a starting Multirun is not interrupted.
     */
    public List<ProcessHandler> stopProcessesOf(Project project, String configurationName) {
        final Map<String, List<ProcessHandler>> byConfiguration = processes.get(project);
        if (byConfiguration == null) return Collections.emptyList();
        final List<ProcessHandler> list = byConfiguration.get(configurationName);
        if (list == null || list.isEmpty()) return Collections.emptyList();

        final List<ProcessHandler> stopped = new ArrayList<>();
        for (ProcessHandler each : list) {
            if (!each.isProcessTerminated()) {
                stop(each);
                stopped.add(each);
            }
        }
        list.clear();
        LOG.debug("Restart: stopped " + stopped.size() + " processes of '" + configurationName + "'");
        return stopped;
    }

    public void addProcess(Project project, String configurationName, ProcessHandler process) {
        if (process == null) return;

        if (stopStartingConfigurations.get()) {
            stop(process);
            return;
        }
        processes.computeIfAbsent(project, p -> new ConcurrentHashMap<>())
                 .computeIfAbsent(configurationName, k -> new CopyOnWriteArrayList<>())
                 .add(process);
    }

    public void removeProcess(final Project project, final ProcessHandler process) {
        if (process == null) return;

        final Map<String, List<ProcessHandler>> byConfiguration = processes.get(project);
        if (byConfiguration == null) return;
        for (List<ProcessHandler> list : byConfiguration.values()) {
            list.remove(process);
        }
    }

    private void stop(ProcessHandler processHandler) {
        if (processHandler instanceof KillableProcess && processHandler.isProcessTerminating()) {
            ((KillableProcess) processHandler).killProcess();
            return;
        }

        if (processHandler.detachIsDefault()) {
            processHandler.detachProcess();
        } else {
            processHandler.destroyProcess();
        }
    }

    public boolean canContinueStartingConfigurations() {
        return !stopStartingConfigurations.get();
    }

    public boolean isStopMultirunTriggered() {
        return stopStartingConfigurations.get();
    }

    // TODO - move to some component

    public void beginStartingConfigurations() {
        stopStartingConfigurations.set(false);
        startingCounter.incrementAndGet();
    }

    public void doneStaringConfigurations() {
        startingCounter.decrementAndGet();
    }
}
