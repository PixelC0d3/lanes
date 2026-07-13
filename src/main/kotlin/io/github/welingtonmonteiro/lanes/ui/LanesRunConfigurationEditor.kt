package io.github.welingtonmonteiro.lanes.ui

import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.github.welingtonmonteiro.lanes.ComposeImporter
import io.github.welingtonmonteiro.lanes.LanesIcons
import io.github.welingtonmonteiro.lanes.LanesRunConfiguration
import io.github.welingtonmonteiro.lanes.RunConfigurationHelper

import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.IOException
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * For to edit multirun run configuration.
 *
 * @author Ruslan Khmelyuk
 */
@Suppress("UNCHECKED_CAST")
class LanesRunConfigurationEditor(private val project: Project) : SettingsEditor<LanesRunConfiguration>() {

    // Bound by LanesRunConfigurationEditor.form (GUI Designer) - names/types must match the
    // form's `binding` attributes exactly; the instrumentation weaves $$$setupUI$$$ into <init>.
    private lateinit var myMainPanel: JPanel
    private lateinit var collectionsPanel: JPanel
    private lateinit var envVarsPanel: JPanel
    private lateinit var startOneByOne: JCheckBox
    private lateinit var configurationsListChanged: JCheckBox
    private lateinit var restartRunning: JCheckBox
    private lateinit var markFailedProcess: JCheckBox
    private lateinit var hideSuccessProcess: JCheckBox
    private lateinit var reuseTabs: JCheckBox
    private lateinit var delayTime: JTextField
    private lateinit var reuseTabsWithFailure: JCheckBox

    private lateinit var configurations: TableView<RunConfiguration>
    private lateinit var configurationsModel: ListTableModel<RunConfiguration>
    private lateinit var environmentVariables: EnvironmentVariablesComponent
    /** Editable combo with the known .env files (profiles); its text is the active profile. */
    private lateinit var envFileCombo: JComboBox<String>
    private lateinit var saveOutputDir: TextFieldWithBrowseButton
    private lateinit var restartOnCrashBox: JCheckBox
    private lateinit var memThresholdSpinner: JSpinner
    private lateinit var memLimitActionCombo: JComboBox<String>
    /** Sustained CPU % that triggers an alert (0 = off). */
    private lateinit var cpuAlertSpinner: JSpinner
    private var configuration: LanesRunConfiguration? = null
    /** Per-child memory (heap) cap in MB, edited inline in the "Memory limit" table column. */
    private var memoryLimits: MutableMap<String, Int> = LinkedHashMap()
    /** Apps unchecked in the list: kept in the configuration but not launched. */
    private var disabledApps: MutableSet<String> = LinkedHashSet()
    /** Per-child readiness condition, edited inline in the "Ready when" column. */
    private var readyConditions: MutableMap<String, String> = LinkedHashMap()
    /** Per-child env file overriding the group environment, edited inline in the "Env file (app)" column. */
    private var appEnvFiles: MutableMap<String, String> = LinkedHashMap()
    /** Named execution presets (On/Off + env profile), chosen from the "Preset" dropdown. */
    private lateinit var presetCombo: JComboBox<String>
    private var presets: MutableMap<String, LanesRunConfiguration.Preset> = LinkedHashMap()
    /** True while a preset is being applied or the combo repopulated, to ignore its own events. */
    private var applyingPreset = false

