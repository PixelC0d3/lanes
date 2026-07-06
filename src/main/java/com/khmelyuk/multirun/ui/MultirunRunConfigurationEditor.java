package com.khmelyuk.multirun.ui;

import com.intellij.execution.RunManager;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import com.khmelyuk.multirun.MultirunRunConfiguration;
import com.khmelyuk.multirun.RunConfigurationHelper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * For to edit multirun run configuration.
 *
 * @author Ruslan Khmelyuk
 */
@SuppressWarnings("unchecked")
public class MultirunRunConfigurationEditor extends SettingsEditor<MultirunRunConfiguration> {

    private Project project;
    private JPanel myMainPanel;
    private TableView<RunConfiguration> configurations;
    private ListTableModel<RunConfiguration> configurationsModel;
    private JPanel collectionsPanel;
    private JPanel envVarsPanel;
    private EnvironmentVariablesComponent environmentVariables;
    /** Editable combo with the known .env files (profiles); its text is the active profile. */
    private JComboBox<String> envFileCombo;
    private TextFieldWithBrowseButton saveOutputDir;
    private JCheckBox reuseTabs;
    private JCheckBox reuseTabsWithFailure;
    private JCheckBox startOneByOne;
    private JCheckBox restartRunning;
    private JCheckBox markFailedProcess;
    private JCheckBox hideSuccessProcess;
    private JCheckBox configurationsListChanged;
    private JTextField delayTime;
    private JCheckBox restartOnCrashBox;
    private JSpinner memThresholdSpinner;
    private JComboBox<String> memLimitActionCombo;
    private MultirunRunConfiguration configuration;
    /** Per-child memory (heap) cap in MB, edited inline in the "Memory limit" table column. */
    private Map<String, Integer> memoryLimits = new LinkedHashMap<>();
    /** Apps unchecked in the list: kept in the configuration but not launched. */
    private java.util.Set<String> disabledApps = new java.util.LinkedHashSet<>();
    /** Per-child readiness condition, edited inline in the "Ready when" column. */
    private Map<String, String> readyConditions = new LinkedHashMap<>();

    public MultirunRunConfigurationEditor(final Project project) {
        this.project = project;
    }

    @Override
    protected void resetEditorFrom(@Nullable MultirunRunConfiguration multirunRunConfiguration) {
        if (multirunRunConfiguration != null) {
            this.configuration = multirunRunConfiguration;
        }

        if (this.configuration != null) {
            memoryLimits = this.configuration.getMemoryLimits();
            disabledApps = this.configuration.getDisabledApps();
            readyConditions = this.configuration.getReadyConditions();
            configurationsModel.setItems(new ArrayList<>(this.configuration.getRunConfigurations()));
            environmentVariables.setEnvData(this.configuration.getEnvData());
            final DefaultComboBoxModel<String> profilesModel = (DefaultComboBoxModel<String>) envFileCombo.getModel();
            profilesModel.removeAllElements();
            for (String profile : this.configuration.getEnvProfiles()) {
                profilesModel.addElement(profile);
            }
            envFileCombo.setSelectedItem(this.configuration.getEnvFilePath());
            saveOutputDir.setText(this.configuration.getSaveOutputDir());
            delayTime.setText(String.format("%.1f", this.configuration.getDelayTime()));
            reuseTabs.setSelected(this.configuration.isReuseTabs());
            reuseTabsWithFailure.setSelected(this.configuration.isReuseTabsWithFailure());
            startOneByOne.setSelected(this.configuration.isStartOneByOne());
            restartRunning.setSelected(this.configuration.isRestartRunning());
            markFailedProcess.setSelected(this.configuration.isMarkFailedProcess());
            hideSuccessProcess.setSelected(this.configuration.isHideSuccessProcess());
            restartOnCrashBox.setSelected(this.configuration.isRestartOnCrash());
            memThresholdSpinner.setValue(this.configuration.getMemAlertThreshold());
            memLimitActionCombo.setSelectedIndex(this.configuration.isMemLimitRestart() ? 1 : 0);
            delayTime.setEnabled(startOneByOne.isSelected());
        }
    }

