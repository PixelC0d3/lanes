package io.github.welingtonmonteiro.multiplerun.ui;

import java.util.ArrayList;
import java.util.HashMap;
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

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
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
import io.github.welingtonmonteiro.multiplerun.MultirunProcessRegistry;
import io.github.welingtonmonteiro.multiplerun.ProcessStatsSampler;
import io.github.welingtonmonteiro.multiplerun.RunConfigurationHelper;
import io.github.welingtonmonteiro.multiplerun.StopRunningMultirunConfigurationsAction;

/**
 * The "Multiple Run Monitor" tool window content: a docker-stats-like table with EVERY process
 * the IDE is running - the applications started by Multiple Run and standalone (singleton) runs
 * alike. The first column shows where the app came from: the Multiple Run icon for grouped apps,
 * the run configuration's own icon (node, npm, jest, ...) for standalone ones. Live memory/CPU,
 * listening ports, uptime and health are refreshed every couple of seconds; rows can be
 * restarted, stopped or force-killed, and any process squatting a TCP port can be killed
 * through the "Kill Process on Port" action. Columns are resizable by dragging their headers.
 */
public class MultirunMonitorPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int REFRESH_INTERVAL_MS = 2000;

    /** A process the IDE is running, captured on the EDT (descriptor access) for the refresh. */
    private static final class ProcessSnapshot {
        final String name;
        final javax.swing.Icon icon;
        final ProcessHandler handler;
        final RunContentDescriptor descriptor;

        ProcessSnapshot(String name, javax.swing.Icon icon, ProcessHandler handler, RunContentDescriptor descriptor) {
            this.name = name;
            this.icon = icon;
            this.handler = handler;
            this.descriptor = descriptor;
        }
    }

    /** Immutable display row; built off the EDT with all texts precomputed. */
    static final class Row {
        final String name;
        final javax.swing.Icon icon;
        final String multirunName;
        final String envFileName;
        final ProcessHandler handler;
        final RunContentDescriptor descriptor;
        /** Multirun metadata (limit/condition/...) or null for plain standalone runs. */
        final MultirunProcessRegistry.Entry meta;
        final String pid;
        final String ports;
        final String uptime;
        final String status;
        final String memUsage;
        final String memPercent;
        final String cpuPercent;
        final double[] memTrend;

        Row(String name, javax.swing.Icon icon, String multirunName, String envFileName,
            ProcessHandler handler, RunContentDescriptor descriptor, MultirunProcessRegistry.Entry meta,
            String pid, String ports, String uptime, String status,
            String memUsage, String memPercent, String cpuPercent, double[] memTrend) {
            this.name = name;
            this.icon = icon;
            this.multirunName = multirunName;
            this.envFileName = envFileName;
            this.handler = handler;
            this.descriptor = descriptor;
            this.meta = meta;
            this.pid = pid;
            this.ports = ports;
            this.uptime = uptime;
            this.status = status;
            this.memUsage = memUsage;
            this.memPercent = memPercent;
            this.cpuPercent = cpuPercent;
            this.memTrend = memTrend;
        }
    }

    private final Project project;
    private final ListTableModel<Row> model;
    private final TableView<Row> table;
    private final Timer timer;
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final javax.swing.Icon multirunIcon;

    /** CPU time per pid at the previous sample - the baseline for the docker-style CPU %. */
    private Map<Long, Double> prevCpuSecondsByPid = java.util.Collections.emptyMap();
    private long prevSampleNanos;

    /** Recent memory-percent samples per process, feeding the "Mem trend" sparkline column. */
    private static final int TREND_SAMPLES = 30;
    private final Map<ProcessHandler, java.util.ArrayDeque<Double>> memHistory = new HashMap<>();
    private final SparklineCellRenderer sparklineRenderer = new SparklineCellRenderer();

    public MultirunMonitorPanel(@NotNull Project project) {
        super(false, true);
        this.project = project;

        javax.swing.Icon pluginIcon;
        try {
            pluginIcon = com.intellij.execution.configurations.ConfigurationTypeUtil
                    .findConfigurationType(io.github.welingtonmonteiro.multiplerun.MultirunConfigurationType.class).getIcon();
        } catch (Throwable t) {
            pluginIcon = AllIcons.RunConfigurations.Compound;
        }
        multirunIcon = pluginIcon;

        model = new ListTableModel<>(
                new ColumnInfo<Row, String>("Name") {
                    @Nullable
                    @Override
                    public String valueOf(Row row) {
                        return row.name;
                    }

                    @Override
                    public javax.swing.table.TableCellRenderer getRenderer(Row row) {
                        return new javax.swing.table.DefaultTableCellRenderer() {
                            @Override
                            public java.awt.Component getTableCellRendererComponent(
                                    javax.swing.JTable table, Object value, boolean isSelected,
                                    boolean hasFocus, int rowIndex, int column) {
                                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, rowIndex, column);
                                // multirun icon for grouped apps, the app's own icon for standalone runs
                                setIcon(row.icon);
                                return this;
                            }
                        };
                    }
                },
                column("Multiple Run", row -> row.multirunName),
                column("Env", row -> row.envFileName),
                column("PID", row -> row.pid),
                column("Ports", row -> row.ports),
                column("Uptime", row -> row.uptime),
                column("Status", row -> row.status),
                column("Mem Usage / Limit", row -> row.memUsage),
                column("Mem %", row -> row.memPercent),
                new ColumnInfo<Row, double[]>("Mem trend") {
                    @Nullable
                    @Override
                    public double[] valueOf(Row row) {
                        return row.memTrend;
                    }

                    @Override
                    public javax.swing.table.TableCellRenderer getRenderer(Row row) {
                        return sparklineRenderer;
                    }
                },
                column("CPU %", row -> row.cpuPercent));
        table = new TableView<>(model);
        table.getEmptyText().setText("No run configurations are running");
        // initial widths only - all columns stay resizable by dragging the header edges
        final int[] preferredWidths = {220, 110, 90, 70, 100, 80, 80, 160, 70, 120, 70};
        for (int i = 0; i < preferredWidths.length && i < table.getColumnModel().getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(preferredWidths[i]);
        }

        final DefaultActionGroup rowActions = new DefaultActionGroup();
        rowActions.add(new RestartSelectedAction());
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
        // double click on a row jumps to the console tab of that application
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    focusRunTabOfSelectedRow();
                }
            }
        });

        timer = new Timer(REFRESH_INTERVAL_MS, e -> {
            // don't burn cycles while the tool window is hidden
            if (isShowing()) {
                refresh();
            }
        });
        timer.start();
        refresh();
    }

    private static ColumnInfo<Row, String> column(String name, java.util.function.Function<Row, String> getter) {
        return new ColumnInfo<Row, String>(name) {
            @Nullable
            @Override
            public String valueOf(Row row) {
                return getter.apply(row);
            }
        };
    }

    @Nullable
    private Row selectedRow() {
        return table.getSelectedObject();
    }

    /** Brings the console tab of the selected application to front (Run or Debug tool window). */
    private void focusRunTabOfSelectedRow() {
        final Row row = selectedRow();
        if (row == null || row.descriptor == null) {
            return;
        }
        final com.intellij.ui.content.Content content = row.descriptor.getAttachedContent();
        if (content != null && content.getManager() != null) {
            content.getManager().setSelectedContent(content);
        }
        final com.intellij.openapi.wm.ToolWindow toolWindow =
                RunContentManager.getInstance(project).getToolWindowByDescriptor(row.descriptor);
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    /** Restarts only the selected application; everything else keeps running. */
    private final class RestartSelectedAction extends DumbAwareAction {
        RestartSelectedAction() {
            super("Restart", "Stop the selected application and start it again (everything else keeps running)",
                  AllIcons.Actions.Restart);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            final Row row = selectedRow();
            e.getPresentation().setEnabled(row != null && row.descriptor != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final Row row = selectedRow();
            if (row != null && row.descriptor != null) {
                // the platform stops the old process and reruns the same environment; the new
                // process shows up again on the next refresh (all IDE processes are listed)
                ExecutionUtil.restart(row.descriptor);
            }
        }
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
                row.handler.destroyProcess();
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
                    "Forcibly kill '" + row.name + "' (PID " + row.pid + ") and all its child processes?\n" +
                    "The application gets no chance to shut down cleanly.",
                    "Force Kill", "Kill", "Cancel", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                // resolve the tree fresh - children may have been spawned after the last refresh
                final Set<Long> treePids =
                        ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(row.handler));
                for (Long pid : treePids) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                // tell the IDE the process is gone, so the run tab stops its spinner too
                row.handler.destroyProcess();
                ApplicationManager.getApplication().invokeLater(MultirunMonitorPanel.this::refresh);
            });
        }
    }

    /** Kills whatever is listening on a TCP port - started by the IDE or not (the EADDRINUSE classic). */
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
        // descriptors must be collected on the EDT; heavy sampling then runs pooled
        final List<ProcessSnapshot> snapshots = new ArrayList<>();
        for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
            final ProcessHandler handler = descriptor.getProcessHandler();
            if (handler != null && !handler.isProcessTerminated()) {
                snapshots.add(new ProcessSnapshot(descriptor.getDisplayName(), descriptor.getIcon(),
                                                  handler, descriptor));
            }
        }
        final List<MultirunProcessRegistry.Entry> entries = MultirunProcessRegistry.getEntries(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                final List<Row> rows = buildRows(snapshots, entries);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        setItemsKeepingSelection(rows);
                    }
                });
            } finally {
                sampling.set(false);
            }
        });
    }

    /**
     * Replaces the table content without losing the user's selection: rows are fresh objects
     * on every refresh, so the selected application is matched back by its process handler.
     */
    private void setItemsKeepingSelection(List<Row> rows) {
        final Row selected = table.getSelectedObject();
        model.setItems(rows);
        if (selected == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).handler == selected.handler) {
                final int viewIndex = table.convertRowIndexToView(i);
                table.getSelectionModel().setSelectionInterval(viewIndex, viewIndex);
                return;
            }
        }
    }

    /**
     * Builds the display rows; runs on a pooled thread (process tree walk + one ps and one
     * lsof call). Also keeps the previous CPU-time sample, so CPU % is the instantaneous
     * docker-stats-style delta between two refreshes - not the lifetime average.
     */
    private List<Row> buildRows(List<ProcessSnapshot> snapshots, List<MultirunProcessRegistry.Entry> entries) {
        final long hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb();

        final Map<ProcessHandler, MultirunProcessRegistry.Entry> liveByHandler = new HashMap<>();
        for (MultirunProcessRegistry.Entry entry : entries) {
            liveByHandler.put(entry.handler, entry);
        }

        // resolve the process tree of every row first, then sample everything in single ps/lsof calls
        final Map<ProcessSnapshot, Set<Long>> treeBySnapshot = new LinkedHashMap<>();
        final Set<Long> allPids = new LinkedHashSet<>();
        for (ProcessSnapshot snapshot : snapshots) {
            final Set<Long> treePids = ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(snapshot.handler));
            treeBySnapshot.put(snapshot, treePids);
            allPids.addAll(treePids);
        }
        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);
        final Map<Long, Set<Integer>> portsByPid = ProcessStatsSampler.sampleListeningPorts(allPids);

        final long nowNanos = System.nanoTime();
        final double elapsedSeconds = prevSampleNanos == 0 ? -1 : (nowNanos - prevSampleNanos) / 1_000_000_000.0;
        final Map<Long, Double> prevCpu = prevCpuSecondsByPid;

        final List<Row> rows = new ArrayList<>(snapshots.size());
        for (ProcessSnapshot snapshot : snapshots) {
            final Set<Long> treePids = treeBySnapshot.get(snapshot);
            final long rootPid = treePids.isEmpty() ? -1 : treePids.iterator().next();
            final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(statsByPid, treePids);

            // grouped app? live registry entry first; otherwise last-known multirun metadata by name,
            // so an app restarted individually keeps showing its group, env profile and limit
            final MultirunProcessRegistry.Entry live = liveByHandler.get(snapshot.handler);
            final MultirunProcessRegistry.Entry meta = live != null
                    ? live : MultirunProcessRegistry.findMetadataByName(project, snapshot.name);

            final String name = live != null ? live.appName : snapshot.name;
            final javax.swing.Icon icon = live != null ? multirunIcon
                    : snapshot.icon != null ? snapshot.icon : AllIcons.RunConfigurations.Application;
            final String multirunName = meta != null ? meta.multirunName : "-";
            final String envFileName = meta != null ? meta.envFileName : "-";
            final Integer memoryLimitMb = meta != null ? meta.memoryLimitMb : null;

            final Set<Integer> treePorts = new java.util.TreeSet<>();
            for (Long pid : treePids) {
                final Set<Integer> ports = portsByPid.get(pid);
                if (ports != null) {
                    treePorts.addAll(ports);
                }
            }
            final String portsText = treePorts.isEmpty()
                    ? "-" : treePorts.stream().map(String::valueOf).collect(Collectors.joining(", "));

            final long startedAtMs = live != null ? live.startedAtMs : ProcessStatsSampler.processStartMillis(rootPid);
            final String uptimeText = startedAtMs > 0
                    ? ProcessStatsSampler.formatUptime(System.currentTimeMillis() - startedAtMs) : "n/a";
            final String statusText = healthStatus(meta);

            final String limitText = memoryLimitMb != null
                    ? ProcessStatsSampler.formatMemory(memoryLimitMb * 1024L)
                    : ProcessStatsSampler.formatMemory(hostTotalKb);
            final String memUsage;
            final String memPercent;
            final String cpuPercent;
            double percentValue = -1;
            if (stats != null) {
                memUsage = ProcessStatsSampler.formatMemory(stats.rssKb) + " / " + limitText;
                percentValue = ProcessStatsSampler.memoryPercent(stats.rssKb, memoryLimitMb, hostTotalKb);
                memPercent = percentValue < 0 ? "n/a" : String.format(Locale.US, "%.2f%%", percentValue);
                final double deltaCpuSeconds = ProcessStatsSampler.cpuDeltaSeconds(statsByPid, prevCpu, treePids);
                cpuPercent = (deltaCpuSeconds >= 0 && elapsedSeconds > 0)
                        ? String.format(Locale.US, "%.2f%%", deltaCpuSeconds / elapsedSeconds * 100)
                        : "n/a";
            } else {
                memUsage = "n/a / " + limitText;
                memPercent = "n/a";
                cpuPercent = "n/a";
            }

            // sparkline history (memory percent over the last ~minute)
            final java.util.ArrayDeque<Double> history =
                    memHistory.computeIfAbsent(snapshot.handler, h -> new java.util.ArrayDeque<>());
            if (percentValue >= 0) {
                history.addLast(percentValue);
                while (history.size() > TREND_SAMPLES) {
                    history.removeFirst();
                }
            }
            final double[] memTrend = history.stream().mapToDouble(Double::doubleValue).toArray();

            rows.add(new Row(name, icon, multirunName, envFileName, snapshot.handler, snapshot.descriptor, meta,
                             rootPid > 0 ? String.valueOf(rootPid) : "n/a",
                             portsText, uptimeText, statusText, memUsage, memPercent, cpuPercent, memTrend));
        }

        // baseline for the next CPU delta
        final Map<Long, Double> newPrev = new HashMap<>();
        statsByPid.forEach((pid, stats) -> newPrev.put(pid, stats.cpuTimeSeconds));
        prevCpuSecondsByPid = newPrev;
        prevSampleNanos = nowNanos;

        // drop the history of processes that are gone
        final Set<ProcessHandler> liveHandlers = new java.util.HashSet<>();
        for (ProcessSnapshot snapshot : snapshots) {
            liveHandlers.add(snapshot.handler);
        }
        memHistory.keySet().retainAll(liveHandlers);

        return rows;
    }

    /**
     * Health of the app according to its "Ready when" condition (docker-compose style):
     * port and http conditions are re-checked on every refresh; log conditions cannot be
     * re-evaluated after startup, so they show "-" like apps without a condition.
     */
    private static String healthStatus(@Nullable MultirunProcessRegistry.Entry meta) {
        if (meta == null) {
            return "-";
        }
        final RunConfigurationHelper.ReadyCondition condition =
                RunConfigurationHelper.parseReadyCondition(meta.readyCondition);
        switch (condition.type) {
            case PORT:
                return RunConfigurationHelper.isPortOpen(condition.port) ? "healthy" : "down";
            case HTTP:
                return RunConfigurationHelper.isHttpHealthy(condition.value) ? "healthy" : "down";
            default:
                return "-";
        }
    }

    /**
     * Tiny polyline with the recent memory history of a row, docker-desktop style. The shape is
     * normalized to the min/max of the series (so trends are visible at any scale); the color
     * reflects the latest memory percent: green, orange from 70%, red from 90%.
     */
    private static final class SparklineCellRenderer extends javax.swing.JComponent
            implements javax.swing.table.TableCellRenderer {
        private double[] values = new double[0];
        private boolean selected;
        private javax.swing.JTable table;

        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                                boolean isSelected, boolean hasFocus,
                                                                int row, int column) {
            this.values = value instanceof double[] ? (double[]) value : new double[0];
            this.selected = isSelected;
            this.table = table;
            return this;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
            if (table != null) {
                g2.setColor(selected ? table.getSelectionBackground() : table.getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            if (values.length < 2) {
                return;
            }
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            final double span = Math.max(max - min, 0.0001);
            final int width = Math.max(getWidth() - 6, 1);
            final int height = Math.max(getHeight() - 6, 1);
            final int[] xs = new int[values.length];
            final int[] ys = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                xs[i] = 3 + (int) Math.round((double) i * width / (values.length - 1));
                ys[i] = 3 + (int) Math.round(height - (values[i] - min) / span * height);
            }
            final double last = values[values.length - 1];
            g2.setColor(last >= 90 ? com.intellij.ui.JBColor.RED
                                   : last >= 70 ? com.intellij.ui.JBColor.ORANGE
                                                : com.intellij.ui.JBColor.GREEN);
            g2.drawPolyline(xs, ys, values.length);
        }
    }

    @Override
    public void dispose() {
        timer.stop();
    }
}
