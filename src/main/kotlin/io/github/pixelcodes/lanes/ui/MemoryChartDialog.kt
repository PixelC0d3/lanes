package io.github.pixelcodes.lanes.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI

import io.github.pixelcodes.lanes.MemoryHistory
import io.github.pixelcodes.lanes.MemoryHistory.Analysis
import io.github.pixelcodes.lanes.MemoryHistory.Sample
import io.github.pixelcodes.lanes.ProcessStatsSampler

/**
 * A pop-up with the full memory history of one application over the current session (the monitor's
 * "Mem trend" sparkline only shows the last minute). The Chart tab draws RSS over time with
 * labeled X (elapsed time) and Y (memory) axes and can export the series to CSV; the Analysis
 * tab summarizes the memory trend (a possible-leak verdict) and breaks the memory down by process
 * of the application's tree, so a runaway child process is easy to spot.
 */
class MemoryChartDialog(
    private val project: Project?,
    private val appName: String,
    private val samples: List<Sample>,
    private val rootPid: Long,
    private val analysisFirst: Boolean,
) : DialogWrapper(project) {

    private val analysis: Analysis = MemoryHistory.analyze(samples)

    private val breakdownModel = BreakdownTableModel()

    /** Every process sampled in the last breakdown, kept so the filter can narrow the view. */
    private var allBreakdownRows: List<BreakdownRow> = ArrayList()
    private var breakdownFilter: ExtendableTextField? = null

    init {
        setTitle("Memory history - $appName")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("Chart", createChartTab())
        tabs.addTab("Analysis", createAnalysisTab())
        tabs.setPreferredSize(Dimension(JBUI.scale(700), JBUI.scale(440)))
        if (analysisFirst) {
            tabs.setSelectedIndex(1) // open straight on Analysis when requested
        }
        return tabs
    }

    private fun createChartTab(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        val chart = ChartComponent()
        chart.setPreferredSize(Dimension(JBUI.scale(660), JBUI.scale(300)))
        panel.add(chart, BorderLayout.CENTER)

        val exportButton = JButton("Export CSV…")
        exportButton.setEnabled(samples.isNotEmpty())
        exportButton.addActionListener { exportCsv() }
        val south = JPanel(FlowLayout(FlowLayout.LEFT))
        south.add(exportButton)
        south.add(JLabel("${samples.size} samples"))
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    private fun createAnalysisTab(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.setBorder(JBUI.Borders.empty(8))

        val summary = JBLabel(leakSummaryHtml(analysis))
        summary.setVerticalAlignment(JLabel.TOP)
        panel.add(summary, BorderLayout.NORTH)

        // center: a filter over the per-process breakdown table
        val center = JPanel(BorderLayout(0, JBUI.scale(4)))
        val filter = ExtendableTextField()
        filter.getEmptyText().setText("Filter processes by PID or command")
        filter.getDocument().addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyBreakdownFilter()
            }
        })
        breakdownFilter = filter
        center.add(filter, BorderLayout.NORTH)

        val table = JBTable(breakdownModel)
        table.getEmptyText().setText("Sampling process tree…")
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(70))
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(360))
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(90))
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(70))
        center.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)

        val refresh = JButton("Refresh")
        refresh.addActionListener { sampleBreakdown() }
        val export = JButton("Export analysis…")
        export.addActionListener { exportAnalysis() }
        val south = JPanel(FlowLayout(FlowLayout.LEFT))
        south.add(refresh)
        south.add(export)
        panel.add(south, BorderLayout.SOUTH)

        sampleBreakdown()
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(getOKAction())
    }

    /** Samples the current RSS of every process in the tree, off the EDT, and fills the table. */
    private fun sampleBreakdown() {
        breakdownModel.setRows(ArrayList()) // clear while re-sampling
        ApplicationManager.getApplication().executeOnPooledThread {
            val tree = ProcessStatsSampler.processTreePids(rootPid)
            val stats = ProcessStatsSampler.samplePids(tree)
            var total = 0L
            for (s in stats.values) {
                total += s.rssKb
            }
            val totalKb = total
            val rows = ArrayList<BreakdownRow>()
            for (pid in tree) {
                val s = stats[pid]
                val rssKb = s?.rssKb ?: 0L
                rows.add(BreakdownRow(pid, commandOf(pid), rssKb,
                                      if (totalKb > 0) rssKb * 100.0 / totalKb else 0.0))
            }
            rows.sortByDescending { it.rssKb } // heaviest first
            // ModalityState.any(): this dialog is modal, so a default-modality invokeLater would
            // never run until it closes - which is why the table used to stay on "Sampling…".
            ApplicationManager.getApplication().invokeLater({
                allBreakdownRows = rows
                applyBreakdownFilter()
            }, ModalityState.any())
        }
    }

    /** Narrows the breakdown table to processes whose PID or command matches the filter text. */
    private fun applyBreakdownFilter() {
        val filter = breakdownFilter?.getText() ?: ""
        if (filter.isBlank()) {
            breakdownModel.setRows(ArrayList(allBreakdownRows))
            return
        }
        val needle = filter.trim().lowercase(Locale.ROOT)
        val filtered = ArrayList<BreakdownRow>()
        for (row in allBreakdownRows) {
            if (row.pid.toString().contains(needle) || row.command.lowercase(Locale.ROOT).contains(needle)) {
                filtered.add(row)
            }
        }
        breakdownModel.setRows(filtered)
    }

    /** Writes the leak analysis plus the current per-process breakdown to a text report. */
    private fun exportAnalysis() {
        // NOTE: the (title, description) ctor does not exist on the 2023.3 baseline, so this
        // compiles to the varargs ctor either way; keep the extension for the save dialog.
        val descriptor = FileSaverDescriptor(
            "Export Memory Analysis", "Save the memory analysis as a text report", "txt")
        val wrapper: VirtualFileWrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "${sanitize(appName)}-memory-analysis.txt") ?: return
        val sb = StringBuilder(MemoryHistory.summaryText(appName, analysis))
        sb.append('\n').append("Per-process breakdown (sampled):\n")
        if (allBreakdownRows.isEmpty()) {
            sb.append("  (not sampled yet)\n")
        } else {
            for (row in allBreakdownRows) {
                sb.append(String.format(Locale.US, "  PID %-8d %6s (%.1f%%)  %s%n",
                                        row.pid, ProcessStatsSampler.formatMemory(row.rssKb),
                                        row.percentOfTree, row.command))
            }
        }
        try {
            Files.write(wrapper.getFile().toPath(), sb.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, "Could not write the file: ${ex.message}", "Export Analysis")
        }
    }

    private fun exportCsv() {
        val descriptor = FileSaverDescriptor("Export Memory History", "Save the memory history as CSV", "csv")
        val wrapper: VirtualFileWrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "${sanitize(appName)}-memory.csv") ?: return
        try {
            Files.write(wrapper.getFile().toPath(), MemoryHistory.toCsv(samples).toByteArray(StandardCharsets.UTF_8))
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, "Could not write the file: ${ex.message}", "Export CSV")
        }
    }

    /** One process of the application's tree in the breakdown table. */
    private class BreakdownRow(val pid: Long, val command: String, val rssKb: Long, val percentOfTree: Double)

    /** Table model for the per-process memory breakdown (PID, Command, Memory, % of tree). */
    private class BreakdownTableModel : AbstractTableModel() {
        private var rows: List<BreakdownRow> = ArrayList()

        fun setRows(rows: List<BreakdownRow>) {
            this.rows = rows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "PID"
            1 -> "Command"
            2 -> "Memory"
            else -> "% of tree"
        }

        override fun isCellEditable(row: Int, col: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.pid
                1 -> r.command
                2 -> ProcessStatsSampler.formatMemory(r.rssKb)
                else -> String.format(Locale.US, "%.1f%%", r.percentOfTree)
            }
        }
    }

    /**
     * Line chart of RSS over the session with labeled axes: X is elapsed time (from the first
     * sample), Y is memory (RSS). Grid lines and tick labels make the scale readable; the peak is
     * annotated and the line color reflects the latest memory percentage.
     */
    private inner class ChartComponent : JComponent() {
        private val LEFT = 66
        private val RIGHT = 16
        private val TOP = 18
        private val BOTTOM = 42

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setColor(getBackground())
            g2.fillRect(0, 0, getWidth(), getHeight())
            if (samples.size < 2) {
                g2.setColor(JBColor.GRAY)
                g2.drawString("Not enough samples yet", LEFT, TOP + 12)
                return
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            var maxRss = 0L
            for (sample in samples) {
                maxRss = maxOf(maxRss, sample.rssKb)
            }
            val scaleMax = maxOf(maxRss * 1.05, 1.0) // 5% headroom above the peak
            val durationMs = maxOf(samples[samples.size - 1].timeMs - samples[0].timeMs, 1L)

            val plotW = maxOf(getWidth() - LEFT - RIGHT, 1)
            val plotH = maxOf(getHeight() - TOP - BOTTOM, 1)
            val axisX = LEFT
            val axisYBottom = TOP + plotH

            val baseFont = g2.getFont()
            val small = baseFont.deriveFont(baseFont.getSize2D() - 1f)
            g2.setFont(small)
            val fm = g2.getFontMetrics()

            // Y grid + tick labels (memory)
            for (i in 0..4) {
                val f = i / 4.0
                val y = axisYBottom - Math.round(f * plotH).toInt()
                g2.setColor(JBColor.border())
                g2.drawLine(axisX, y, axisX + plotW, y)
                val label = ProcessStatsSampler.formatMemory((scaleMax * f).toLong())
                g2.setColor(JBColor.GRAY)
                g2.drawString(label, axisX - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1)
            }
            // X tick labels (elapsed time)
            for (i in 0..4) {
                val f = i / 4.0
                val x = axisX + Math.round(f * plotW).toInt()
                g2.setColor(JBColor.GRAY)
                g2.drawLine(x, axisYBottom, x, axisYBottom + 3)
                val label = ProcessStatsSampler.formatUptime((durationMs * f).toLong())
                var lx = x - fm.stringWidth(label) / 2
                lx = maxOf(axisX, minOf(lx, axisX + plotW - fm.stringWidth(label)))
                g2.drawString(label, lx, axisYBottom + 3 + fm.getAscent() + 1)
            }

            // axes
            g2.setColor(JBColor.foreground())
            g2.drawLine(axisX, TOP, axisX, axisYBottom)
            g2.drawLine(axisX, axisYBottom, axisX + plotW, axisYBottom)

            // axis titles
            g2.setColor(JBColor.GRAY)
            val xTitle = "Elapsed time"
            g2.drawString(xTitle, axisX + (plotW - fm.stringWidth(xTitle)) / 2, getHeight() - 4)
            val yTitle = "Memory (RSS)"
            val saved = g2.getTransform()
            g2.rotate(-Math.PI / 2, 12.0, TOP + plotH / 2.0)
            g2.drawString(yTitle, 12 - fm.stringWidth(yTitle) / 2, TOP + plotH / 2 + fm.getAscent())
            g2.setTransform(saved)

            // peak annotation
            g2.setColor(JBColor.GRAY)
            g2.drawString("peak " + ProcessStatsSampler.formatMemory(maxRss), axisX + 6, TOP + fm.getAscent())

            // the series
            val xs = IntArray(samples.size)
            val ys = IntArray(samples.size)
            for (i in samples.indices) {
                xs[i] = axisX + Math.round(i.toDouble() * plotW / (samples.size - 1)).toInt()
                ys[i] = axisYBottom - Math.round(samples[i].rssKb / scaleMax * plotH).toInt()
            }
            val lastPercent = samples[samples.size - 1].percent
            g2.setColor(if (lastPercent >= 90) JBColor.RED
                        else if (lastPercent >= 70) JBColor.ORANGE
                        else JBColor.GREEN)
            g2.drawPolyline(xs, ys, samples.size)
            g2.setFont(baseFont)
        }
    }

    companion object {
        /** Builds an HTML summary of the memory trend, coloring the verdict. */
        private fun leakSummaryHtml(a: Analysis): String {
            if (a.trend == MemoryHistory.Trend.INSUFFICIENT_DATA) {
                return "<html><b>Leak analysis</b><br>Not enough samples yet - keep the application " +
                    "running to collect a trend.</html>"
            }
            val color = if (a.trend == MemoryHistory.Trend.GROWING) "#D9534F" else "#5CB85C"
            val sign = if (a.netChangeKb >= 0) "+" else "-"
            val hourSign = if (a.projectedPerHourKb >= 0) "+" else "-"
            return "<html><b>Leak analysis</b><br>" +
                "Verdict: <b><font color='$color'>${MemoryHistory.verdictText(a)}</font></b><br>" +
                "Trend: " + String.format(Locale.US, "%+.1f MiB/min", a.slopeKbPerMin / 1024.0) +
                " (~ $hourSign${ProcessStatsSampler.formatMemory(Math.abs(a.projectedPerHourKb))}/h)" +
                ", R²=" + String.format(Locale.US, "%.2f", a.rSquared) +
                ", memory not freed " + String.format(Locale.US, "%.0f%%", a.monotonicFraction * 100) +
                " of the time<br>" +
                "Duration: " + ProcessStatsSampler.formatUptime(a.durationMs) +
                " (${a.samples} samples)<br>" +
                "First → last: " + ProcessStatsSampler.formatMemory(a.firstRssKb) + " → " +
                ProcessStatsSampler.formatMemory(a.lastRssKb) +
                " ($sign${ProcessStatsSampler.formatMemory(Math.abs(a.netChangeKb))})<br>" +
                "Min / peak: " + ProcessStatsSampler.formatMemory(a.minRssKb) + " / " +
                ProcessStatsSampler.formatMemory(a.maxRssKb) + "</html>"
        }

        /** Best-effort readable command for a pid: command line, else executable, else the pid. */
        private fun commandOf(pid: Long): String {
            return ProcessHandle.of(pid)
                .map { it.info() }
                .map { info -> info.commandLine().orElse(info.command().orElse("PID $pid")) }
                .orElse("PID $pid (ended)")
        }

        private fun sanitize(name: String?): String {
            return if (name == null) "app" else name.trim().replace(Regex("[^a-zA-Z0-9-_.]"), "_")
        }
    }
}
