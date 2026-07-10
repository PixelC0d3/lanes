package io.github.welingtonmonteiro.multiplerun.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import io.github.welingtonmonteiro.multiplerun.MemoryHistory;
import io.github.welingtonmonteiro.multiplerun.MemoryHistory.Analysis;
import io.github.welingtonmonteiro.multiplerun.MemoryHistory.Sample;
import io.github.welingtonmonteiro.multiplerun.ProcessStatsSampler;

/**
 * A pop-up with the full memory history of one application over the current session (the monitor's
 * "Mem trend" sparkline only shows the last minute). The <b>Chart</b> tab draws RSS over time with
 * labeled X (elapsed time) and Y (memory) axes and can export the series to CSV; the <b>Analysis</b>
 * tab summarizes the memory trend (a possible-leak verdict) and breaks the memory down by process
 * of the application's tree, so a runaway child process is easy to spot.
 */
public class MemoryChartDialog extends DialogWrapper {

    private final Project project;
    private final String appName;
    private final List<Sample> samples;
    private final long rootPid;
    private final boolean analysisFirst;
    private final Analysis analysis;

    private final BreakdownTableModel breakdownModel = new BreakdownTableModel();
    /** Every process sampled in the last breakdown, kept so the filter can narrow the view. */
    private List<BreakdownRow> allBreakdownRows = new ArrayList<>();
    private com.intellij.ui.components.fields.ExtendableTextField breakdownFilter;

    public MemoryChartDialog(@Nullable Project project, @NotNull String appName,
                             @NotNull List<Sample> samples, long rootPid, boolean analysisFirst) {
        super(project);
        this.project = project;
        this.appName = appName;
        this.samples = samples;
        this.rootPid = rootPid;
        this.analysisFirst = analysisFirst;
        this.analysis = MemoryHistory.analyze(samples);
        setTitle("Memory history - " + appName);
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        final JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Chart", createChartTab());
        tabs.addTab("Analysis", createAnalysisTab());
        tabs.setPreferredSize(new Dimension(JBUI.scale(700), JBUI.scale(440)));
        if (analysisFirst) {
            tabs.setSelectedIndex(1); // open straight on Analysis when requested
        }
        return tabs;
    }

