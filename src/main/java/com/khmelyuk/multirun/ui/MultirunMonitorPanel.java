package com.khmelyuk.multirun.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Timer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import com.khmelyuk.multirun.MultirunProcessRegistry;
import com.khmelyuk.multirun.ProcessStatsSampler;
import com.khmelyuk.multirun.StopRunningMultirunConfigurationsAction;

/**
 * The "Multiple Run Monitor" tool window content: a docker-stats-like table with the
 * applications started by Multiple Run and their live memory/CPU usage, refreshed
 * automatically every couple of seconds.
 */
public class MultirunMonitorPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int REFRESH_INTERVAL_MS = 2000;

    /** Immutable display row; built off the EDT with all texts precomputed. */
    static final class Row {
        final String appName;
        final String multirunName;
        final String pid;
        final String memUsage;
        final String memPercent;
        final String cpuPercent;

        Row(String appName, String multirunName, String pid, String memUsage, String memPercent, String cpuPercent) {
            this.appName = appName;
            this.multirunName = multirunName;
            this.pid = pid;
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
                column("Name", 220, row -> row.appName),
                column("Multiple Run", 160, row -> row.multirunName),
                column("PID", 70, row -> row.pid),
                column("Mem Usage / Limit", 160, row -> row.memUsage),
                column("Mem %", 70, row -> row.memPercent),
                column("CPU %", 70, row -> row.cpuPercent));
        table = new TableView<>(model);
        table.getEmptyText().setText("No applications started by Multiple Run are running");

        final DefaultActionGroup group = new DefaultActionGroup();
        group.add(new DumbAwareAction("Refresh", "Refresh the process list now", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh();
            }
        });
        final AnAction stopAction = ActionManager.getInstance().getAction(StopRunningMultirunConfigurationsAction.ACTION_ID);
        if (stopAction != null) {
            group.add(stopAction);
        }
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("MultipleRunMonitor", group, false);
        toolbar.setTargetComponent(table);
        setToolbar(toolbar.getComponent());
        setContent(ScrollPaneFactory.createScrollPane(table));

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

    /** Builds the display rows; runs on a pooled thread (process tree walk + one ps call). */
    private static List<Row> buildRows(List<MultirunProcessRegistry.Entry> entries) {
        final long hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb();

        // resolve the process tree of every entry first, then sample everything with a single ps call
        final Map<MultirunProcessRegistry.Entry, Set<Long>> treeByEntry = new LinkedHashMap<>();
        final Set<Long> allPids = new LinkedHashSet<>();
        for (MultirunProcessRegistry.Entry entry : entries) {
            final Set<Long> treePids = ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler));
            treeByEntry.put(entry, treePids);
            allPids.addAll(treePids);
        }
        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);

        final List<Row> rows = new ArrayList<>(entries.size());
        for (MultirunProcessRegistry.Entry entry : entries) {
            final Set<Long> treePids = treeByEntry.get(entry);
            final long rootPid = treePids.isEmpty() ? -1 : treePids.iterator().next();
            final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(statsByPid, treePids);

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
            rows.add(new Row(entry.appName, entry.multirunName,
                             rootPid > 0 ? String.valueOf(rootPid) : "n/a",
                             memUsage, memPercent, cpuPercent));
        }
        return rows;
    }

    @Override
    public void dispose() {
        timer.stop();
    }
}