    // SettingsEditor<Settings>'s Settings type parameter has a non-null upper bound, so this
    // override cannot be declared with a nullable parameter (Kotlin rejects both a nullable
    // parameter type here and a nullable SettingsEditor<LanesRunConfiguration?> type
    // argument on the class itself - only the exact non-null signature satisfies the override).
    // The platform's composite SettingsEditor wrapper chain can still call this with a raw null
    // at the JVM level regardless (bypassing Kotlin's compile-time guarantee, the same way a
    // Java caller always could) while a brand-new "Add New Configuration" entry is still
    // settling - the original, decade-old Java implementation took @Nullable here for that
    // reason. -Xno-param-assertions (build.gradle) disables Kotlin's usual automatic
    // Intrinsics.checkNotNullParameter for this parameter so the explicit check below can run
    // instead of an immediate NPE.
    @Suppress("SENSELESS_COMPARISON")
    override fun resetEditorFrom(lanesRunConfiguration: LanesRunConfiguration) {
        if (lanesRunConfiguration != null) {
            this.configuration = lanesRunConfiguration
        }

        val configuration = this.configuration ?: return

        memoryLimits = LinkedHashMap(configuration.getMemoryLimits())
        disabledApps = LinkedHashSet(configuration.getDisabledApps())
        readyConditions = LinkedHashMap(configuration.getReadyConditions())
        appEnvFiles = LinkedHashMap(configuration.getAppEnvFiles())
        presets = LinkedHashMap(configuration.getPresets())
        applyingPreset = true
        val presetsModel = presetCombo.getModel() as DefaultComboBoxModel<String>
        presetsModel.removeAllElements()
        for (presetName in presets.keys) {
            presetsModel.addElement(presetName)
        }
        presetCombo.setSelectedItem(null)
        applyingPreset = false
        configurationsModel.setItems(ArrayList(configuration.getRunConfigurations()))
        environmentVariables.setEnvData(configuration.getEnvData())
        val profilesModel = envFileCombo.getModel() as DefaultComboBoxModel<String>
        profilesModel.removeAllElements()
        for (profile in configuration.getEnvProfiles()) {
            profilesModel.addElement(profile)
        }
        envFileCombo.setSelectedItem(configuration.getEnvFilePath())
        saveOutputDir.setText(configuration.getSaveOutputDir())
        delayTime.setText(String.format("%.1f", configuration.getDelayTime()))
        reuseTabs.setSelected(configuration.isReuseTabs())
        reuseTabsWithFailure.setSelected(configuration.isReuseTabsWithFailure())
        startOneByOne.setSelected(configuration.isStartOneByOne())
        restartRunning.setSelected(configuration.isRestartRunning())
        markFailedProcess.setSelected(configuration.isMarkFailedProcess())
        hideSuccessProcess.setSelected(configuration.isHideSuccessProcess())
        restartOnCrashBox.setSelected(configuration.isRestartOnCrash())
        memThresholdSpinner.setValue(configuration.getMemAlertThreshold())
        memLimitActionCombo.setSelectedIndex(if (configuration.isMemLimitRestart()) 1 else 0)
        cpuAlertSpinner.setValue(configuration.getCpuAlertThreshold())
        delayTime.setEnabled(startOneByOne.isSelected())
    }

