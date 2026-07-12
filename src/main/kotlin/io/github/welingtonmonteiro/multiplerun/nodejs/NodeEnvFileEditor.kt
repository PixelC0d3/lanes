package io.github.welingtonmonteiro.multiplerun.nodejs

import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

import com.intellij.icons.AllIcons
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.util.ui.JBUI

/**
 * The "Environment files" section contributed to a Node-based run configuration's editor. Mirrors
 * the Multiple Run group editor's env field: an editable combo holding every `.env` file used as a
 * profile, a browse button that adds one or more files at once, and a remove button. The combo's
 * text is the *active* file (loaded on the next run).
 */
class NodeEnvFileEditor<P : AbstractNodeTargetRunProfile> : SettingsEditor<P>() {

    private val envFileCombo = ComboBox(DefaultComboBoxModel<String>())
    private var project: Project? = null

    init {
        envFileCombo.isEditable = true
        envFileCombo.toolTipText =
            "Active .env file (KEY=VALUE lines, # comments, optional \"export\" prefix), loaded when " +
                "this configuration runs - including a plain Play/Debug, not only through Multiple Run. " +
                "The variables in the \"Environment variables\" field above win on conflicts. Relative " +
                "paths are resolved against the project root. Every file used stays in this dropdown as a " +
                "profile, so you can switch environments without retyping paths (also from the run toolbar)."
        envFileCombo.addActionListener { fireEditorStateChanged() }
    }

    override fun resetEditorFrom(configuration: P) {
        project = configuration.project
        val settings = NodeEnvFileSettings.of(configuration)
        val model = envFileCombo.model as DefaultComboBoxModel<String>
        model.removeAllElements()
        for (profile in settings.profiles) {
            model.addElement(profile)
        }
        envFileCombo.selectedItem = settings.active
    }

    override fun applyEditorTo(configuration: P) {
        NodeEnvFileSettings.store(configuration, currentSettings())
    }

    /** The list from the combo model plus the active (typed) entry, with the active file selected. */
    private fun currentSettings(): NodeEnvFileSettings {
        val model = envFileCombo.model as DefaultComboBoxModel<String>
        val profiles = ArrayList<String>()
        for (i in 0 until model.size) {
            profiles.add(model.getElementAt(i))
        }
        val active = (envFileCombo.selectedItem as? String)?.trim() ?: ""
        if (active.isNotEmpty() && !profiles.contains(active)) {
            profiles.add(active)
        }
        return NodeEnvFileSettings(profiles, active)
    }

    override fun createEditor(): JComponent {
        val browse = FixedSizeButton(envFileCombo)
        browse.toolTipText = "Select one or more .env files and add them to the profile list"
        browse.addActionListener {
            val chosen = FileChooser.chooseFiles(
                FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                    .withTitle("Select Environment File(s)")
                    .withShowHiddenFiles(true), // .env files are dotfiles, hidden by default
                project, null)
            if (chosen.isEmpty()) {
                return@addActionListener
            }
            val model = envFileCombo.model as DefaultComboBoxModel<String>
            var last = ""
            for (file in chosen) {
                val path = file.presentableUrl
                if (model.getIndexOf(path) < 0) {
                    model.addElement(path)
                }
                last = path
            }
            envFileCombo.selectedItem = last // a single new pick behaves like a normal select
            fireEditorStateChanged()
        }

        val remove = FixedSizeButton(envFileCombo)
        remove.icon = AllIcons.General.Remove
        remove.toolTipText = "Remove the selected profile from the list"
        remove.addActionListener {
            val selected = envFileCombo.selectedItem
            if (selected != null && selected.toString().isNotEmpty()) {
                (envFileCombo.model as DefaultComboBoxModel<String>).removeElement(selected)
                envFileCombo.selectedItem = ""
                fireEditorStateChanged()
            }
        }

        val buttons = JPanel(GridLayout(1, 2, 2, 0))
        buttons.add(browse)
        buttons.add(remove)
        val row = JPanel(BorderLayout(4, 0))
        row.add(envFileCombo, BorderLayout.CENTER)
        row.add(buttons, BorderLayout.EAST)

        val labeled = LabeledComponent.create(row, "Environment file (profile):")
        labeled.labelLocation = BorderLayout.WEST
        labeled.border = JBUI.Borders.empty(6, 0)
        return labeled
    }
}
