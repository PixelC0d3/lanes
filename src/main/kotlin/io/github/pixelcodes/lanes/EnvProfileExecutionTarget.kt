package io.github.pixelcodes.lanes

import javax.swing.Icon

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.RunConfiguration

/**
 * A run-widget target representing one saved `.env` profile: registering an
 * [com.intellij.execution.ExecutionTargetProvider] that returns more than one of these makes the
 * platform show a picker next to the run configuration selector, before Play/Debug even starts -
 * letting the user choose the environment to launch with, no dialog required.
 *
 * The platform's own `DefaultExecutionTargetProvider` unconditionally contributes a "Default"
 * target to every configuration alongside these (confirmed by disassembling
 * `ExecutionTargetManagerImpl.getTargetsFor`, which unions every provider's results) - there is no
 * supported way to suppress it. "Default" simply means "keep whatever profile is already active".
 *
 * [profilesOf] re-derives the *current* profile list for a given configuration instead of caching
 * it at construction time, kept generic (no reference to Node or Lanes types here) so this single
 * class serves both the Node-native provider (optional module) and the Lanes group provider
 * (core) without either pulling in the other's types.
 */
class EnvProfileExecutionTarget(
    @JvmField val profile: String,
    private val configName: String,
    private val configTypeId: String,
    /** Marks this entry the same way the existing env-switch combos do (see EnvFileCellRenderer). */
    private val isActive: Boolean,
    private val profilesOf: (RunConfiguration) -> List<String>,
) : ExecutionTarget() {

    override fun getId(): String = "Lanes.EnvProfile:$configTypeId:$configName:$profile"

    override fun getDisplayName(): String =
        RunConfigurationHelper.envFileDisplayName(profile) + (if (isActive) "  (active)" else "")

    override fun getIcon(): Icon = LanesIcons.EnvVariables

    override fun canRun(configuration: RunConfiguration): Boolean =
        configuration.getType().getId() == configTypeId &&
            configuration.getName() == configName &&
            profilesOf(configuration).contains(profile)

    override fun equals(other: Any?): Boolean = other is EnvProfileExecutionTarget && other.getId() == getId()

    override fun hashCode(): Int = getId().hashCode()
}