    @Override
    protected void applyEditorTo(@Nullable MultirunRunConfiguration multirunRunConfiguration) {
        if (multirunRunConfiguration == null) {
            return;
        }

        multirunRunConfiguration.setEnvData(environmentVariables.getEnvData());
        final String activeEnvFile = envFileComboText();
        multirunRunConfiguration.setEnvFilePath(activeEnvFile);
        final java.util.List<String> envProfiles = new ArrayList<>();
        for (int i = 0; i < envFileCombo.getItemCount(); i++) {
            envProfiles.add(envFileCombo.getItemAt(i));
        }
        if (!activeEnvFile.isEmpty() && !envProfiles.contains(activeEnvFile)) {
            // a path typed by hand becomes a profile too
            envProfiles.add(activeEnvFile);
        }
        multirunRunConfiguration.setEnvProfiles(envProfiles);
        multirunRunConfiguration.setSaveOutputDir(saveOutputDir.getText());
        multirunRunConfiguration.setMemoryLimits(memoryLimits);
        multirunRunConfiguration.setDisabledApps(disabledApps);
        multirunRunConfiguration.setReadyConditions(readyConditions);
        multirunRunConfiguration.setReuseTabs(reuseTabs.isSelected());
        multirunRunConfiguration.setReuseTabsWithFailure(reuseTabsWithFailure.isSelected());
        multirunRunConfiguration.setStartOneByOne(startOneByOne.isSelected());
        multirunRunConfiguration.setRestartRunning(restartRunning.isSelected());
        multirunRunConfiguration.setMarkFailedProcess(markFailedProcess.isSelected());
        multirunRunConfiguration.setHideSuccessProcess(hideSuccessProcess.isSelected());
        multirunRunConfiguration.setRestartOnCrash(restartOnCrashBox.isSelected());
        multirunRunConfiguration.setMemAlertThreshold((Integer) memThresholdSpinner.getValue());
        multirunRunConfiguration.setMemLimitRestart(memLimitActionCombo.getSelectedIndex() == 1);
        double delayTimeSeconds = 0;
        if (delayTime.getText() != null && !delayTime.getText().isEmpty()) {
            try {
                // Accepts both '.' and ',' so it matches the locale-formatted value shown by
                // resetEditorFrom (e.g. "0,0" in pt-BR/German) as well as hand-typed "0.5".
                delayTimeSeconds = MultirunRunConfiguration.parseDelay(delayTime.getText());
            } catch (NumberFormatException e) {
                // well ignore if the value is not a number
            }
        }
        multirunRunConfiguration.setDelayTime(delayTimeSeconds);

        // commit a possibly in-progress cell edit so typed limits are not lost on Apply/Run
        if (configurations.isEditing()) {
            configurations.stopEditing();
        }
        MultirunRunConfigurationEditor.this.configuration.setRunConfigurations(configurationsModel.getItems());
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        configurationsModel = new ListTableModel<>(new EnabledColumn(), new ConfigurationColumn(),
                                                   new MemoryLimitColumn(), new ReadyWhenColumn());
        configurations = new TableView<>(configurationsModel);
        configurations.setShowGrid(false);
        configurations.getEmptyText().setText("Add run configurations to this list");
        // commit the cell editor when the table loses focus, so typed limits are kept
        configurations.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        configurationsModel.addTableModelListener(e -> {
            if (MultirunRunConfigurationEditor.this.configuration != null) {
                configuration.setRunConfigurations(configurationsModel.getItems());
            }
            fireEditorStateChanged();
        });
        final ToolbarDecorator myDecorator = ToolbarDecorator.createDecorator(configurations);
        myDecorator.initPosition();

        myDecorator.setRemoveAction(anActionButton -> {
            TableUtil.removeSelectedItems(configurations);
            markConfigurationsChanged();
        });
        myDecorator.setAddAction(button -> {
            final JBList<RunConfiguration> list = new JBList<>(getConfigurationsToAdd());
            list.setCellRenderer(new RunConfigurationListCellRenderer());
            new PopupChooserBuilder<>(list)
                    .setItemChosenCallback(() -> {
                        int[] selectedIndices = list.getSelectedIndices();
                        for (int index : selectedIndices) {
                            RunConfiguration selectedRunConfiguration = list.getModel().getElementAt(index);
                            if (selectedRunConfiguration != null) {
                                configurationsModel.addRow(selectedRunConfiguration);
                            }

                            markConfigurationsChanged();
                        }
                    })
                    .createPopup()
                    .showUnderneathOf(button.getContextComponent());
        });
        myDecorator.setAddActionUpdater(e -> !getConfigurationsToAdd().isEmpty());

        startOneByOne.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                delayTime.setEnabled(startOneByOne.isSelected());
            }
        });

        // Environment variables applied to every configuration in the list (Multirun values win on conflicts).
        // The component provides the same editing dialog used by the platform run configurations.
        environmentVariables = new EnvironmentVariablesComponent();
        environmentVariables.setLabelLocation(BorderLayout.WEST);
        environmentVariables.getComponent().setToolTipText(
                "These variables are applied to every configuration in the list, overriding its own variables with the same name");
        envVarsPanel.add(environmentVariables, BorderLayout.CENTER);

        // Optional .env file applied under the variables above (the table values win on conflicts).
        // Read again on every run, so file edits are picked up without touching the configuration.
        // The editable combo keeps every file ever used as an "environment profile", so switching
        // between .env files (-com / -ede / -def, ...) is a two-click dropdown choice.
        envFileCombo = new com.intellij.openapi.ui.ComboBox<>(new DefaultComboBoxModel<>());
        envFileCombo.setEditable(true);
        envFileCombo.setToolTipText(
                "Active .env file (KEY=VALUE lines, # comments, optional \"export\" prefix). "
                        + "Applied to every configuration in the list; variables configured above win on conflicts. "
                        + "Relative paths are resolved against the project root. Every file used once stays "
                        + "in this dropdown as a profile - switch environments without retyping paths");
        envFileCombo.addActionListener(e -> fireEditorStateChanged());

        final com.intellij.openapi.ui.FixedSizeButton browseEnvFile =
                new com.intellij.openapi.ui.FixedSizeButton(envFileCombo);
        browseEnvFile.setToolTipText("Select a .env file and add it to the profile list");
        browseEnvFile.addActionListener(e -> {
            final VirtualFile chosen = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFileDescriptor()
                                                .withTitle("Select Environment File")
                                                // .env files are dotfiles, hidden by the chooser by default
                                                .withShowHiddenFiles(true),
                    project, null);
            if (chosen != null) {
                final String path = chosen.getPresentableUrl();
                final DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) envFileCombo.getModel();
                if (model.getIndexOf(path) < 0) {
                    model.addElement(path);
                }
                envFileCombo.setSelectedItem(path);
            }
        });

        final com.intellij.openapi.ui.FixedSizeButton removeEnvProfile =
                new com.intellij.openapi.ui.FixedSizeButton(envFileCombo);
        removeEnvProfile.setIcon(com.intellij.icons.AllIcons.General.Remove);
        removeEnvProfile.setToolTipText("Remove the selected profile from the list");
        removeEnvProfile.addActionListener(e -> {
            final Object selected = envFileCombo.getSelectedItem();
            if (selected != null && !selected.toString().isEmpty()) {
                ((DefaultComboBoxModel<String>) envFileCombo.getModel()).removeElement(selected);
                envFileCombo.setSelectedItem("");
            }
        });

        final JPanel envProfileButtons = new JPanel(new GridLayout(1, 2, 2, 0));
        envProfileButtons.add(browseEnvFile);
        envProfileButtons.add(removeEnvProfile);
        final JPanel envProfilePanel = new JPanel(new BorderLayout(4, 0));
        envProfilePanel.add(envFileCombo, BorderLayout.CENTER);
        envProfilePanel.add(envProfileButtons, BorderLayout.EAST);
        final LabeledComponent<JPanel> envFileComponent =
                LabeledComponent.create(envProfilePanel, "Environment file (profile):");
        envFileComponent.setLabelLocation(BorderLayout.WEST);

        // Optional folder where each configuration's console is also saved as <name>.log,
        // through the IDE's native "save console output to file" mechanism (Logs tab).
        saveOutputDir = new TextFieldWithBrowseButton();
        saveOutputDir.getTextField().setToolTipText(
                "When set, the console output of every configuration in the list is also saved to this folder "
                        + "as <configuration name>.log - same mechanism as the Logs tab of individual run "
                        + "configurations. Relative paths are resolved against the project root");
        saveOutputDir.addActionListener(e -> {
            final VirtualFile chosen = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                                .withTitle("Select Folder for Console Logs")
                                                .withShowHiddenFiles(true),
                    project, null);
            if (chosen != null) {
                saveOutputDir.setText(chosen.getPresentableUrl());
            }
        });
        final LabeledComponent<TextFieldWithBrowseButton> saveOutputComponent =
                LabeledComponent.create(saveOutputDir, "Save console logs to:");
        saveOutputComponent.setLabelLocation(BorderLayout.WEST);

        // Restart policies (docker-like): crash restart + action when the memory limit is reached
        restartOnCrashBox = new JCheckBox("Restart application on crash (max 3 attempts)");
        restartOnCrashBox.setToolTipText(
                "Like docker restart: on-failure - an application that exits with a crash code is "
                        + "relaunched automatically, at most 3 times per run. Stops via the stop buttons "
                        + "(SIGTERM/SIGINT/SIGKILL) never trigger a restart");
        memThresholdSpinner = new JSpinner(new SpinnerNumberModel(90, 10, 100, 5));
        memLimitActionCombo = new JComboBox<>(new String[]{"Notify", "Restart application"});
        memLimitActionCombo.setToolTipText(
                "What to do when an application with a Memory limit crosses the threshold: "
                        + "show a warning notification or restart it (docker-like OOM handling)");
        final JPanel policyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        policyPanel.add(restartOnCrashBox);
        policyPanel.add(new JLabel("   At"));
        policyPanel.add(memThresholdSpinner);
        policyPanel.add(new JLabel("% of the memory limit:"));
        policyPanel.add(memLimitActionCombo);

        final JPanel filesPanel = new JPanel(new GridLayout(3, 1));
        filesPanel.add(envFileComponent);
        filesPanel.add(saveOutputComponent);
        filesPanel.add(policyPanel);
        envVarsPanel.add(filesPanel, BorderLayout.SOUTH);

        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        JPanel myDecoratorPanel = myDecorator.createPanel();
        panel.add(myDecoratorPanel, BorderLayout.CENTER);
        collectionsPanel.add(myDecoratorPanel);

        configurationsListChanged.setVisible(false);

        return myMainPanel;
    }

    /** The text of the (possibly in-edition) env profile combo editor. */
    private String envFileComboText() {
        final Object editorItem = envFileCombo.getEditor().getItem();
        return editorItem == null ? "" : editorItem.toString().trim();
    }

    private void markConfigurationsChanged() {
        // use hidden checkbox to fire the modified event;
        configurationsListChanged.setSelected(!configurationsListChanged.isSelected());
    }

    @Override
    protected void disposeEditor() {
    }

    /** Checkbox column: unchecked applications stay in the list but are not launched. */
    private class EnabledColumn extends ColumnInfo<RunConfiguration, Boolean> {
        EnabledColumn() {
            super("On");
        }

        @Override
        public Class<?> getColumnClass() {
            return Boolean.class;
        }

        @Override
        public Boolean valueOf(RunConfiguration configuration) {
            return !disabledApps.contains(configuration.getName());
        }

        @Override
        public boolean isCellEditable(RunConfiguration configuration) {
            return true;
        }

        @Override
        public void setValue(RunConfiguration configuration, Boolean enabled) {
            if (Boolean.FALSE.equals(enabled)) {
                disabledApps.add(configuration.getName());
            } else {
                disabledApps.remove(configuration.getName());
            }
            markConfigurationsChanged();
        }

        @Override
        public String getTooltipText() {
            return "Unchecked applications are kept in the list but not launched";
        }

        @Override
        public int getWidth(JTable table) {
            return 40;
        }
    }

    /** First table column: icon + "Run 'name'", read-only. */
    private static class ConfigurationColumn extends ColumnInfo<RunConfiguration, String> {
        ConfigurationColumn() {
            super("Run Configuration");
        }

        @Override
        public String valueOf(RunConfiguration configuration) {
            return "Run '" + configuration.getName() + "'";
        }

        @Override
        public TableCellRenderer getRenderer(final RunConfiguration configuration) {
            return new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                               boolean hasFocus, int row, int column) {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    setIcon(configuration.getIcon());
                    return this;
                }
            };
        }
    }

    /** Second table column: per-application memory (heap) cap in MB, edited inline. */
    private class MemoryLimitColumn extends ColumnInfo<RunConfiguration, String> {
        MemoryLimitColumn() {
            super("Memory limit (MB)");
        }

        @Override
        public String valueOf(RunConfiguration configuration) {
            final Integer limitMb = memoryLimits.get(configuration.getName());
            return limitMb == null ? "" : String.valueOf(limitMb);
        }

        @Override
        public boolean isCellEditable(RunConfiguration configuration) {
            return true;
        }

        @Override
        public void setValue(RunConfiguration configuration, String value) {
            try {
                final int parsed = value == null || value.trim().isEmpty() ? 0 : Integer.parseInt(value.trim());
                if (parsed <= 0) {
                    memoryLimits.remove(configuration.getName());
                } else {
                    memoryLimits.put(configuration.getName(), parsed);
                }
                markConfigurationsChanged();
            } catch (NumberFormatException ignored) {
                // non-numeric input keeps the previous value
            }
        }

        @Override
        public String getTooltipText() {
            return "Heap cap applied at launch as NODE_OPTIONS --max-old-space-size (Node.js) and "
                    + "JAVA_TOOL_OPTIONS -Xmx (JVM) - the process-level analog of docker's mem_limit. "
                    + "Empty = no limit";
        }

        @Override
        public int getWidth(JTable table) {
            return 140;
        }
    }

    /** "Ready when" column: docker-compose-like readiness gate used by one-by-one starts. */
    private class ReadyWhenColumn extends ColumnInfo<RunConfiguration, String> {
        ReadyWhenColumn() {
            super("Ready when");
        }

        @Override
        public String valueOf(RunConfiguration configuration) {
            final String condition = readyConditions.get(configuration.getName());
            return condition == null ? "" : condition;
        }

        @Override
        public boolean isCellEditable(RunConfiguration configuration) {
            return true;
        }

        @Override
        public void setValue(RunConfiguration configuration, String value) {
            if (value == null || value.trim().isEmpty()) {
                readyConditions.remove(configuration.getName());
            } else {
                readyConditions.put(configuration.getName(), value.trim());
            }
            markConfigurationsChanged();
        }

        @Override
        public String getTooltipText() {
            return "With 'Start one by one', the next application only starts after this one is ready. "
                    + "Syntax: port:3003 (TCP port open), http://localhost:3003/health (HTTP 2xx/3xx) "
                    + "or log:Server started (console output contains the text). Empty = no waiting. "
                    + "Port/http conditions also feed the Status column of the Multiple Run Monitor";
        }

        @Override
        public int getWidth(JTable table) {
            return 180;
        }
    }

    /** Renderer for the "add configuration" popup list. */
    private static class RunConfigurationListCellRenderer extends SimpleListCellRenderer<RunConfiguration> {
        @Override
        public void customize(@NotNull JList<? extends RunConfiguration> list, RunConfiguration data, int index,
                              boolean selected, boolean hasFocus) {
            if (data != null) {
                setIcon(data.getIcon());
                setText("Run '" + data.getName() + "'");
            }
        }
    }

    private java.util.List<RunConfiguration> getConfigurationsToAdd() {
        java.util.List<RunConfiguration> result = new ArrayList<>();
        if (this.configuration == null) {
            return result;
        }

        java.util.List<RunConfiguration> allConfigurations = RunManager.getInstance(project).getAllConfigurationsList();
        for (RunConfiguration configuration : allConfigurations) {
            if (this.configuration.equals(configuration)) {
                // skip current
                continue;
            }
            if (this.configuration.getRunConfigurations().contains(configuration)) {
                // skip already added
                continue;
            }
            if (configuration instanceof MultirunRunConfiguration) {
                // exclude configurations that may cause loopies
                if (RunConfigurationHelper.containsLoopies((MultirunRunConfiguration) configuration, this.configuration)) {
                    continue;
                }
            }
            result.add(configuration);
        }

        return result;
    }

}
