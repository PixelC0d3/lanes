package io.github.pixelcodes.lanes.ui

import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute

import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.UIUtil

import io.github.pixelcodes.lanes.RunConfigurationHelper
import io.github.pixelcodes.lanes.ui.LanesMonitorPanel.Companion.parsePorts

/**
 * Table cell renderers of the Lanes Monitor.
 *
 * They were inner declarations of [LanesMonitorPanel] but never touched its state, and the panel
 * had grown past 1800 lines - which is where most of the recent bugs landed. Pure presentation
 * lives here; the panel keeps the behaviour.
 */

internal class EnvProfileListRenderer(private val activePaths: Set<String>) : SimpleListCellRenderer<String>() {
    override fun customize(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value != null) {
            setText(RunConfigurationHelper.envFileDisplayName(value) + (if (activePaths.contains(value)) "  (active)" else ""))
            setToolTipText(value)
        }
    }
}

internal class PortsCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val text = value?.toString() ?: ""
        val hasPorts = parsePorts(text).isNotEmpty()
        if (hasPorts) {
            if (!isSelected) {
                setForeground(JBColor.BLUE)
            }
            val attributes = HashMap(getFont().getAttributes())
            attributes[TextAttribute.UNDERLINE] = TextAttribute.UNDERLINE_ON
            setFont(getFont().deriveFont(attributes))
            setToolTipText("Click to open in the browser")
        } else {
            setToolTipText(null)
        }
        return this
    }
}

internal class EnvCellRenderer(private val clickable: Boolean, private val switchable: Boolean) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (switchable) {
            setText((value?.toString() ?: "") + "  ▾")
        }
        if (clickable || switchable) {
            if (!isSelected) {
                setForeground(JBColor.BLUE)
            }
            val attributes = HashMap(getFont().getAttributes())
            attributes[TextAttribute.UNDERLINE] = TextAttribute.UNDERLINE_ON
            setFont(getFont().deriveFont(attributes))
            setToolTipText(if (switchable) "Click to switch the group's environment or view the loaded variables"
                           else "Click to view the loaded environment variables")
        } else {
            setToolTipText(null)
        }
        return this
    }
}

internal class StatusCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (!isSelected) {
            val text = value?.toString() ?: ""
            if (text == "down") {
                setForeground(JBColor.RED)
            } else if (text == "healthy" || text == "running") {
                setForeground(JBColor.GREEN)
            }
        }
        return this
    }
}

internal class SparklineCellRenderer : JComponent(), TableCellRenderer {
    private var values: DoubleArray = DoubleArray(0)
    private var selected = false
    private var table: JTable? = null

    /** Set by the table's prepareRenderer for a paused row: the frozen line is drawn muted. */
    var paused = false

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        this.values = value as? DoubleArray ?: DoubleArray(0)
        this.selected = isSelected
        this.table = table
        return this
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val t = table
        if (t != null) {
            g2.setColor(if (selected) t.getSelectionBackground() else t.getBackground())
            g2.fillRect(0, 0, getWidth(), getHeight())
        }
        if (values.size < 2) {
            return
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (value in values) {
            min = minOf(min, value)
            max = maxOf(max, value)
        }
        val span = maxOf(max - min, 0.0001)
        val width = maxOf(getWidth() - 6, 1)
        val height = maxOf(getHeight() - 6, 1)
        val xs = IntArray(values.size)
        val ys = IntArray(values.size)
        for (i in values.indices) {
            xs[i] = 3 + Math.round(i.toDouble() * width / (values.size - 1)).toInt()
            ys[i] = 3 + Math.round(height - (values[i] - min) / span * height).toInt()
        }
        val last = values[values.size - 1]
        g2.setColor(when {
            paused -> UIUtil.getLabelDisabledForeground()
            last >= 90 -> JBColor.RED
            last >= 70 -> JBColor.ORANGE
            else -> JBColor.GREEN
        })
        g2.drawPolyline(xs, ys, values.size)
    }
}
