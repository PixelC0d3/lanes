package io.github.welingtonmonteiro.multiplerun.nodejs

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.options.SettingsEditor
import org.jdom.Element

import io.github.welingtonmonteiro.multiplerun.RunConfigurationHelper

/**
 * Brings Multiple Run's `.env`-file loading to every Node-based run configuration
 * (`AbstractNodeTargetRunProfile`: Node.js, npm/pnpm/yarn, Karma, Jest, Mocha, ...), so an app
 * started with the IDE's own Play/Debug can load a `.env` file - the same capability a Multiple Run
 * group already gives its children.
 *
 * Registered through the OPTIONAL module (`META-INF/multiplerun-nodejs.xml`, EP
 * `JavaScript.nodeRunConfigurationExtension`), so it only exists when the host IDE has the
 * JavaScript plugin; uninstalling the plugin removes it entirely.
 *
 * - [createEditor] contributes the "Env Files" tab that manages the profile list + active file.
 * - [createLaunchSession] injects the active file's variables under the configuration's own env
 *   (the manual "Environment variables" field wins on conflicts), and adds the run-toolbar switch
 *   button when more than one profile is configured.
 * - [readExternal]/[writeExternal] persist the settings in the run configuration's XML.
 */
class MultiplerunNodeEnvFileExtension : AbstractNodeRunConfigurationExtension() {

    override fun isApplicableFor(configuration: AbstractNodeTargetRunProfile): Boolean = true

    override fun getEditorTitle(): String = "Env Files"

    override fun <P : AbstractNodeTargetRunProfile> createEditor(configuration: P): SettingsEditor<P> =
        NodeEnvFileEditor()

    override fun getSerializationId(): String = NodeEnvFileSettings.SERIALIZATION_ID

    override fun readExternal(runConfiguration: AbstractNodeTargetRunProfile, element: Element) {
        NodeEnvFileSettings.store(runConfiguration, NodeEnvFileSettings.readFrom(element))
    }

    override fun writeExternal(runConfiguration: AbstractNodeTargetRunProfile, element: Element) {
        NodeEnvFileSettings.of(runConfiguration).writeTo(element)
    }

    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment,
    ): NodeRunConfigurationLaunchSession = EnvFileLaunchSession(configuration, environment)

    private class EnvFileLaunchSession(
        private val configuration: AbstractNodeTargetRunProfile,
        private val environment: ExecutionEnvironment,
    ) : NodeRunConfigurationLaunchSession() {

        override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
            val active = NodeEnvFileSettings.of(configuration).active
            if (active.isBlank()) {
                return
            }
            // File variables are the base; whatever the configuration already resolved onto the run
            // (its own "Environment variables" field, pass-parent, ...) wins on conflicts - same
            // precedence Multiple Run groups use. See RunConfigurationHelper.withEnvFile.
            val merged = RunConfigurationHelper.withEnvFile(targetRun.envData, active, environment.project)
            targetRun.envData = merged
        }

        override fun getRunDebugActions(): List<AnAction> {
            if (NodeEnvFileSettings.of(configuration).profiles.size < 2) {
                return emptyList()
            }
            return listOf(SwitchNodeEnvAction(configuration, environment))
        }
    }
}