    // See the comment on resetEditorFrom for why this parameter is declared non-null but still
    // explicitly checked.
    @Suppress("SENSELESS_COMPARISON")
    override fun applyEditorTo(lanesRunConfiguration: LanesRunConfiguration) {
        if (lanesRunConfiguration == null) return
        lanesRunConfiguration.setEnvData(environmentVariables.getEnvData())
        val activeEnvFile = envFileComboText()
        lanesRunConfiguration.setEnvFilePath(activeEnvFile)
        val envProfiles = ArrayList<String>()
        for (i in 0 until envFileCombo.getItemCount()) {
            envProfiles.add(envFileCombo.getItemAt(i))
        }
        if (activeEnvFile.isNotEmpty() && !envProfiles.contains(activeEnvFile)) {
            // a path typed by hand becomes a profile too
            envProfiles.add(activeEnvFile)
        }
        lanesRunConfiguration.setEnvProfiles(envProfiles)
        lanesRunConfiguration.setSaveOutputDir(saveOutputDir.getText())
        lanesRunConfiguration.setMemoryLimits(memoryLimits)
        lanesRunConfiguration.setDisabledApps(disabledApps)
        lanesRunConfiguration.setReadyConditions(readyConditions)
        lanesRunConfiguration.setAppEnvFiles(appEnvFiles)
        lanesRunConfiguration.setPresets(presets)
        lanesRunConfiguration.setReuseTabs(reuseTabs.isSelected())
        lanesRunConfiguration.setReuseTabsWithFailure(reuseTabsWithFailure.isSelected())
        lanesRunConfiguration.setStartOneByOne(startOneByOne.isSelected())
        lanesRunConfiguration.setRestartRunning(restartRunning.isSelected())
        lanesRunConfiguration.setMarkFailedProcess(markFailedProcess.isSelected())
        lanesRunConfiguration.setHideSuccessProcess(hideSuccessProcess.isSelected())
        lanesRunConfiguration.setRestartOnCrash(restartOnCrashBox.isSelected())
        lanesRunConfiguration.setMemAlertThreshold(memThresholdSpinner.getValue() as Int)
        lanesRunConfiguration.setMemLimitRestart(memLimitActionCombo.getSelectedIndex() == 1)
        lanesRunConfiguration.setCpuAlertThreshold(cpuAlertSpinner.getValue() as Int)
        var delayTimeSeconds = 0.0
        val delayText = delayTime.getText()
        if (!delayText.isNullOrEmpty()) {
            try {
                // Accepts both '.' and ',' so it matches the locale-formatted value shown by
                // resetEditorFrom (e.g. "0,0" in pt-BR/German) as well as hand-typed "0.5".
                delayTimeSeconds = LanesRunConfiguration.parseDelay(delayText)
            } catch (e: NumberFormatException) {
                // well ignore if the value is not a number
            }
        }
        lanesRunConfiguration.setDelayTime(delayTimeSeconds)

        // NOTE: never stopEditing() here. This method also runs for dialog validation on every
        // user interaction, so closing the cell editor from it made the "Memory limit" and
        // "Ready when" columns lose focus while typing. Pending cell edits are committed by the
        // "terminateEditOnFocusLost" client property when focus moves to the Apply/Run button.
        this.configuration!!.setRunConfigurations(configurationsModel.getItems())
    }

