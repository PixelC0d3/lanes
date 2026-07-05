package com.khmelyuk.multirun.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.Timer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import com.khmelyuk.multirun.MultirunProcessRegistry;
import com.khmelyuk.multirun.ProcessStatsSampler;
import com.khmelyuk.multirun.StopRunningMultirunConfigurationsAction;

/**
 * The "Multiple Run Monitor" tool window content: a docker-stats-like table with the
 * applications started by Multiple Run and their live memory/CPU usage and listening ports,
 * refreshed automatically every couple of seconds. Rows can be stopped gracefully or
 * force-killed (whole process tree), and any process squatting a TCP port can be killed
 * through the "Kill Process on Port" action.
 */
public class MultirunMonitorPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int REFRESH_INTERVAL_MS = 2000;

    /** Immutable display row; built off the EDT with all texts precomputed. */
    static final class Row {
        final MultirunProcessRegistry.Entry entry;
        final String pid;
        final String ports;
        final String memUsage;
        final String memPercent;
        final String cpuPercent;

        Row(MultirunProcessRegistry.Entry entry, String pid, String ports,
            String memUsage, String memPercent, String cpuPercent) {
            this.entry = entry;
            this.pid = pid;
            this.ports = ports;
            this.memUsage = memUsage;
            this.memPercent = memPercent;
            this.cpuPercent = cpuPercent;
        }
    }

    private final Project project;
    private final ListTableModel<Row> model;
    private final TableView<Row> table;
    private final Timer timer;
    private final AtomicBoolean sampling = new AtomicBoolean();

    public MultirunMonitorPanel(@NotNull Project project) {
        super(false, true);
        this.project = project;

        model = new ListTableModel<>(
                column("Name", 220, row -> row.entry.appName),
                column("Multiple Run", 150, row -> row.entry.multirunName),
                column("PID", 70, row -> row.pid),
                column("Ports", 110, row -> row.ports),
                column("Mem Usage / Limit", 160, row -> row.memUsage),
                column("Mem %", 70, row -> row.memPercent),
                column("CPU %", 70, row -> row.cpuPercent));
        table = new TableView<>(model);
        table.getEmptyText().setText("No applications started by Multiple Run are running");

        final DefaultActionGroup rowActions = new DefaultActionGroup();
        rowActions.add(new StopSelectedAction());
        rowActions.add(new KillSelectedAction());

        final DefaultActionGroup toolbarGroup = new DefaultActionGroup();
        toolbarGroup.add(new DumbAwareAction("Refresh", "Refresh the process list now", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh();
            }
        });
        toolbarGroup.addAll(rowActions);
        toolbarGroup.add(new KillByPortAction());
        toolbarGroup.addSeparator();
        final AnAction stopAllAction = ActionManager.getInstance().getAction(StopRunningMultirunConfigurationsAction.ACTION_ID);
        if (stopAllAction != null) {
            toolbarGroup.add(stopAllAction);
        }
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("MultipleRunMonitor", toolbarGroup, false);
        toolbar.setTargetComponent(table);
        setToolbar(toolbar.getComponent());
        setContent(ScrollPaneFactory.createScrollPane(table));
        PopupHandler.installPopupMenu(table, rowActions, "MultipleRunMonitorPopup");

        timer = new Timer(REFRESH_INTERVAL_MS, e -> {
            // don't burn cycles while the tool window is hidden
            if (isShowing()) {
                refresh();
            }
        });
        timer.start();
        refresh();
    }

    private static ColumnInfo<Row, String> column(String name, int width, java.util.function.Function<Row, String> getter) {
        return new ColumnInfo<Row, String>(name) {
            @Nullable
            @Override
            public String valueOf(Row row) {
                return getter.apply(row);
            }

            @Override
            public int getWidth(javax.swing.JTable table) {
                return "Name".equals(name) ? -1 : width;
            }
        };
    }

    @Nullable
    private Row selectedRow() {
        return table.getSelectedObject();
    }

    /** Graceful stop of the selected application - same as the red stop button of its tab. */
    private final class StopSelectedAction extends DumbAwareAction {
        StopSelectedAction() {
            super("Stop", "Request the selected application to terminate", AllIcons.Actions.Suspend);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedRow() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final Row row = selectedRow();
            if (row != null) {
                row.entry.handler.destroyProcess();
            }
        }
    }

    /** SIGKILL of the selected application and every process it spawned. */
    private final class KillSelectedAction extends DumbAwareAction {
        KillSelectedAction() {
            super("Force Kill", "Forcibly kill the selected application and its whole process tree (SIGKILL)",
                  AllIcons.Debugger.KillProcess);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(selectedRow() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final Row row = selectedRow();
            if (row == null) {
                return;
            }
            final int answer = Messages.showYesNoDialog(
                    project,
                    "Forcibly kill '" + row.entry.appName + "' (PID " + row.pid + ") and all its child processes?\n" +
                    "The application gets no chance to shut down cleanly.",
                    "Force Kill", "Kill", "Cancel", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                // resolve the tree fresh - children may have been spawned after the last refresh
                final Set<Long> treePids =
                        ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(row.entry.handler));
                for (Long pid : treePids) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                // tell the IDE the process is gone, so the run tab stops its spinner too
                row.entry.handler.destroyProcess();
                ApplicationManager.getApplication().invokeLater(MultirunMonitorPanel.this::refresh);
            });
        }
    }

    /** Kills whatever is listening on a TCP port - Multiple Run's or not (the EADDRINUSE classic). */
    private final class KillByPortAction extends DumbAwareAction {
        KillByPortAction() {
            super("Kill Process on Port...", "Find the process listening on a TCP port and kill it",
                  AllIcons.General.Web);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final String input = Messages.showInputDialog(
                    project, "TCP port:", "Kill Process on Port", Messages.getQuestionIcon());
            if (input == null || input.trim().isEmpty()) {
                return;
            }
            final int port;
            try {
                port = Integer.parseInt(input.trim());
            } catch (NumberFormatException ex) {
                Messages.showErrorDialog(project, "'" + input + "' is not a valid port number.", "Kill Process on Port");
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                final List<Long> pids = ProcessStatsSampler.pidsListeningOnPort(port);
                ApplicationManager.getApplication().invokeLater(() -> confirmAndKill(port, pids));
            });
        }

        private void confirmAndKill(int port, List<Long> pids) {
            if (pids.isEmpty()) {
                Messages.showInfoMessage(project,
                                         "No process is listening on port " + port + " (or lsof is not available).",
                                         "Kill Process on Port");
                return;
            }
            final String processList = pids.stream()
                    .map(pid -> "  PID " + pid + " - " + ProcessHandle.of(pid)
                            .flatMap(handle -> handle.info().command())
                            .orElse("unknown command"))
                    .collect(Collectors.joining("\n"));
            final int answer = Messages.showYesNoDialog(
                    project,
                    "Kill the process(es) listening on port " + port + "?\n\n" + processList,
                    "Kill Process on Port", "Kill", "Cancel", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                for (Long pid : pids) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                ApplicationManager.getApplication().invokeLater(MultirunMonitorPanel.this::refresh);
            });
        }
    }

    private void refresh() {
        if (!sampling.compareAndSet(false, true)) {
            return;
        }
        final List<MultirunProcessRegistry.Entry> entries = MultirunProcessRegistry.getEntries(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                final List<Row> rows = buildRows(entries);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        model.setItems(rows);
                    }
                });
            } finally {
                sampling.set(false);
            }
        });
    }

    /** Builds the display rows; runs on a pooled thread (process tree walk + one ps and one lsof call). */
    private static List<Row> buildRows(List<MultirunProcessRegistry.Entry> entries) {
        final long hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb();

        // resolve the process tree of every entry first, then sample everything in single ps/lsof calls
        final Map<MultirunProcessRegistry.Entry, Set<Long>> treeByEntry = new LinkedHashMap<>();
        final Set<Long> allPids = new LinkedHashSet<>();
        for (MultirunProcessRegistry.Entry entry : entries) {
            final Set<Long> treePids = ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler));
            treeByEntry.put(entry, treePids);
            allPids.addAll(treePids);
        }
        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);
        final Map<Long, Set<Integer>> portsByPid = ProcessStatsSampler.sampleListeningPorts(allPids);

        final List<Row> rows = new ArrayList<>(entries.size());
        for (MultirunProcessRegistry.Entry entry : entries) {
            final Set<Long> treePids = treeByEntry.get(entry);
            final long rootPid = treePids.isEmpty() ? -1 : treePids.iterator().next();
            final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(statsByPid, treePids);

            final Set<Integer> treePorts = new java.util.TreeSet<>();
            for (Long pid : treePids) {
                final Set<Integer> ports = portsByPid.get(pid);
                if (ports != null) {
                    treePorts.addAll(ports);
                }
            }
            final String portsText = treePorts.isEmpty()
                    ? "-" : treePorts.stream().map(String::valueOf).collect(Collectors.joining(", "));

            final String limitText = entry.memoryLimitMb != null
                    ? ProcessStatsSampler.formatMemory(entry.memoryLimitMb * 1024L)
                    : ProcessStatsSampler.formatMemory(hostTotalKb);
            final String memUsage;
            final String memPercent;
            final String cpuPercent;
            if (stats != null) {
                memUsage = ProcessStatsSampler.formatMemory(stats.rssKb) + " / " + limitText;
                final double percent = ProcessStatsSampler.memoryPercent(stats.rssKb, entry.memoryLimitMb, hostTotalKb);
                memPercent = percent < 0 ? "n/a" : String.format(Locale.US, "%.2f%%", percent);
                cpuPercent = String.format(Locale.US, "%.2f%%", stats.cpuPercent);
            } else {
                memUsage = "n/a / " + limitText;
                memPercent = "n/a";
                cpuPercent = "n/a";
            }
            rows.add(new Row(entry, rootPid > 0 ? String.valueOf(rootPid) : "n/a",
                             portsText, memUsage, memPercent, cpuPercent));
        }
        return rows;
    }

    @Override
    public void dispose() {
        timer.stop();
    }
}
