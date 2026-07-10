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

import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
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
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText;
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
import io.github.welingtonmonteiro.multiplerun.MemoryHistory;
import io.github.welingtonmonteiro.multiplerun.MultirunProcessRegistry;
import io.github.welingtonmonteiro.multiplerun.MultirunRunConfiguration;
import io.github.welingtonmonteiro.multiplerun.MultirunRunnerState;
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

    /** Initial column widths, by model index; also reapplied when columns are shown/hidden. */
    private static final int[] PREFERRED_WIDTHS = {220, 110, 90, 70, 100, 80, 80, 160, 70, 120, 70};
    /** The one column that can never be hidden (it identifies the row). */
    private static final String MANDATORY_COLUMN = "Name";
    /** Column header names the user chose to hide (empty = everything visible). */
    private final Set<String> hiddenColumns = new LinkedHashSet<>();

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
        /** The env-file profiles configured on the app's Multiple Run group (empty when unknown). */
        final List<String> envProfiles;

        Row(String name, javax.swing.Icon icon, String multirunName, String envFileName,
            ProcessHandler handler, RunContentDescriptor descriptor, MultirunProcessRegistry.Entry meta,
            String pid, String ports, String uptime, String status,
            String memUsage, String memPercent, String cpuPercent, double[] memTrend,
            List<String> envProfiles) {
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
            this.envProfiles = envProfiles == null ? java.util.Collections.emptyList() : envProfiles;
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
    /** Full session memory history per process, for the click-to-open chart (bounded). */
    private static final int MAX_FULL_SAMPLES = 10_000;
    private final Map<ProcessHandler, List<MemoryHistory.Sample>> fullHistory =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final SparklineCellRenderer sparklineRenderer = new SparklineCellRenderer();
    private final PortsCellRenderer portsRenderer = new PortsCellRenderer();
    private final StatusCellRenderer statusRenderer = new StatusCellRenderer();

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
                new ColumnInfo<Row, String>("Env") {
                    @Nullable
                    @Override
                    public String valueOf(Row row) {
                        return row.envFileName;
                    }

                    @Override
                    public javax.swing.table.TableCellRenderer getRenderer(Row row) {
                        return new EnvCellRenderer(hasLoadedEnv(row), row.envProfiles.size() > 1);
                    }
                },
                column("PID", row -> row.pid),
                new ColumnInfo<Row, String>("Ports") {
                    @Nullable
                    @Override
                    public String valueOf(Row row) {
                        return row.ports;
                    }

                    @Override
                    public javax.swing.table.TableCellRenderer getRenderer(Row row) {
                        return portsRenderer;
                    }
                },
                column("Uptime", row -> row.uptime),
                new ColumnInfo<Row, String>("Status") {
                    @Nullable
                    @Override
                    public String valueOf(Row row) {
                        return row.status;
                    }

                    @Override
                    public javax.swing.table.TableCellRenderer getRenderer(Row row) {
                        return statusRenderer;
                    }
                },
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
        // apply initial widths (and honor any hidden columns); all columns stay resizable
        applyColumnVisibility();
        // batch actions: the row actions operate on every selected row
        table.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // shared action instances: the same objects go into the toolbar and the right-click popup
        final AnAction restartSelected = new RestartSelectedAction();
        final AnAction stopSelected = new StopSelectedAction();
        final AnAction killSelected = new KillSelectedAction();
        final AnAction restartUnhealthy = new RestartUnhealthyAction();

        final DefaultActionGroup rowActions = new DefaultActionGroup();
        rowActions.add(restartSelected);
        rowActions.add(stopSelected);
        rowActions.add(killSelected);
        rowActions.addSeparator();
        rowActions.add(restartUnhealthy);

        final DefaultActionGroup toolbarGroup = new DefaultActionGroup();
        toolbarGroup.add(new DumbAwareAction("Refresh", "Refresh the process list now", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh();
            }
        });
        // add the row actions individually (avoids the deprecated DefaultActionGroup.addAll(ActionGroup))
        toolbarGroup.addAll(restartSelected, stopSelected, killSelected);
        toolbarGroup.addSeparator();
        toolbarGroup.add(restartUnhealthy);
        toolbarGroup.add(new MemoryAnalysisAction());
        toolbarGroup.add(new KillByPortAction());
        toolbarGroup.add(new ShowColumnsAction());
        toolbarGroup.addSeparator();
        toolbarGroup.add(new RestartAllWithCountAction());
        toolbarGroup.add(new StopAllWithCountAction());
        toolbarGroup.add(new BatchEnvSwitchAction());
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("MultipleRunMonitor", toolbarGroup, false);
        toolbar.setTargetComponent(table);
        setToolbar(toolbar.getComponent());
        setContent(ScrollPaneFactory.createScrollPane(table));
        PopupHandler.installPopupMenu(table, rowActions, "MultipleRunMonitorPopup");
        // double click on a row jumps to the console tab of that application
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                // single click on the Ports column opens the port(s) in the browser;
                // single click on the Mem trend column opens the full memory chart
                if (e.getClickCount() == 1 && isPortsColumn(e.getPoint())) {
                    openPortsAt(e);
                } else if (e.getClickCount() == 1 && isColumn(e.getPoint(), "Env")) {
                    openEnvAt(e);
                } else if (e.getClickCount() == 1 && isColumn(e.getPoint(), "Mem trend")) {
                    openMemChartAt(e);
                } else if (e.getClickCount() == 2) {
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

    /** All column header names, in model order. */
    private List<String> allColumnNames() {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            names.add(model.getColumnName(i));
        }
        return names;
    }

    /** The names that stay visible: every column in {@code all} not present in {@code hidden}. */
    static List<String> visibleColumns(List<String> all, Set<String> hidden) {
        final List<String> result = new ArrayList<>();
        for (String name : all) {
            if (!hidden.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * Rebuilds the table's column model from {@link #hiddenColumns}: visible columns are re-added in
     * model order with their preferred width. TableView resolves renderers/values through the model
     * index, so hiding a column never disturbs the others.
     */
    private void applyColumnVisibility() {
        final javax.swing.table.TableColumnModel cm = table.getColumnModel();
        while (cm.getColumnCount() > 0) {
            cm.removeColumn(cm.getColumn(0));
        }
        for (int modelIdx = 0; modelIdx < model.getColumnCount(); modelIdx++) {
            final String name = model.getColumnName(modelIdx);
            if (hiddenColumns.contains(name)) {
                continue;
            }
            final javax.swing.table.TableColumn col = new javax.swing.table.TableColumn(modelIdx);
            col.setHeaderValue(name);
            if (modelIdx < PREFERRED_WIDTHS.length) {
                col.setPreferredWidth(PREFERRED_WIDTHS[modelIdx]);
            }
            cm.addColumn(col);
        }
    }

    @Nullable
    private Row selectedRow() {
        return table.getSelectedObject();
    }

    /** Every selected row (batch actions operate on all of them). */
    private List<Row> selectedRows() {
        return table.getSelectedObjects();
    }

    /** The rows whose readiness status is currently "down" - used by "Restart Unhealthy". */
    static List<Row> unhealthyRows(List<Row> rows) {
        final List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if ("down".equals(row.status)) {
                result.add(row);
            }
        }
        return result;
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

    /** True when the point falls inside the (possibly reordered) "Ports" column. */
    private boolean isPortsColumn(java.awt.Point point) {
        return isColumn(point, "Ports");
    }

    /** True when the point falls inside the (possibly reordered) column with the given header name. */
    private boolean isColumn(java.awt.Point point, String columnName) {
        final int viewColumn = table.columnAtPoint(point);
        if (viewColumn < 0) {
            return false;
        }
        return columnName.equals(model.getColumnName(table.convertColumnIndexToModel(viewColumn)));
    }

    /** Opens the full-session memory chart for the clicked row (on the Chart tab). */
    private void openMemChartAt(java.awt.event.MouseEvent e) {
        final int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        final Row row = model.getItem(table.convertRowIndexToModel(viewRow));
        if (row != null) {
            openMemChart(row, false);
        }
    }

    /** Opens the full-session memory chart/analysis dialog for a row, on the chosen initial tab. */
    private void openMemChart(Row row, boolean analysisFirst) {
        final List<MemoryHistory.Sample> stored = fullHistory.get(row.handler);
        final List<MemoryHistory.Sample> copy;
        if (stored == null) {
            copy = java.util.Collections.emptyList();
        } else {
            synchronized (stored) {
                copy = new ArrayList<>(stored);
            }
        }
        new MemoryChartDialog(project, row.name, copy,
                              MultirunProcessRegistry.pidOf(row.handler), analysisFirst).show();
    }

    /** True when the row carries a Multiple Run environment that can be shown in the Env viewer. */
    private static boolean hasLoadedEnv(Row row) {
        return row.meta != null && !row.meta.loadedEnv.isEmpty();
    }

    /**
     * Handles a click on the Env cell: when the app's group has a single environment (or none) it
     * opens the read-only variable viewer straight away, as before; when the group has more than one
     * environment profile it opens a dropdown to switch between them, with a "view variables" entry.
     */
    private void openEnvAt(java.awt.event.MouseEvent e) {
        final int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        final Row row = model.getItem(table.convertRowIndexToModel(viewRow));
        if (row == null) {
            return;
        }
        if (row.envProfiles.size() > 1) {
            showEnvSwitchPopup(row, e);
        } else if (hasLoadedEnv(row)) {
            openEnvViewer(row);
        }
    }

    /** The read-only viewer with the environment variables loaded for the row's application. */
    private void openEnvViewer(Row row) {
        if (hasLoadedEnv(row)) {
            new EnvVarsDialog(project, row.name, row.envFileName, row.meta.includeSystemEnv, row.meta.loadedEnv).show();
        }
    }

    /**
     * Dropdown for the Env cell of a group with several profiles: one item per profile switches
     * <b>just this application</b> to it (via a per-app override) and restarts only that app; the
     * active one is disabled; a trailing "view loaded variables" item opens the read-only viewer.
     * (The toolbar's Switch Environment button is the group-wide counterpart.)
     */
    private void showEnvSwitchPopup(Row row, java.awt.event.MouseEvent e) {
        final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu("Environment");
        for (String profile : row.envProfiles) {
            final boolean active = RunConfigurationHelper.envFileDisplayName(profile).equals(row.envFileName);
            final javax.swing.JMenuItem item = new javax.swing.JMenuItem(
                    RunConfigurationHelper.envFileDisplayName(profile) + (active ? "  (active)" : ""));
            item.setToolTipText(profile);
            if (active) {
                item.setEnabled(false);
            } else {
                item.addActionListener(a -> switchAppEnv(row, profile));
            }
            menu.add(item);
        }
        if (hasLoadedEnv(row)) {
            menu.addSeparator();
            final javax.swing.JMenuItem detail =
                    new javax.swing.JMenuItem("View loaded variables…", AllIcons.Actions.Show);
            detail.addActionListener(a -> openEnvViewer(row));
            menu.add(detail);
        }
        menu.show(table, e.getX(), e.getY());
    }

    /** The distinct env profiles offered for switching: only groups with more than one qualify. */
    static List<String> switchableEnvProfiles(List<Row> rows) {
        final Set<String> profiles = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.envProfiles.size() > 1) {
                profiles.addAll(row.envProfiles);
            }
        }
        return new ArrayList<>(profiles);
    }

    /** Group names (from the rows) whose configured profiles include the chosen one - the batch targets. */
    static Set<String> groupsWithProfile(List<Row> rows, String profile) {
        final Set<String> groups = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.multirunName != null && !"-".equals(row.multirunName)
                    && row.envProfiles.size() > 1 && row.envProfiles.contains(profile)) {
                groups.add(row.multirunName);
            }
        }
        return groups;
    }

    /** The Multiple Run group configuration with this name, or null when it no longer exists. */
    @Nullable
    private MultirunRunConfiguration findGroupConfig(String groupName) {
        if (groupName == null) {
            return null;
        }
        for (RunConfiguration cfg : RunManager.getInstance(project).getAllConfigurationsList()) {
            if (cfg instanceof MultirunRunConfiguration && groupName.equals(cfg.getName())) {
                return (MultirunRunConfiguration) cfg;
            }
        }
        return null;
    }

    /** Live rows belonging to a Multiple Run group (matched by the group name shown on the row). */
    private List<Row> runningRowsOfGroup(String groupName) {
        final List<Row> result = new ArrayList<>();
        for (Row row : model.getItems()) {
            if (groupName != null && groupName.equals(row.multirunName)
                    && row.handler != null && !row.handler.isProcessTerminated()) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * Switches <b>a single application</b> to {@code envProfile} through a per-app env override
     * (persisted on the group, so it also applies to the next launch) and restarts only that app -
     * everything else in the group keeps running with its own environment. Because Multiple Run
     * bakes the environment into each app at launch, a plain restart would keep the old values, so
     * the app is stopped and relaunched through the group's pipeline with the new profile - keeping
     * its executor (Run/Debug/...) and staying tracked in the monitor.
     */
    private void switchAppEnv(Row row, String envProfile) {
        final MultirunRunConfiguration group = findGroupConfig(row.multirunName);
        if (group == null) {
            Messages.showErrorDialog(project,
                    "The Multiple Run group '" + row.multirunName + "' no longer exists.", "Switch Environment");
            return;
        }
        final int answer = Messages.showYesNoDialog(
                project,
                "Switch '" + row.name + "' to environment '" + RunConfigurationHelper.envFileDisplayName(envProfile)
                        + "' and restart just this application?",
                "Switch Environment", "Switch & Restart", "Cancel", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        // per-app override wins over the group environment for this app only
        final Map<String, String> appEnvFiles = new LinkedHashMap<>(group.getAppEnvFiles());
        appEnvFiles.put(row.name, envProfile);
        group.setAppEnvFiles(appEnvFiles);
        relaunchApp(group, row.name, executorOf(row), row.handler);
    }

    /** The executor the app is running under (so a relaunch keeps Debug as Debug), Run as fallback. */
    private Executor executorOf(Row row) {
        if (row.meta != null && row.meta.environment != null) {
            final Executor executor = row.meta.environment.getExecutor();
            if (executor != null) {
                return executor;
            }
        }
        return DefaultRunExecutor.getRunExecutorInstance();
    }

    /**
     * Stops the given app and relaunches only it through the group's pipeline (which re-reads the
     * environment, re-registers the app in the monitor and honors the group's settings), under the
     * given executor. Used by both the per-row env switch and the batch Switch Environment button.
     */
    private void relaunchApp(MultirunRunConfiguration group, String appName, Executor executor,
                             ProcessHandler oldHandler) {
        RunConfiguration base = null;
        for (RunConfiguration child : group.getRunConfigurations()) {
            if (appName.equals(child.getName())) {
                base = child;
                break;
            }
        }
        if (base == null) {
            return; // the app was removed from the group in the meantime
        }
        final RunConfiguration target = base;
        final ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), target);
        if (runner == null) {
            Messages.showErrorDialog(project,
                    "No runner is available for '" + appName + "' with " + executor.getId() + ".", "Switch Environment");
            return;
        }
        if (oldHandler != null && !oldHandler.isProcessTerminated()) {
            oldHandler.destroyProcess();
        }
        final MultirunRunnerState state = group.createStateForApps(java.util.Collections.singletonList(target));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // let the old process release its port(s) before the new one starts
            if (oldHandler != null) {
                oldHandler.waitFor(10_000);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                state.execute(executor, runner);
                refresh();
            });
        });
    }

    /**
     * Modal combo to pick one env profile to apply to every running app (null when cancelled). The
     * currently-active profile(s) are marked "(active)" and one is pre-selected, so the dialog opens
     * on the environment already in use.
     */
    @Nullable
    private String chooseEnvProfile(List<String> profiles, Set<String> activePaths) {
        final com.intellij.openapi.ui.ComboBox<String> combo =
                new com.intellij.openapi.ui.ComboBox<>(profiles.toArray(new String[0]));
        combo.setRenderer(new EnvProfileListRenderer(activePaths));
        for (String profile : profiles) {
            if (activePaths.contains(profile)) {
                combo.setSelectedItem(profile);
                break;
            }
        }
        final com.intellij.openapi.ui.DialogWrapper dialog =
                new com.intellij.openapi.ui.DialogWrapper(project, true) {
                    {
                        setTitle("Switch Environment");
                        setOKButtonText("Apply & Restart");
                        init();
                    }

                    @Override
                    protected javax.swing.JComponent createCenterPanel() {
                        final javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(8, 8));
                        panel.add(new javax.swing.JLabel(
                                "Environment applied to all running apps (they will restart):"),
                                  java.awt.BorderLayout.NORTH);
                        panel.add(combo, java.awt.BorderLayout.CENTER);
                        return panel;
                    }

                    @Override
                    public javax.swing.JComponent getPreferredFocusedComponent() {
                        return combo;
                    }
                };
        if (!dialog.showAndGet()) {
            return null;
        }
        return (String) combo.getSelectedItem();
    }

    /** Shows env profiles by their file name (full path as tooltip), marking the active one(s). */
    private static final class EnvProfileListRenderer extends com.intellij.ui.SimpleListCellRenderer<String> {
        private final Set<String> activePaths;

        EnvProfileListRenderer(Set<String> activePaths) {
            this.activePaths = activePaths;
        }

        @Override
        public void customize(@NotNull javax.swing.JList<? extends String> list, String value, int index,
                              boolean selected, boolean hasFocus) {
            if (value != null) {
                setText(RunConfigurationHelper.envFileDisplayName(value)
                                + (activePaths.contains(value) ? "  (active)" : ""));
                setToolTipText(value);
            }
        }
    }

    /** Opens the port(s) of the clicked row in the browser (a menu when there is more than one). */
    private void openPortsAt(java.awt.event.MouseEvent e) {
        final int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        final Row row = model.getItem(table.convertRowIndexToModel(viewRow));
        if (row == null) {
            return;
        }
        final List<Integer> ports = parsePorts(row.ports);
        if (ports.isEmpty()) {
            return;
        }
        if (ports.size() == 1) {
            com.intellij.ide.BrowserUtil.browse(urlForPort(ports.get(0)));
            return;
        }
        final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (Integer port : ports) {
            final javax.swing.JMenuItem item = new javax.swing.JMenuItem("Open " + urlForPort(port));
            item.addActionListener(a -> com.intellij.ide.BrowserUtil.browse(urlForPort(port)));
            menu.add(item);
        }
        menu.show(table, e.getX(), e.getY());
    }

    /** Parses the comma-separated Ports cell text ("3000, 8080") into a list of port numbers. */
    static List<Integer> parsePorts(String portsText) {
        final List<Integer> ports = new ArrayList<>();
        if (portsText == null) {
            return ports;
        }
        for (String part : portsText.split(",")) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ports.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // "-"/"n/a" and any non-numeric token are simply not links
            }
        }
        return ports;
    }

    /** The URL opened when a port is clicked. */
    static String urlForPort(int port) {
        return "http://localhost:" + port;
    }

    /** Renders the Ports cell as a clickable hyperlink when the row has any listening port. */
    private static final class PortsCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                                boolean isSelected, boolean hasFocus,
                                                                int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            final String text = value == null ? "" : value.toString();
            final boolean hasPorts = !parsePorts(text).isEmpty();
            if (hasPorts) {
                if (!isSelected) {
                    setForeground(com.intellij.ui.JBColor.BLUE);
                }
                final java.util.Map<java.awt.font.TextAttribute, Object> attributes =
                        new java.util.HashMap<>(getFont().getAttributes());
                attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
                setFont(getFont().deriveFont(attributes));
                setToolTipText("Click to open in the browser");
            } else {
                setToolTipText(null);
            }
            return this;
        }
    }

    /**
     * Renders the Env cell as a clickable hyperlink when the row has a loaded environment to show.
     * When the app's group has more than one environment profile the cell also gets a {@code ▾}
     * affordance, so it reads as a dropdown: clicking it offers the profiles to switch to (plus a
     * "view variables" entry) instead of opening the viewer straight away.
     */
    private static final class EnvCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private final boolean clickable;
        private final boolean switchable;

        EnvCellRenderer(boolean clickable, boolean switchable) {
            this.clickable = clickable;
            this.switchable = switchable;
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                                boolean isSelected, boolean hasFocus,
                                                                int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (switchable) {
                setText((value == null ? "" : value.toString()) + "  ▾");
            }
            if (clickable || switchable) {
                if (!isSelected) {
                    setForeground(com.intellij.ui.JBColor.BLUE);
                }
                final java.util.Map<java.awt.font.TextAttribute, Object> attributes =
                        new java.util.HashMap<>(getFont().getAttributes());
                attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
                setFont(getFont().deriveFont(attributes));
                setToolTipText(switchable
                        ? "Click to switch the group's environment or view the loaded variables"
                        : "Click to view the loaded environment variables");
            } else {
                setToolTipText(null);
            }
            return this;
        }
    }

    /** Colors the Status cell: green for healthy/running, red for a failing readiness check. */
    private static final class StatusCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                                boolean isSelected, boolean hasFocus,
                                                                int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                final String text = value == null ? "" : value.toString();
                if ("down".equals(text)) {
                    setForeground(com.intellij.ui.JBColor.RED);
                } else if ("healthy".equals(text) || "running".equals(text)) {
                    setForeground(com.intellij.ui.JBColor.GREEN);
                }
            }
            return this;
        }
    }

    /** Restarts every selected application; everything else keeps running. */
    private final class RestartSelectedAction extends DumbAwareAction {
        RestartSelectedAction() {
            super("Restart", "Stop the selected application(s) and start them again (everything else keeps running)",
                  AllIcons.Actions.Restart);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            boolean anyRestartable = false;
            for (Row row : selectedRows()) {
                if (row.descriptor != null) {
                    anyRestartable = true;
                    break;
                }
            }
            e.getPresentation().setEnabled(anyRestartable);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            for (Row row : selectedRows()) {
                if (row.descriptor != null) {
                    // the platform stops the old process and reruns the same environment; the new
                    // process shows up again on the next refresh (all IDE processes are listed)
                    ExecutionUtil.restart(row.descriptor);
                }
            }
        }
    }

    /** Graceful stop of every selected application - same as the red stop button of its tab. */
    private final class StopSelectedAction extends DumbAwareAction {
        StopSelectedAction() {
            super("Stop", "Request the selected application(s) to terminate", AllIcons.Actions.Suspend);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!selectedRows().isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            for (Row row : selectedRows()) {
                row.handler.destroyProcess();
            }
        }
    }

    /** SIGKILL of every selected application and each process it spawned. */
    private final class KillSelectedAction extends DumbAwareAction {
        KillSelectedAction() {
            super("Force Kill", "Forcibly kill the selected application(s) and their whole process tree (SIGKILL)",
                  AllIcons.Debugger.KillProcess);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!selectedRows().isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final List<Row> rows = selectedRows();
            if (rows.isEmpty()) {
                return;
            }
            final StringBuilder list = new StringBuilder();
            for (Row row : rows) {
                list.append("\n  - '").append(row.name).append("' (PID ").append(row.pid).append(')');
            }
            final int answer = Messages.showYesNoDialog(
                    project,
                    "Forcibly kill the following application(s) and all their child processes?" + list + "\n\n" +
                    "They get no chance to shut down cleanly.",
                    "Force Kill", "Kill", "Cancel", Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return;
            }
            final List<ProcessHandler> handlers = new ArrayList<>();
            for (Row row : rows) {
                handlers.add(row.handler);
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                for (ProcessHandler handler : handlers) {
                    // resolve the tree fresh - children may have been spawned after the last refresh
                    final Set<Long> treePids =
                            ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(handler));
                    for (Long pid : treePids) {
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    }
                    // tell the IDE the process is gone, so the run tab stops its spinner too
                    handler.destroyProcess();
                }
                ApplicationManager.getApplication().invokeLater(MultirunMonitorPanel.this::refresh);
            });
        }
    }

    /** Restarts every application whose readiness status is currently "down". */
    private final class RestartUnhealthyAction extends DumbAwareAction {
        RestartUnhealthyAction() {
            super("Restart Unhealthy", "Restart every application whose port/http readiness check is currently down",
                  AllIcons.Actions.Restart);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!unhealthyRows(model.getItems()).isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            for (Row row : unhealthyRows(model.getItems())) {
                if (row.descriptor != null) {
                    ExecutionUtil.restart(row.descriptor);
                }
            }
        }
    }

    /** Lets the user choose which columns are visible through a checkbox popup. */
    private final class ShowColumnsAction extends DumbAwareAction {
        ShowColumnsAction() {
            super("Show Columns", "Choose which columns are visible", AllIcons.Actions.Show);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final com.intellij.ui.CheckBoxList<String> list = new com.intellij.ui.CheckBoxList<>();
            for (String name : allColumnNames()) {
                if (MANDATORY_COLUMN.equals(name)) {
                    continue; // the Name column is always shown
                }
                list.addItem(name, name, !hiddenColumns.contains(name));
            }
            list.setCheckBoxListListener((index, value) -> {
                final String name = list.getItemAt(index);
                if (name == null) {
                    return;
                }
                if (value) {
                    hiddenColumns.remove(name);
                } else {
                    hiddenColumns.add(name);
                }
                applyColumnVisibility();
            });
            final com.intellij.openapi.ui.popup.JBPopup popup =
                    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                            .createComponentPopupBuilder(ScrollPaneFactory.createScrollPane(list), list)
                            .setTitle("Show Columns")
                            .setResizable(true)
                            .setMovable(true)
                            .setRequestFocus(true)
                            .createPopup();
            final java.awt.Component source = e.getInputEvent() != null ? e.getInputEvent().getComponent() : table;
            popup.showUnderneathOf(source);
        }
    }

    /** Opens the memory chart/analysis of the selected application straight on the Analysis tab. */
    private final class MemoryAnalysisAction extends DumbAwareAction {
        MemoryAnalysisAction() {
            super("Memory Analysis", "Open the memory chart and leak analysis of the selected application",
                  AllIcons.Toolwindows.ToolWindowProfiler);
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
                openMemChart(row, true); // open on the Analysis tab
            }
        }
    }

    /**
     * Number of running processes that belong to Multiple Run - counted from the monitor rows, so an
     * app restarted individually from here (which the platform relaunches as a standalone run, no
     * longer in the plugin's own tracking map) still counts. A row belongs to Multiple Run when it
     * carries group metadata (meta != null); plain standalone runs never started by the plugin don't.
     */
    private int runningMultirunCount() {
        int count = 0;
        for (Row row : model.getItems()) {
            if (row.meta != null && row.handler != null && !row.handler.isProcessTerminated()) {
                count++;
            }
        }
        return count;
    }

    /** Number of running applications currently listed - grouped or standalone, every live row. */
    private int runningAppCount() {
        int count = 0;
        for (Row row : model.getItems()) {
            if (row.handler != null && !row.handler.isProcessTerminated()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Restarts every running application at once. Like the Stop-all button, it shows the
     * <b>count of running applications</b> as a badge next to a restart icon, so a single click
     * relaunches the whole set instead of restarting apps one by one; disabled when nothing runs.
     */
    private final class RestartAllWithCountAction extends DumbAwareAction implements CustomComponentAction {
        RestartAllWithCountAction() {
            super("Restart All", "Restart every running application", AllIcons.Actions.Restart);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            final int count = runningAppCount();
            final Presentation p = e.getPresentation();
            p.setText(count > 0 ? String.valueOf(count) : "");
            p.setEnabled(count > 0);
            p.setDescription(count == 1 ? "Restart the 1 running application"
                                        : "Restart all " + count + " running applications");
        }

        @Override
        public @NotNull javax.swing.JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            // an icon+text toolbar button, so the running-app count is visible as a badge
            return new ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            for (Row row : model.getItems()) {
                // re-run the same environment of every live application; each one reappears on the
                // next refresh (all IDE processes are listed)
                if (row.descriptor != null && row.handler != null && !row.handler.isProcessTerminated()) {
                    ExecutionUtil.restart(row.descriptor);
                }
            }
        }
    }

    /**
     * Stops every process Multiple Run started. Unlike the per-row Stop, this button shows the
     * <b>count of running processes</b> next to a stop icon (WebStorm-style), so it is not confused
     * with the per-row stop; it is disabled when nothing the plugin started is running.
     */
    private final class StopAllWithCountAction extends DumbAwareAction implements CustomComponentAction {
        StopAllWithCountAction() {
            super("Stop Multiple Run", "Stop every process started by Multiple Run", AllIcons.Actions.Suspend);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            final int count = runningMultirunCount();
            final Presentation p = e.getPresentation();
            p.setText(count > 0 ? String.valueOf(count) : "");
            p.setEnabled(count > 0);
            p.setDescription(count == 1 ? "Stop the 1 running Multiple Run process"
                                        : "Stop the " + count + " running Multiple Run processes");
        }

        @Override
        public @NotNull javax.swing.JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            // an icon+text toolbar button, so the running-process count is actually visible
            return new ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            // raise the stop flag and stop everything still tracked by the plugin...
            final AnAction action = ActionManager.getInstance().getAction(StopRunningMultirunConfigurationsAction.ACTION_ID);
            if (action instanceof StopRunningMultirunConfigurationsAction) {
                ((StopRunningMultirunConfigurationsAction) action).stopAll(project);
            }
            // ...plus every Multiple Run row that is no longer tracked (e.g. restarted individually,
            // now a standalone run) - so the count and the button stay consistent.
            for (Row row : model.getItems()) {
                if (row.meta != null && row.handler != null && !row.handler.isProcessTerminated()) {
                    row.handler.destroyProcess();
                }
            }
            refresh();
        }
    }

    /**
     * Switches the environment of every running Multiple Run group that offers more than one env
     * profile, and restarts their apps with it - the batch counterpart of the per-row Env dropdown,
     * so the whole set moves to a chosen .env from a single modal instead of app by app. Shows the
     * running-app count as a badge; enabled only when at least one running group has a choice.
     */
    private final class BatchEnvSwitchAction extends DumbAwareAction implements CustomComponentAction {
        BatchEnvSwitchAction() {
            super("Switch Environment", "Switch the environment of all running apps and restart them",
                  AllIcons.FileTypes.Properties);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            final int count = runningAppCount();
            final boolean hasChoices = !switchableEnvProfiles(model.getItems()).isEmpty();
            final Presentation p = e.getPresentation();
            p.setText(count > 0 ? String.valueOf(count) : "");
            p.setEnabled(count > 0 && hasChoices);
            p.setDescription(hasChoices
                    ? "Switch the environment of all running apps and restart them"
                    : "Add more than one environment profile to a group to switch between them here");
        }

        @Override
        public @NotNull javax.swing.JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            // an icon+text toolbar button, so the running-app count shows as a badge like Stop All
            return new ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            final List<String> profiles = switchableEnvProfiles(model.getItems());
            if (profiles.isEmpty()) {
                return;
            }
            final String chosen = chooseEnvProfile(profiles, activeGroupEnvPaths());
            if (chosen == null) {
                return;
            }
            // group-wide switch: set the active env file of each affected group, then relaunch every
            // running app individually - each keeps its own executor (Run/Debug/...)
            for (String groupName : groupsWithProfile(model.getItems(), chosen)) {
                final MultirunRunConfiguration group = findGroupConfig(groupName);
                if (group == null) {
                    continue;
                }
                final List<Row> rows = runningRowsOfGroup(groupName);
                // a group-wide switch is authoritative: drop any per-app override on the running apps
                // so they all follow the new group environment (and the Env column shows it uniformly)
                final Map<String, String> appEnvFiles = new LinkedHashMap<>(group.getAppEnvFiles());
                for (Row row : rows) {
                    appEnvFiles.remove(row.name);
                }
                group.setAppEnvFiles(appEnvFiles);
                group.setEnvFilePath(chosen);
                for (Row row : rows) {
                    relaunchApp(group, row.name, executorOf(row), row.handler);
                }
            }
        }
    }

    /** The env file currently active on each switchable group (to mark/pre-select it in the modal). */
    private Set<String> activeGroupEnvPaths() {
        final Set<String> groupNames = new LinkedHashSet<>();
        for (Row row : model.getItems()) {
            if (row.envProfiles.size() > 1 && row.multirunName != null && !"-".equals(row.multirunName)) {
                groupNames.add(row.multirunName);
            }
        }
        final Set<String> paths = new LinkedHashSet<>();
        for (String groupName : groupNames) {
            final MultirunRunConfiguration group = findGroupConfig(groupName);
            if (group != null && !group.getEnvFilePath().isEmpty()) {
                paths.add(group.getEnvFilePath());
            }
        }
        return paths;
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
        // the env profiles configured on each Multiple Run group - read on the EDT (RunManager),
        // so the Env column can offer them as a dropdown and the batch env switch can list them
        final Map<String, List<String>> envProfilesByGroup = new HashMap<>();
        for (RunConfiguration cfg : RunManager.getInstance(project).getAllConfigurationsList()) {
            if (cfg instanceof MultirunRunConfiguration) {
                envProfilesByGroup.put(cfg.getName(),
                                       new ArrayList<>(((MultirunRunConfiguration) cfg).getEnvProfiles()));
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                final List<Row> rows = buildRows(snapshots, entries, envProfilesByGroup);
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
     * Replaces the table content without losing the user's selection: rows are fresh objects on
     * every refresh, so the selected applications are matched back by their process handlers. The
     * whole multi-selection is preserved (not just a single row), so selecting several apps and
     * then running a batch action still targets all of them even across a refresh.
     */
    private void setItemsKeepingSelection(List<Row> rows) {
        final List<Row> previouslySelected = table.getSelectedObjects();
        model.setItems(rows);
        if (previouslySelected.isEmpty()) {
            return;
        }
        final Set<ProcessHandler> selectedHandlers = new java.util.HashSet<>();
        for (Row row : previouslySelected) {
            selectedHandlers.add(row.handler);
        }
        final List<ProcessHandler> rowHandlers = new ArrayList<>(rows.size());
        for (Row row : rows) {
            rowHandlers.add(row.handler);
        }
        final javax.swing.ListSelectionModel selectionModel = table.getSelectionModel();
        selectionModel.setValueIsAdjusting(true);
        selectionModel.clearSelection();
        for (int modelIndex : selectionIndices(rowHandlers, selectedHandlers)) {
            final int viewIndex = table.convertRowIndexToView(modelIndex);
            selectionModel.addSelectionInterval(viewIndex, viewIndex);
        }
        selectionModel.setValueIsAdjusting(false);
    }

    /**
     * Indices of the rows whose key was selected before a refresh, used to restore a multi-selection
     * after the row objects are rebuilt. Kept pure (keyed on any identity) so it is unit-testable
     * without a live table.
     */
    static <T> List<Integer> selectionIndices(List<T> rowKeys, Set<T> selectedKeys) {
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < rowKeys.size(); i++) {
            if (selectedKeys.contains(rowKeys.get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Builds the display rows; runs on a pooled thread (process tree walk + one ps and one
     * lsof call). Also keeps the previous CPU-time sample, so CPU % is the instantaneous
     * docker-stats-style delta between two refreshes - not the lifetime average.
     */
    private List<Row> buildRows(List<ProcessSnapshot> snapshots, List<MultirunProcessRegistry.Entry> entries,
                                Map<String, List<String>> envProfilesByGroup) {
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

            // full session history (bounded) feeds the click-to-open memory chart
            if (stats != null) {
                final List<MemoryHistory.Sample> full =
                        fullHistory.computeIfAbsent(snapshot.handler, h -> new ArrayList<>());
                synchronized (full) {
                    full.add(new MemoryHistory.Sample(System.currentTimeMillis(), stats.rssKb, percentValue));
                    while (full.size() > MAX_FULL_SAMPLES) {
                        full.remove(0);
                    }
                }
            }

            final List<String> envProfiles = meta != null
                    ? envProfilesByGroup.getOrDefault(meta.multirunName, java.util.Collections.emptyList())
                    : java.util.Collections.emptyList();

            rows.add(new Row(name, icon, multirunName, envFileName, snapshot.handler, snapshot.descriptor, meta,
                             rootPid > 0 ? String.valueOf(rootPid) : "n/a",
                             portsText, uptimeText, statusText, memUsage, memPercent, cpuPercent, memTrend,
                             envProfiles));
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
        fullHistory.keySet().retainAll(liveHandlers);

        return rows;
    }

    /**
     * Health of the app according to its "Ready when" condition (docker-compose style): every row
     * in the monitor is a live process, so the baseline is "running"; a port/http condition that is
     * re-checked on every refresh refines that into "healthy" or "down". Log conditions cannot be
     * re-evaluated after startup, so those apps simply stay "running".
     */
    private static String healthStatus(@Nullable MultirunProcessRegistry.Entry meta) {
        if (meta == null) {
            return statusLabel(RunConfigurationHelper.ReadyCondition.Type.NONE, false);
        }
        final RunConfigurationHelper.ReadyCondition condition =
                RunConfigurationHelper.parseReadyCondition(meta.readyCondition);
        switch (condition.type) {
            case PORT:
                return statusLabel(condition.type, RunConfigurationHelper.isPortOpen(condition.port));
            case HTTP:
                return statusLabel(condition.type, RunConfigurationHelper.isHttpHealthy(condition.value));
            default:
                return statusLabel(condition.type, false);
        }
    }

    /**
     * The Status label for a live process: a PORT/HTTP readiness check maps to "healthy"/"down"
     * depending on {@code checkPassed}; every other case (no condition, or a log condition) is a
     * plain "running", since the row only exists while the process is alive.
     */
    static String statusLabel(RunConfigurationHelper.ReadyCondition.Type type, boolean checkPassed) {
        switch (type) {
            case PORT:
            case HTTP:
                return checkPassed ? "healthy" : "down";
            default:
                return "running";
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
