package io.github.pixelcodes.lanes.nodejs

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.options.SettingsEditor
import org.jdom.Element

import io.github.pixelcodes.lanes.RunConfigurationHelper
import io.github.pixelcodes.lanes.StandaloneEnvRegistry

/**
 * Brings Lanes's `.env`-file loading to Node-based run configurations whose launch actually
 * applies extensions - Node.js and npm/pnpm/yarn scripts - so an app started with the IDE's own
 * Play/Debug can load a `.env` file - the same capability a Lanes group already gives its
 * children. Mocha/Karma/Jest also extend `AbstractNodeTargetRunProfile`, but their run states
 * never invoke the extension launch session (see [NodeEnvFileSettings.isLaunchInjectionSupported]),
 * so [isApplicableFor] hides the field there instead of offering a file that would never load;
 * for those, a Lanes *group* still injects env normally (different mechanism), as does the
 * native Environment variables field.
 *
 * Registered through the OPTIONAL module (`META-INF/lanes-nodejs.xml`, EP
 * `JavaScript.nodeRunConfigurationExtension`), so it only exists when the host IDE has the
 * JavaScript plugin; uninstalling the plugin removes it entirely.
 *
 * - [createEditor] contributes the "Env Files" tab that manages the profile list + active file.
 * - [createLaunchSession] injects the active file's variables under the configuration's own env
 *   (the manual "Environment variables" field wins on conflicts), and adds the run-toolbar switch
 *   button when more than one profile is configured.
 * - [readExternal]/[writeExternal] persist the settings in the run configuration's XML.
 */
class LanesNodeEnvFileExtension : AbstractNodeRunConfigurationExtension() {

    override fun isApplicableFor(configuration: AbstractNodeTargetRunProfile): Boolean =
        NodeEnvFileSettings.isLaunchInjectionSupported(configuration)

    override fun getEditorTitle(): String = "Env Files"

    // Render the env-file field inline in the main "Configuration" tab (right with the other run
    // settings) instead of as a separate tab - the user asked for it next to "Environment variables".
    override fun shouldExtendMainEditor(): Boolean = true

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

        @Volatile
        private var loadedEnv: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
        @Volatile
        private var activeFileName: String? = null
        @Volatile
        private var processHandler: ProcessHandler? = null

        override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
            val active = NodeEnvFileSettings.of(configuration).active
            if (active.isNotBlank()) {
                // File variables are the base; whatever the configuration already resolved onto the
                // run (its own "Environment variables" field, pass-parent, ...) wins on conflicts -
                // same precedence Lanes groups use. See RunConfigurationHelper.withEnvFile.
                targetRun.envData = RunConfigurationHelper.withEnvFile(targetRun.envData, active, environment.project)
                activeFileName = RunConfigurationHelper.envFileDisplayName(active)
            }
            // capture the final env so the monitor can show what this standalone app loaded (even a
            // Node app with no .env file, so its Env column offers the "view variables" detail)
            loadedEnv = targetRun.envData
        }

        override fun onProcessCreated(processHandler: ProcessHandler) {
            this.processHandler = processHandler
            StandaloneEnvRegistry.register(processHandler, activeFileName, loadedEnv.envs, loadedEnv.isPassParentEnvs)
        }

        override fun getRunDebugActions(): List<AnAction> {
            if (NodeEnvFileSettings.of(configuration).profiles.size < 2) {
                return emptyList()
            }
            return listOf(SwitchNodeEnvAction(configuration, environment) { processHandler })
        }
    }
}
