package io.github.pixelcodes.lanes.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.util.AbstractMap
import java.util.Locale

import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI

/**
 * Read-only viewer of the environment variables Lanes loaded for one application at launch
 * (group variables + group env file + memory-limit options + per-app env file, merged). A filter
 * field at the top narrows the list by variable name; values can be masked for screen sharing.
 * The filtering and masking are pure static methods so they are unit-tested without a UI.
 */
class EnvVarsDialog(
    project: Project?,
    private val appName: String,
    private val envFileName: String,
    private val includeSystemEnv: Boolean,
    env: Map<String, String>,
) : DialogWrapper(project) {

    private val allEntries: MutableList<Map.Entry<String, String>> = ArrayList()
    private val tableModel = EnvTableModel()
    private var filterField: SearchTextField? = null
    private var maskValues: JBCheckBox? = null

    init {
        for (e in env.entries) {
            allEntries.add(AbstractMap.SimpleImmutableEntry(e.key, e.value))
        }
        setTitle("Environment - $appName")
        setModal(true)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.setPreferredSize(Dimension(JBUI.scale(560), JBUI.scale(420)))

        val subtitle = allEntries.size.toString() + (if (allEntries.size == 1) " variable" else " variables") +
            " · env file: $envFileName" +
            (if (includeSystemEnv) " · + system environment" else "")
        val header = JBLabel(subtitle)
        header.setBorder(JBUI.Borders.emptyBottom(2))

        val field = SearchTextField()
        field.getTextEditor().getEmptyText().setText("Filter by variable name")
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                reload()
            }
        })
        filterField = field

        val top = JPanel(BorderLayout())
        top.add(header, BorderLayout.NORTH)
        top.add(field, BorderLayout.CENTER)

        val table = JBTable(tableModel)
        table.setShowGrid(false)
        table.getEmptyText().setText("No variables match the filter")
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(200))
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(340))

        val mask = JBCheckBox("Mask values", false)
        mask.addActionListener { reload() }
        maskValues = mask

        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(mask, BorderLayout.SOUTH)

        reload()
        return panel
    }

    private fun reload() {
        val filter = filterField?.getText() ?: ""
        val mask = maskValues?.isSelected() ?: false
        val filtered = filterByName(allEntries, filter)
        val display = ArrayList<Map.Entry<String, String>>(filtered.size)
        for (e in filtered) {
            display.add(AbstractMap.SimpleImmutableEntry(e.key, if (mask) maskValue(e.value) else e.value))
        }
        tableModel.setRows(display)
    }

    override fun getPreferredFocusedComponent(): JComponent? = filterField

    override fun createActions(): Array<Action> {
        // read-only viewer: a single Close button, no OK/Cancel semantics
        return arrayOf(getOKAction())
    }

    /** Two-column (Name, Value) read-only model backing the table. */
    private class EnvTableModel : AbstractTableModel() {
        private var rows: List<Map.Entry<String, String>> = ArrayList()

        fun setRows(rows: List<Map.Entry<String, String>>) {
            this.rows = rows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "Name" else "Value"

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = rows[rowIndex]
            return if (columnIndex == 0) entry.key else entry.value
        }
    }

    companion object {
        /** Case-insensitive filter by variable name; a blank filter returns every entry (order kept). */
        @JvmStatic
        fun filterByName(entries: List<Map.Entry<String, String>>, filter: String?): List<Map.Entry<String, String>> {
            if (filter.isNullOrBlank()) {
                return ArrayList(entries)
            }
            val needle = filter.trim().lowercase(Locale.ROOT)
            val result = ArrayList<Map.Entry<String, String>>()
            for (entry in entries) {
                if (entry.key.lowercase(Locale.ROOT).contains(needle)) {
                    result.add(entry)
                }
            }
            return result
        }

        /** Replaces a value with fixed-length bullets so it can be shown while screen sharing. */
        @JvmStatic
        fun maskValue(value: String?): String {
            return if (value.isNullOrEmpty()) "" else "••••••"
        }
    }
}