    override fun createEditor(): JComponent {
        configurationsModel = ListTableModel(
            EnabledColumn(), ConfigurationColumn(), MemoryLimitColumn(), ReadyWhenColumn(), AppEnvFileColumn())
        configurations = TableView(configurationsModel)
        configurations.setShowGrid(false)
        configurations.getEmptyText().setText("Add run configurations to this list")
        // commit the cell editor when the table loses focus, so typed limits are kept
        configurations.putClientProperty("terminateEditOnFocusLost", true)
        configurationsModel.addTableModelListener {
            configuration?.setRunConfigurations(configurationsModel.getItems())
            fireEditorStateChanged()
        }
        val myDecorator = ToolbarDecorator.createDecorator(configurations)
        myDecorator.initPosition()

        myDecorator.setRemoveAction {
            TableUtil.removeSelectedItems(configurations)
            markConfigurationsChanged()
        }
        myDecorator.setAddAction { button ->
            val list = JBList(getConfigurationsToAdd())
            list.setCellRenderer(RunConfigurationListCellRenderer())
            PopupChooserBuilder(list)
                .setItemChosenCallback(Runnable {
                    val selectedIndices = list.getSelectedIndices()
                    for (index in selectedIndices) {
                        val selectedRunConfiguration = list.getModel().getElementAt(index)
                        if (selectedRunConfiguration != null) {
                            configurationsModel.addRow(selectedRunConfiguration)
                        }
                        markConfigurationsChanged()
                    }
                })
                .createPopup()
                .showUnderneathOf(button.getContextComponent())
        }
        myDecorator.setAddActionUpdater { !getConfigurationsToAdd().isEmpty() }

        // Import a docker-compose.yml onto the matching run configurations (by service name):
        // mem_limit -> Memory limit, env_file -> profile, depends_on -> order + Ready when.
        myDecorator.addExtraAction(object : DumbAwareAction(
            "Import from docker-compose.yml…",
            "Apply a docker-compose.yml to the run configurations whose name matches a service",
            LanesIcons.Docker) {
            override fun actionPerformed(e: AnActionEvent) {
                importFromCompose()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        startOneByOne.addActionListener { delayTime.setEnabled(startOneByOne.isSelected()) }

        // Environment variables applied to every configuration in the list (Lanes values win on conflicts).
        // The component provides the same editing dialog used by the platform run configurations.
        environmentVariables = EnvironmentVariablesComponent()
        environmentVariables.setLabelLocation(BorderLayout.WEST)
        environmentVariables.getComponent().setToolTipText(
            "These variables are applied to every configuration in the list, overriding its own variables with the same name")
        envVarsPanel.add(environmentVariables, BorderLayout.CENTER)

        // Optional .env file applied under the variables above (the table values win on conflicts).
        // Read again on every run, so file edits are picked up without touching the configuration.
        // The editable combo keeps every file ever used as an "environment profile", so switching
        // between .env files (-com / -ede / -def, ...) is a two-click dropdown choice.
        envFileCombo = ComboBox(DefaultComboBoxModel())
        envFileCombo.setEditable(true)
        envFileCombo.setToolTipText(
            "Active .env file (KEY=VALUE lines, # comments, optional \"export\" prefix). "
                + "Applied to every configuration in the list; variables configured above win on conflicts. "
                + "Relative paths are resolved against the project root. Every file used once stays "
                + "in this dropdown as a profile - switch environments without retyping paths")
        envFileCombo.addActionListener { fireEditorStateChanged() }

        val browseEnvFile = FixedSizeButton(envFileCombo)
        browseEnvFile.setToolTipText("Select one or more .env files and add them to the profile list")
        browseEnvFile.addActionListener {
            // multi-select: pick several .env files at once (Ctrl/Shift) instead of one per click
            val chosen = FileChooser.chooseFiles(
                FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                    .withTitle("Select Environment File(s)")
                    // .env files are dotfiles, hidden by the chooser by default
                    .withShowHiddenFiles(true),
                project, null)
            if (chosen.isEmpty()) {
                return@addActionListener
            }
            val model = envFileCombo.getModel() as DefaultComboBoxModel<String>
            val existing = ArrayList<String>()
            for (i in 0 until model.getSize()) {
                existing.add(model.getElementAt(i))
            }
            val chosenPaths = ArrayList<String>()
            for (file in chosen) {
                chosenPaths.add(file.getPresentableUrl())
            }
            val merged = addEnvProfiles(existing, chosenPaths)
            model.removeAllElements()
            for (path in merged) {
                model.addElement(path)
            }
            // select the last file picked, so a single new choice behaves like before
            envFileCombo.setSelectedItem(chosenPaths[chosenPaths.size - 1])
        }

        val removeEnvProfile = FixedSizeButton(envFileCombo)
        removeEnvProfile.setIcon(AllIcons.General.Remove)
        removeEnvProfile.setToolTipText("Remove the selected profile from the list")
        removeEnvProfile.addActionListener {
            val selected = envFileCombo.getSelectedItem()
            if (selected != null && selected.toString().isNotEmpty()) {
                (envFileCombo.getModel() as DefaultComboBoxModel<String>).removeElement(selected)
                envFileCombo.setSelectedItem("")
            }
        }

        val envProfileButtons = JPanel(GridLayout(1, 2, 2, 0))
        envProfileButtons.add(browseEnvFile)
        envProfileButtons.add(removeEnvProfile)
        val envProfilePanel = JPanel(BorderLayout(4, 0))
        envProfilePanel.add(envFileCombo, BorderLayout.CENTER)
        envProfilePanel.add(envProfileButtons, BorderLayout.EAST)
        val envFileComponent = LabeledComponent.create(envProfilePanel, "Environment file (profile):")
        envFileComponent.setLabelLocation(BorderLayout.WEST)

        // Optional folder where each configuration's console is also saved as <name>.log,
        // through the IDE's native "save console output to file" mechanism (Logs tab).
        saveOutputDir = TextFieldWithBrowseButton()
        saveOutputDir.getTextField().setToolTipText(
            "When set, the console output of every configuration in the list is also saved to this folder "
                + "as <configuration name>.log - same mechanism as the Logs tab of individual run "
                + "configurations. Relative paths are resolved against the project root")
        saveOutputDir.addActionListener {
            val chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Folder for Console Logs")
                    .withShowHiddenFiles(true),
                project, null)
            if (chosen != null) {
                saveOutputDir.setText(chosen.getPresentableUrl())
            }
        }
        val saveOutputComponent = LabeledComponent.create(saveOutputDir, "Save console logs to:")
        saveOutputComponent.setLabelLocation(BorderLayout.WEST)

        // Restart policies (docker-like): crash restart + action when the memory limit is reached
        restartOnCrashBox = JCheckBox("Restart application on crash (max 3 attempts)")
        restartOnCrashBox.setToolTipText(
            "Like docker restart: on-failure - an application that exits with a crash code is "
                + "relaunched automatically, at most 3 times per run. Stops via the stop buttons "
                + "(SIGTERM/SIGINT/SIGKILL) never trigger a restart")
        memThresholdSpinner = JSpinner(SpinnerNumberModel(90, 10, 100, 5))
        memLimitActionCombo = JComboBox(arrayOf("Notify", "Restart application"))
        memLimitActionCombo.setToolTipText(
            "What to do when an application with a Memory limit crosses the threshold: "
                + "show a warning notification or restart it (docker-like OOM handling)")
        cpuAlertSpinner = JSpinner(SpinnerNumberModel(0, 0, 1000, 10))
        cpuAlertSpinner.setToolTipText(
            "Raise a notification when an application's CPU usage stays at or above this percentage "
                + "for a few consecutive background checks. 0 disables it. Values above 100 make "
                + "sense on multi-core machines (docker stats-style CPU %, summed across cores)")
        val policyPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        policyPanel.add(restartOnCrashBox)
        policyPanel.add(JLabel("   At"))
        policyPanel.add(memThresholdSpinner)
        policyPanel.add(JLabel("% of the memory limit:"))
        policyPanel.add(memLimitActionCombo)
        policyPanel.add(JLabel("   CPU alert at"))
        policyPanel.add(cpuAlertSpinner)
        policyPanel.add(JLabel("% (0 = off)"))

        // Named execution presets: a saved On/Off + env-profile combination, applied from a dropdown.
        presetCombo = ComboBox(DefaultComboBoxModel())
        presetCombo.setToolTipText(
            "Named combinations of enabled applications + environment profile (e.g. 'backend only', "
                + "'full stack'). Pick one to apply it to the list above; Save stores the current "
                + "On/Off selection and active profile as a preset")
        presetCombo.addActionListener {
            if (applyingPreset) {
                return@addActionListener
            }
            val selected = presetCombo.getSelectedItem()
            if (selected == null) {
                return@addActionListener
            }
            val preset = presets[selected.toString()]
            if (preset == null) {
                return@addActionListener
            }
            disabledApps = LinkedHashSet(preset.disabledApps)
            envFileCombo.setSelectedItem(preset.envFilePath)
            (configurationsModel as AbstractTableModel).fireTableDataChanged()
            markConfigurationsChanged()
        }

        val savePreset = FixedSizeButton(presetCombo)
        savePreset.setIcon(AllIcons.Actions.MenuSaveall)
        savePreset.setToolTipText("Save the current On/Off selection and environment profile as a preset")
        savePreset.addActionListener {
            val current = presetCombo.getSelectedItem()
            val suggested = current?.toString() ?: ""
            val name = Messages.showInputDialog(
                project, "Preset name:", "Save Execution Preset",
                Messages.getQuestionIcon(), suggested, null)
            if (name == null || name.trim().isEmpty()) {
                return@addActionListener
            }
            presets[name.trim()] = LanesRunConfiguration.Preset(
                name.trim(), LinkedHashSet(disabledApps), envFileComboText())
            val model = presetCombo.getModel() as DefaultComboBoxModel<String>
            if (model.getIndexOf(name.trim()) < 0) {
                model.addElement(name.trim())
            }
            applyingPreset = true
            presetCombo.setSelectedItem(name.trim())
            applyingPreset = false
            markConfigurationsChanged()
        }

        val deletePreset = FixedSizeButton(presetCombo)
        deletePreset.setIcon(AllIcons.General.Remove)
        deletePreset.setToolTipText("Delete the selected preset")
        deletePreset.addActionListener {
            val selected = presetCombo.getSelectedItem()
            if (selected != null) {
                presets.remove(selected.toString())
                (presetCombo.getModel() as DefaultComboBoxModel<String>).removeElement(selected)
                markConfigurationsChanged()
            }
        }

        val presetButtons = JPanel(GridLayout(1, 2, 2, 0))
        presetButtons.add(savePreset)
        presetButtons.add(deletePreset)
        val presetPanel = JPanel(BorderLayout(4, 0))
        presetPanel.add(presetCombo, BorderLayout.CENTER)
        presetPanel.add(presetButtons, BorderLayout.EAST)
        val presetComponent = LabeledComponent.create(presetPanel, "Preset:")
        presetComponent.setLabelLocation(BorderLayout.WEST)

        val filesPanel = JPanel(GridLayout(4, 1))
        filesPanel.add(presetComponent)
        filesPanel.add(envFileComponent)
        filesPanel.add(saveOutputComponent)
        filesPanel.add(policyPanel)
        envVarsPanel.add(filesPanel, BorderLayout.SOUTH)

        val panel = JPanel()
        panel.setLayout(BorderLayout())
        val myDecoratorPanel = myDecorator.createPanel()
        panel.add(myDecoratorPanel, BorderLayout.CENTER)
        collectionsPanel.add(myDecoratorPanel)

        configurationsListChanged.setVisible(false)

        return myMainPanel
    }

    /**
     * Reads a docker-compose.yml and applies it to the run configurations already in the list whose
     * name matches a service: `mem_limit`/`deploy...memory` becomes the Memory limit,
     * `env_file` becomes an environment profile, and `depends_on` reorders the list
     * (dependencies first) and adds a `port:` "Ready when" gate on services that expose a port.
     * Services with no matching run configuration are reported and skipped.
     */
    private fun importFromCompose() {
        val chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select a docker-compose.yml"),
            project, null) ?: return

        val text: String
        try {
            text = String(chosen.contentsToByteArray(), chosen.getCharset())
        } catch (ex: IOException) {
            Messages.showErrorDialog(
                project, "Could not read the file: ${ex.message}", "Import From docker-compose")
            return
        }
        val services: List<ComposeImporter.Service>
        try {
            services = ComposeImporter.parse(text)
        } catch (ex: IllegalArgumentException) {
            Messages.showErrorDialog(project, ex.message, "Import From docker-compose")
            return
        }
        if (services.isEmpty()) {
            Messages.showInfoMessage(
                project, "No services found in the file.", "Import From docker-compose")
            return
        }

        val items = ArrayList(configurationsModel.getItems())
        val byName = LinkedHashMap<String, RunConfiguration>()
        for (each in items) {
            byName[each.getName()] = each
        }

        val matched = ArrayList<String>()
        val unmatched = ArrayList<String>()
        val matchedServices = LinkedHashMap<String, ComposeImporter.Service>()
        val distinctEnvFiles = LinkedHashSet<String>()

        for (service in services) {
            if (!byName.containsKey(service.name)) {
                unmatched.add(service.name)
                continue
            }
            matched.add(service.name)
            matchedServices[service.name] = service
            if (service.memLimitMb != null) {
                memoryLimits[service.name] = service.memLimitMb
            }
            val envModel = envFileCombo.getModel() as DefaultComboBoxModel<String>
            for (envFile in service.envFiles) {
                distinctEnvFiles.add(envFile)
                if (envModel.getIndexOf(envFile) < 0) {
                    envModel.addElement(envFile)
                }
            }
        }

        // depends_on among matched services: gate on the dependency (if it exposes a port) + order
        var anyReadyAdded = false
        val deps = LinkedHashMap<String, List<String>>()
        for (name in matched) {
            val filtered = ArrayList<String>()
            for (dep in matchedServices.getValue(name).dependsOn) {
                if (!byName.containsKey(dep)) {
                    continue
                }
                filtered.add(dep)
                val depService = matchedServices[dep]
                if (depService != null && depService.ports.isNotEmpty() && !readyConditions.containsKey(dep)) {
                    readyConditions[dep] = "port:${depService.ports[0]}"
                    anyReadyAdded = true
                }
            }
            deps[name] = filtered
        }

        // reorder: matched configs in dependency order, the group's other configs kept after them
        val order = ComposeImporter.topologicalOrder(matched, deps)
        val reordered = ArrayList<RunConfiguration>()
        for (name in order) {
            reordered.add(byName.getValue(name))
        }
        for (each in items) {
            if (!order.contains(each.getName())) {
                reordered.add(each)
            }
        }
        configurationsModel.setItems(reordered)

        if (distinctEnvFiles.size == 1) {
            // a single env file across the matched services can be set as the active profile
            envFileCombo.setSelectedItem(distinctEnvFiles.iterator().next())
        }
        if (anyReadyAdded && !startOneByOne.isSelected()) {
            // Ready when only gates in one-by-one mode, so enable it when a gate was added
            startOneByOne.setSelected(true)
            delayTime.setEnabled(true)
        }
        (configurationsModel as AbstractTableModel).fireTableDataChanged()
        markConfigurationsChanged()

        val report = StringBuilder()
        report.append(matched.size).append(" service(s) matched and updated")
        if (matched.isNotEmpty()) {
            report.append(":\n  ").append(matched.joinToString(", "))
        }
        if (unmatched.isNotEmpty()) {
            report.append("\n\n").append(unmatched.size)
                .append(" service(s) have no run configuration with the same name and were skipped:\n  ")
                .append(unmatched.joinToString(", "))
                .append("\n\nCreate run configurations with matching names and import again.")
        }
        Messages.showInfoMessage(project, report.toString(), "Import From docker-compose")
    }

    /** The text of the (possibly in-edition) env profile combo editor. */
    private fun envFileComboText(): String {
        val editorItem = envFileCombo.getEditor().getItem()
        return editorItem?.toString()?.trim() ?: ""
    }

    private fun markConfigurationsChanged() {
        // use hidden checkbox to fire the modified event;
        configurationsListChanged.setSelected(!configurationsListChanged.isSelected())
    }

    override fun disposeEditor() {
    }

    /** Checkbox column: unchecked applications stay in the list but are not launched. */
    private inner class EnabledColumn : ColumnInfo<RunConfiguration, Boolean>("On") {
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java

        override fun valueOf(configuration: RunConfiguration): Boolean = !disabledApps.contains(configuration.getName())

        override fun isCellEditable(configuration: RunConfiguration): Boolean = true

        override fun setValue(configuration: RunConfiguration, enabled: Boolean?) {
            if (enabled == false) {
                disabledApps.add(configuration.getName())
            } else {
                disabledApps.remove(configuration.getName())
            }
            markConfigurationsChanged()
        }

        override fun getTooltipText(): String = "Unchecked applications are kept in the list but not launched"

        override fun getWidth(table: JTable): Int = 40
    }

    /** First table column: icon + "Run 'name'", read-only. */
    private class ConfigurationColumn : ColumnInfo<RunConfiguration, String>("Run Configuration") {
        override fun valueOf(configuration: RunConfiguration): String = "Run '${configuration.getName()}'"

        override fun getRenderer(configuration: RunConfiguration): TableCellRenderer {
            return object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    icon = configuration.getIcon()
                    return this
                }
            }
        }
    }

    /** Second table column: per-application memory (heap) cap in MB, edited inline. */
    private inner class MemoryLimitColumn : ColumnInfo<RunConfiguration, String>("Memory limit (MB)") {
        override fun valueOf(configuration: RunConfiguration): String {
            val limitMb = memoryLimits[configuration.getName()]
            return limitMb?.toString() ?: ""
        }

        override fun isCellEditable(configuration: RunConfiguration): Boolean = true

        override fun setValue(configuration: RunConfiguration, value: String?) {
            try {
                val parsed = if (value == null || value.trim().isEmpty()) 0 else value.trim().toInt()
                if (parsed <= 0) {
                    memoryLimits.remove(configuration.getName())
                } else {
                    memoryLimits[configuration.getName()] = parsed
                }
                markConfigurationsChanged()
            } catch (ignored: NumberFormatException) {
                // non-numeric input keeps the previous value
            }
        }

        override fun getTooltipText(): String =
            "Heap cap applied at launch as NODE_OPTIONS --max-old-space-size (Node.js) and " +
                "JAVA_TOOL_OPTIONS -Xmx (JVM) - the process-level analog of docker's mem_limit. " +
                "Empty = no limit"

        override fun getWidth(table: JTable): Int = 140
    }

    /** "Ready when" column: docker-compose-like readiness gate used by one-by-one starts. */
    private inner class ReadyWhenColumn : ColumnInfo<RunConfiguration, String>("Ready when") {
        override fun valueOf(configuration: RunConfiguration): String = readyConditions[configuration.getName()] ?: ""

        override fun isCellEditable(configuration: RunConfiguration): Boolean = true

        override fun setValue(configuration: RunConfiguration, value: String?) {
            if (value == null || value.trim().isEmpty()) {
                readyConditions.remove(configuration.getName())
            } else {
                readyConditions[configuration.getName()] = value.trim()
            }
            markConfigurationsChanged()
        }

        override fun getTooltipText(): String =
            "With 'Start one by one', the next application only starts after this one is ready. " +
                "Syntax: port:3003 (TCP port open), http://localhost:3003/health (HTTP 2xx/3xx) " +
                "or log:Server started (console output contains the text). Empty = no waiting. " +
                "Port/http conditions also feed the Status column of the Lanes Monitor"

        override fun getWidth(table: JTable): Int = 180
    }

    /** "Env file (app)" column: a per-application .env file that overrides the group environment. */
    private inner class AppEnvFileColumn : ColumnInfo<RunConfiguration, String>("Env file (app)") {
        override fun valueOf(configuration: RunConfiguration): String = appEnvFiles[configuration.getName()] ?: ""

        override fun isCellEditable(configuration: RunConfiguration): Boolean = true

        override fun setValue(configuration: RunConfiguration, value: String?) {
            if (value == null || value.trim().isEmpty()) {
                appEnvFiles.remove(configuration.getName())
            } else {
                appEnvFiles[configuration.getName()] = value.trim()
            }
            markConfigurationsChanged()
        }

        override fun getTooltipText(): String =
            "Optional .env file applied only to this application, overriding the group's " +
                "environment (variables and env file) on conflicts. Relative paths resolve " +
                "against the project root. Re-read on every run. Empty = use the group's environment"

        override fun getWidth(table: JTable): Int = 160
    }

    /** Renderer for the "add configuration" popup list. */
    private class RunConfigurationListCellRenderer : SimpleListCellRenderer<RunConfiguration>() {
        override fun customize(
            list: JList<out RunConfiguration>, data: RunConfiguration?, index: Int, selected: Boolean, hasFocus: Boolean
        ) {
            if (data != null) {
                icon = data.getIcon()
                text = "Run '${data.getName()}'"
            }
        }
    }

    private fun getConfigurationsToAdd(): List<RunConfiguration> {
        val result = ArrayList<RunConfiguration>()
        val cfg = this.configuration ?: return result

        val allConfigurations = RunManager.getInstance(project).allConfigurationsList
        for (candidate in allConfigurations) {
            if (cfg == candidate) {
                // skip current
                continue
            }
            if (cfg.getRunConfigurations().contains(candidate)) {
                // skip already added
                continue
            }
            if (candidate is LanesRunConfiguration) {
                // exclude configurations that may cause loopies
                if (RunConfigurationHelper.containsLoopies(candidate, cfg)) {
                    continue
                }
            }
            result.add(candidate)
        }

        return result
    }

    companion object {
        /**
         * Appends the chosen env-file paths to the existing profile list, skipping duplicates and
         * preserving order (existing first, then the new ones in the order they were picked). Pure, so
         * the multi-file selection behaviour is unit-testable without a file chooser.
         */
        @JvmStatic
        fun addEnvProfiles(existing: List<String>, chosen: List<String>): List<String> {
            val result = ArrayList(existing)
            for (path in chosen) {
                if (path.isNotEmpty() && !result.contains(path)) {
                    result.add(path)
                }
            }
            return result
        }
    }
}
