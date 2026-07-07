package io.github.welingtonmonteiro.multiplerun.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

/**
 * Read-only viewer of the environment variables Multiple Run loaded for one application at launch
 * (group variables + group env file + memory-limit options + per-app env file, merged). A filter
 * field at the top narrows the list by variable name; values can be masked for screen sharing.
 * The filtering and masking are pure static methods so they are unit-tested without a UI.
 */
public class EnvVarsDialog extends DialogWrapper {

    private final String appName;
    private final String envFileName;
    private final boolean includeSystemEnv;
    private final List<Map.Entry<String, String>> allEntries;

    private final EnvTableModel tableModel = new EnvTableModel();
    private SearchTextField filterField;
    private JBCheckBox maskValues;

    public EnvVarsDialog(@Nullable Project project, @NotNull String appName, @NotNull String envFileName,
                         boolean includeSystemEnv, @NotNull Map<String, String> env) {
        super(project);
        this.appName = appName;
        this.envFileName = envFileName;
        this.includeSystemEnv = includeSystemEnv;
        this.allEntries = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            allEntries.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        setTitle("Environment - " + appName);
        setModal(true);
        init();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(420)));

        final String subtitle = allEntries.size() + (allEntries.size() == 1 ? " variable" : " variables")
                + (" · env file: " + envFileName)
                + (includeSystemEnv ? " · + system environment" : "");
        final JBLabel header = new JBLabel(subtitle);
        header.setBorder(JBUI.Borders.emptyBottom(2));

        filterField = new SearchTextField();
        filterField.getTextEditor().getEmptyText().setText("Filter by variable name");
        filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                reload();
            }
        });

        final JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(filterField, BorderLayout.CENTER);

        final JBTable table = new JBTable(tableModel);
        table.setShowGrid(false);
        table.getEmptyText().setText("No variables match the filter");
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(200));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(340));

        maskValues = new JBCheckBox("Mask values", false);
        maskValues.addActionListener(e -> reload());

        panel.add(top, BorderLayout.NORTH);
        panel.add(new com.intellij.ui.components.JBScrollPane(table), BorderLayout.CENTER);
        panel.add(maskValues, BorderLayout.SOUTH);

        reload();
        return panel;
    }

    private void reload() {
        final String filter = filterField == null ? "" : filterField.getText();
        final boolean mask = maskValues != null && maskValues.isSelected();
        final List<Map.Entry<String, String>> filtered = filterByName(allEntries, filter);
        final List<Map.Entry<String, String>> display = new ArrayList<>(filtered.size());
        for (Map.Entry<String, String> e : filtered) {
            display.add(new AbstractMap.SimpleImmutableEntry<>(
                    e.getKey(), mask ? maskValue(e.getValue()) : e.getValue()));
        }
        tableModel.setRows(display);
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return filterField;
    }

    @Override
    protected Action @NotNull [] createActions() {
        // read-only viewer: a single Close button, no OK/Cancel semantics
        return new Action[]{getOKAction()};
    }

    /** Case-insensitive filter by variable name; a blank filter returns every entry (order kept). */
    static List<Map.Entry<String, String>> filterByName(List<Map.Entry<String, String>> entries, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return new ArrayList<>(entries);
        }
        final String needle = filter.trim().toLowerCase(Locale.ROOT);
        final List<Map.Entry<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Replaces a value with fixed-length bullets so it can be shown while screen sharing. */
    static String maskValue(String value) {
        return value == null || value.isEmpty() ? "" : "••••••";
    }

    /** Two-column (Name, Value) read-only model backing the table. */
    private static final class EnvTableModel extends AbstractTableModel {
        private List<Map.Entry<String, String>> rows = new ArrayList<>();

        void setRows(List<Map.Entry<String, String>> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Name" : "Value";
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final Map.Entry<String, String> entry = rows.get(rowIndex);
            return columnIndex == 0 ? entry.getKey() : entry.getValue();
        }
    }
}