    private JComponent createChartTab() {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        final ChartComponent chart = new ChartComponent();
        chart.setPreferredSize(new Dimension(JBUI.scale(660), JBUI.scale(300)));
        panel.add(chart, BorderLayout.CENTER);

        final JButton exportButton = new JButton("Export CSV…");
        exportButton.setEnabled(!samples.isEmpty());
        exportButton.addActionListener(e -> exportCsv());
        final JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(exportButton);
        south.add(new JLabel(samples.size() + " samples"));
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createAnalysisTab() {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(8));

        final JBLabel summary = new JBLabel(leakSummaryHtml(analysis));
        summary.setVerticalAlignment(JLabel.TOP);
        panel.add(summary, BorderLayout.NORTH);

        // center: a filter over the per-process breakdown table
        final JPanel center = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        breakdownFilter = new com.intellij.ui.components.fields.ExtendableTextField();
        breakdownFilter.getEmptyText().setText("Filter processes by PID or command");
        breakdownFilter.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                applyBreakdownFilter();
            }
        });
        center.add(breakdownFilter, BorderLayout.NORTH);

        final JBTable table = new JBTable(breakdownModel);
        table.getEmptyText().setText("Sampling process tree…");
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(70));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(360));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(90));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(70));
        center.add(new JBScrollPane(table), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        final JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> sampleBreakdown());
        final JButton export = new JButton("Export analysis…");
        export.addActionListener(e -> exportAnalysis());
        final JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(refresh);
        south.add(export);
        panel.add(south, BorderLayout.SOUTH);

        sampleBreakdown();
        return panel;
    }

    /** Builds an HTML summary of the memory trend, coloring the verdict. */
    private static String leakSummaryHtml(Analysis a) {
        if (a.trend == MemoryHistory.Trend.INSUFFICIENT_DATA) {
            return "<html><b>Leak analysis</b><br>Not enough samples yet - keep the application "
                    + "running to collect a trend.</html>";
        }
        final String color = a.trend == MemoryHistory.Trend.GROWING ? "#D9534F" : "#5CB85C";
        final String sign = a.netChangeKb >= 0 ? "+" : "-";
        final String hourSign = a.projectedPerHourKb >= 0 ? "+" : "-";
        return "<html><b>Leak analysis</b><br>"
                + "Verdict: <b><font color='" + color + "'>" + MemoryHistory.verdictText(a) + "</font></b><br>"
                + "Trend: " + String.format(Locale.US, "%+.1f MiB/min", a.slopeKbPerMin / 1024.0)
                + " (~ " + hourSign + ProcessStatsSampler.formatMemory(Math.abs(a.projectedPerHourKb)) + "/h)"
                + ", R²=" + String.format(Locale.US, "%.2f", a.rSquared)
                + ", memory not freed " + String.format(Locale.US, "%.0f%%", a.monotonicFraction * 100)
                + " of the time<br>"
                + "Duration: " + ProcessStatsSampler.formatUptime(a.durationMs)
                + " (" + a.samples + " samples)<br>"
                + "First → last: " + ProcessStatsSampler.formatMemory(a.firstRssKb) + " → "
                + ProcessStatsSampler.formatMemory(a.lastRssKb)
                + " (" + sign + ProcessStatsSampler.formatMemory(Math.abs(a.netChangeKb)) + ")<br>"
                + "Min / peak: " + ProcessStatsSampler.formatMemory(a.minRssKb) + " / "
                + ProcessStatsSampler.formatMemory(a.maxRssKb) + "</html>";
    }

    /** Samples the current RSS of every process in the tree, off the EDT, and fills the table. */
    private void sampleBreakdown() {
        breakdownModel.setRows(new ArrayList<>()); // clear while re-sampling
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final Set<Long> tree = ProcessStatsSampler.processTreePids(rootPid);
            final Map<Long, ProcessStatsSampler.Stats> stats = ProcessStatsSampler.samplePids(tree);
            long total = 0;
            for (ProcessStatsSampler.Stats s : stats.values()) {
                total += s.rssKb;
            }
            final long totalKb = total;
            final List<BreakdownRow> rows = new ArrayList<>();
            for (Long pid : tree) {
                final ProcessStatsSampler.Stats s = stats.get(pid);
                final long rssKb = s == null ? 0 : s.rssKb;
                rows.add(new BreakdownRow(pid, commandOf(pid), rssKb,
                                          totalKb > 0 ? rssKb * 100.0 / totalKb : 0));
            }
            rows.sort((x, y) -> Long.compare(y.rssKb, x.rssKb)); // heaviest first
            // ModalityState.any(): this dialog is modal, so a default-modality invokeLater would
            // never run until it closes - which is why the table used to stay on "Sampling…".
            ApplicationManager.getApplication().invokeLater(() -> {
                allBreakdownRows = rows;
                applyBreakdownFilter();
            }, com.intellij.openapi.application.ModalityState.any());
        });
    }

    /** Narrows the breakdown table to processes whose PID or command matches the filter text. */
    private void applyBreakdownFilter() {
        final String filter = breakdownFilter == null ? "" : breakdownFilter.getText();
        if (filter == null || filter.trim().isEmpty()) {
            breakdownModel.setRows(new ArrayList<>(allBreakdownRows));
            return;
        }
        final String needle = filter.trim().toLowerCase(Locale.ROOT);
        final List<BreakdownRow> filtered = new ArrayList<>();
        for (BreakdownRow row : allBreakdownRows) {
            if (String.valueOf(row.pid).contains(needle)
                    || row.command.toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(row);
            }
        }
        breakdownModel.setRows(filtered);
    }

    /** Writes the leak analysis plus the current per-process breakdown to a text report. */
    private void exportAnalysis() {
        final FileSaverDescriptor descriptor =
                // NOTE: the (title, description) ctor does not exist on the 2023.3 baseline, so this
                // compiles to the varargs ctor either way; keep the extension for the save dialog.
                new FileSaverDescriptor("Export Memory Analysis", "Save the memory analysis as a text report", "txt");
        final VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, sanitize(appName) + "-memory-analysis.txt");
        if (wrapper == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder(MemoryHistory.summaryText(appName, analysis));
        sb.append('\n').append("Per-process breakdown (sampled):\n");
        if (allBreakdownRows.isEmpty()) {
            sb.append("  (not sampled yet)\n");
        } else {
            for (BreakdownRow row : allBreakdownRows) {
                sb.append(String.format(Locale.US, "  PID %-8d %6s (%.1f%%)  %s%n",
                                        row.pid, ProcessStatsSampler.formatMemory(row.rssKb),
                                        row.percentOfTree, row.command));
            }
        }
        try {
            java.nio.file.Files.write(wrapper.getFile().toPath(),
                                      sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project, "Could not write the file: " + ex.getMessage(), "Export Analysis");
        }
    }

    /** Best-effort readable command for a pid: command line, else executable, else the pid. */
    private static String commandOf(long pid) {
        return ProcessHandle.of(pid)
                .map(ProcessHandle::info)
                .map(info -> info.commandLine().orElse(info.command().orElse("PID " + pid)))
                .orElse("PID " + pid + " (ended)");
    }

    @NotNull
    @Override
    protected javax.swing.Action[] createActions() {
        return new javax.swing.Action[]{getOKAction()};
    }

    private void exportCsv() {
        final FileSaverDescriptor descriptor =
                new FileSaverDescriptor("Export Memory History", "Save the memory history as CSV", "csv");
        final VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, sanitize(appName) + "-memory.csv");
        if (wrapper == null) {
            return;
        }
        try {
            java.nio.file.Files.write(wrapper.getFile().toPath(),
                                      MemoryHistory.toCsv(samples).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project, "Could not write the file: " + ex.getMessage(), "Export CSV");
        }
    }

    private static String sanitize(String name) {
        return name == null ? "app" : name.trim().replaceAll("[^a-zA-Z0-9-_.]", "_");
    }

    /** One process of the application's tree in the breakdown table. */
    private static final class BreakdownRow {
        final long pid;
        final String command;
        final long rssKb;
        final double percentOfTree;

        BreakdownRow(long pid, String command, long rssKb, double percentOfTree) {
            this.pid = pid;
            this.command = command;
            this.rssKb = rssKb;
            this.percentOfTree = percentOfTree;
        }
    }

    /** Table model for the per-process memory breakdown (PID, Command, Memory, % of tree). */
    private static final class BreakdownTableModel extends AbstractTableModel {
        private List<BreakdownRow> rows = new ArrayList<>();

        void setRows(List<BreakdownRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return 4; }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0: return "PID";
                case 1: return "Command";
                case 2: return "Memory";
                default: return "% of tree";
            }
        }

        @Override public boolean isCellEditable(int row, int col) { return false; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final BreakdownRow r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.pid;
                case 1: return r.command;
                case 2: return ProcessStatsSampler.formatMemory(r.rssKb);
                default: return String.format(Locale.US, "%.1f%%", r.percentOfTree);
            }
        }
    }

    /**
     * Line chart of RSS over the session with labeled axes: X is elapsed time (from the first
     * sample), Y is memory (RSS). Grid lines and tick labels make the scale readable; the peak is
     * annotated and the line color reflects the latest memory percentage.
     */
    private final class ChartComponent extends JComponent {
        private static final int LEFT = 66;
        private static final int RIGHT = 16;
        private static final int TOP = 18;
        private static final int BOTTOM = 42;

        @Override
        protected void paintComponent(Graphics g) {
            final Graphics2D g2 = (Graphics2D) g;
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (samples.size() < 2) {
                g2.setColor(JBColor.GRAY);
                g2.drawString("Not enough samples yet", LEFT, TOP + 12);
                return;
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            long maxRss = 0;
            for (Sample sample : samples) {
                maxRss = Math.max(maxRss, sample.rssKb);
            }
            final double scaleMax = Math.max(maxRss * 1.05, 1); // 5% headroom above the peak
            final long durationMs = Math.max(samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs, 1);

            final int plotW = Math.max(getWidth() - LEFT - RIGHT, 1);
            final int plotH = Math.max(getHeight() - TOP - BOTTOM, 1);
            final int axisX = LEFT;
            final int axisYBottom = TOP + plotH;

            final java.awt.Font baseFont = g2.getFont();
            final java.awt.Font small = baseFont.deriveFont(baseFont.getSize2D() - 1f);
            g2.setFont(small);
            final java.awt.FontMetrics fm = g2.getFontMetrics();

            // Y grid + tick labels (memory)
            for (int i = 0; i <= 4; i++) {
                final double f = i / 4.0;
                final int y = axisYBottom - (int) Math.round(f * plotH);
                g2.setColor(JBColor.border());
                g2.drawLine(axisX, y, axisX + plotW, y);
                final String label = ProcessStatsSampler.formatMemory((long) (scaleMax * f));
                g2.setColor(JBColor.GRAY);
                g2.drawString(label, axisX - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            }
            // X tick labels (elapsed time)
            for (int i = 0; i <= 4; i++) {
                final double f = i / 4.0;
                final int x = axisX + (int) Math.round(f * plotW);
                g2.setColor(JBColor.GRAY);
                g2.drawLine(x, axisYBottom, x, axisYBottom + 3);
                final String label = ProcessStatsSampler.formatUptime((long) (durationMs * f));
                int lx = x - fm.stringWidth(label) / 2;
                lx = Math.max(axisX, Math.min(lx, axisX + plotW - fm.stringWidth(label)));
                g2.drawString(label, lx, axisYBottom + 3 + fm.getAscent() + 1);
            }

            // axes
            g2.setColor(JBColor.foreground());
            g2.drawLine(axisX, TOP, axisX, axisYBottom);
            g2.drawLine(axisX, axisYBottom, axisX + plotW, axisYBottom);

            // axis titles
            g2.setColor(JBColor.GRAY);
            final String xTitle = "Elapsed time";
            g2.drawString(xTitle, axisX + (plotW - fm.stringWidth(xTitle)) / 2, getHeight() - 4);
            final String yTitle = "Memory (RSS)";
            final java.awt.geom.AffineTransform saved = g2.getTransform();
            g2.rotate(-Math.PI / 2, 12, TOP + plotH / 2.0);
            g2.drawString(yTitle, 12 - fm.stringWidth(yTitle) / 2, TOP + plotH / 2 + fm.getAscent());
            g2.setTransform(saved);

            // peak annotation
            g2.setColor(JBColor.GRAY);
            g2.drawString("peak " + ProcessStatsSampler.formatMemory(maxRss), axisX + 6, TOP + fm.getAscent());

            // the series
            final int[] xs = new int[samples.size()];
            final int[] ys = new int[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                xs[i] = axisX + (int) Math.round((double) i * plotW / (samples.size() - 1));
                ys[i] = axisYBottom - (int) Math.round(samples.get(i).rssKb / scaleMax * plotH);
            }
            final double lastPercent = samples.get(samples.size() - 1).percent;
            g2.setColor(lastPercent >= 90 ? JBColor.RED
                                          : lastPercent >= 70 ? JBColor.ORANGE
                                                              : JBColor.GREEN);
            g2.drawPolyline(xs, ys, samples.size());
            g2.setFont(baseFont);
        }
    }
}
