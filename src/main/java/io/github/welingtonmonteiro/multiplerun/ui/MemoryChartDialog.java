package io.github.welingtonmonteiro.multiplerun.ui;

import java.util.List;

import javax.swing.JComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;

import io.github.welingtonmonteiro.multiplerun.MemoryHistory;
import io.github.welingtonmonteiro.multiplerun.MemoryHistory.Sample;
import io.github.welingtonmonteiro.multiplerun.ProcessStatsSampler;

/**
 * A pop-up with the full memory history of one application over the current session (the monitor's
 * "Mem trend" sparkline only shows the last minute). Shows a larger line chart of RSS over time and
 * can export the series to CSV.
 */
public class MemoryChartDialog extends DialogWrapper {

    private final Project project;
    private final String appName;
    private final List<Sample> samples;

    public MemoryChartDialog(@Nullable Project project, @NotNull String appName, @NotNull List<Sample> samples) {
        super(project);
        this.project = project;
        this.appName = appName;
        this.samples = samples;
        setTitle("Memory history - " + appName);
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        final javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 6));
        final ChartComponent chart = new ChartComponent();
        chart.setPreferredSize(new java.awt.Dimension(640, 300));
        panel.add(chart, java.awt.BorderLayout.CENTER);

        final javax.swing.JButton exportButton = new javax.swing.JButton("Export CSV…");
        exportButton.setEnabled(!samples.isEmpty());
        exportButton.addActionListener(e -> exportCsv());
        final javax.swing.JPanel south = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        south.add(exportButton);
        south.add(new javax.swing.JLabel(samples.size() + " samples"));
        panel.add(south, java.awt.BorderLayout.SOUTH);
        return panel;
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

    /** Simple line chart of RSS over the session, with the peak value labeled. */
    private final class ChartComponent extends JComponent {
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (samples.size() < 2) {
                g2.setColor(com.intellij.ui.JBColor.GRAY);
                g2.drawString("Not enough samples yet", 12, 24);
                return;
            }
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            long maxRss = 0;
            for (Sample sample : samples) {
                maxRss = Math.max(maxRss, sample.rssKb);
            }
            final long scaleMax = Math.max(maxRss, 1);
            final int left = 8;
            final int right = 8;
            final int top = 8;
            final int bottom = 20;
            final int width = Math.max(getWidth() - left - right, 1);
            final int height = Math.max(getHeight() - top - bottom, 1);

            // baseline and peak label
            g2.setColor(com.intellij.ui.JBColor.GRAY);
            g2.drawLine(left, top + height, left + width, top + height);
            g2.drawString("peak " + ProcessStatsSampler.formatMemory(maxRss), left + 4, top + 14);

            final int[] xs = new int[samples.size()];
            final int[] ys = new int[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                xs[i] = left + (int) Math.round((double) i * width / (samples.size() - 1));
                ys[i] = top + (int) Math.round(height - (double) samples.get(i).rssKb / scaleMax * height);
            }
            final double lastPercent = samples.get(samples.size() - 1).percent;
            g2.setColor(lastPercent >= 90 ? com.intellij.ui.JBColor.RED
                                          : lastPercent >= 70 ? com.intellij.ui.JBColor.ORANGE
                                                              : com.intellij.ui.JBColor.GREEN);
            g2.drawPolyline(xs, ys, samples.size());
        }
    }
}
