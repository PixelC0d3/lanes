package io.github.pixelcodes.lanes.nodejs

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.util.Key
import org.jdom.Element

/**
 * The Lanes env-file settings attached to a single Node-based run configuration (Node.js and
 * npm/pnpm/yarn scripts - see [isLaunchInjectionSupported] for why only those): a list of
 * known `.env` "profiles" and which one is currently active.
 *
 * Mirrors the environment model a Lanes *group* already offers its children, but for a
 * standalone run started with the IDE's own Play/Debug. The active file's variables are loaded
 * under the run configuration's own "Environment variables" field (the manual field wins on
 * conflicts, exactly like [io.github.pixelcodes.lanes.RunConfigurationHelper.withEnvFile]).
 *
 * Held as *copyable* user data on the configuration ([Key]), because the settings editor works on a
 * `clone()` of the configuration and `RunConfigurationBase.clone()` only carries copyable user data
 * across. Persisted into the run config XML by the extension's `read`/`writeExternal`; when the
 * plugin is uninstalled the leftover XML element is simply ignored by the platform.
 */
class NodeEnvFileSettings(profiles: List<String>, active: String) {

    /** Known `.env` files, trimmed, de-duplicated, blanks dropped - the switch list. */
    @JvmField
    val profiles: List<String> = profiles.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    /** The `.env` file loaded on the next run; blank means "no env file", so nothing is injected. */
    @JvmField
    val active: String = active.trim()

    val isEmpty: Boolean
        get() = profiles.isEmpty() && active.isEmpty()

    /** Same profiles, different active file - used by the run-toolbar switch action. */
    fun withActive(newActive: String): NodeEnvFileSettings = NodeEnvFileSettings(profiles, newActive)

    fun writeTo(element: Element) {
        if (active.isNotEmpty()) {
            element.setAttribute(ATTR_ACTIVE, active)
        }
        for (profile in profiles) {
            element.addContent(Element(TAG_FILE).setText(profile))
        }
    }

    companion object {
        /**
         * Configuration types whose run states actually apply node run-configuration extensions at
         * launch: `NodeJsRunProfileState` and `NpmRunProfileState` are the only ones that create the
         * extension launch session (`NodeRunConfigurationExtensionsManager.createLaunchSession`).
         * Mocha, Karma and Jest build their command line directly and never consult extensions —
         * verified by disassembling their run states on 2024.2 and 2026.1 — so offering the env-file
         * field there would let the user configure a file that never loads. The ids are stable
         * across those versions.
         */
        private val LAUNCH_CAPABLE_TYPE_IDS = setOf(
            "NodeJSConfigurationType", // Node.js
            "js.build_tools.npm",      // npm/pnpm/yarn scripts
        )

        /** True when the IDE actually loads the active env file at launch for this configuration type. */
        @JvmStatic
        fun isLaunchInjectionSupported(configuration: RunConfigurationBase<*>): Boolean =
            LAUNCH_CAPABLE_TYPE_IDS.contains(configuration.getType().getId())

        /** Unique per-extension id; also the value the platform stores in the XML wrapper element. */
        const val SERIALIZATION_ID = "io.github.pixelcodes.lanes.nodeEnvFiles"

        private const val ATTR_ACTIVE = "active"
        private const val TAG_FILE = "file"

        @JvmField
        val KEY: Key<NodeEnvFileSettings> = Key.create("LanesNodeEnvFileSettings")

        val EMPTY = NodeEnvFileSettings(emptyList(), "")

        fun readFrom(element: Element): NodeEnvFileSettings {
            val active = element.getAttributeValue(ATTR_ACTIVE) ?: ""
            val profiles = element.getChildren(TAG_FILE).map { it.text ?: "" }
            return NodeEnvFileSettings(profiles, active)
        }

        /** Current settings for a configuration, never null (defaults to [EMPTY]). */
        fun of(configuration: RunConfigurationBase<*>): NodeEnvFileSettings =
            configuration.getCopyableUserData(KEY) ?: EMPTY

        fun store(configuration: RunConfigurationBase<*>, settings: NodeEnvFileSettings) {
            configuration.putCopyableUserData(KEY, settings)
        }
    }
}
