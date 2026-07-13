package io.github.pixelcodes.lanes

import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener

/**
 * Monitor-only registry of the environment a STANDALONE app runs with - one started directly with
 * the IDE's own Play/Debug, not by a Lanes group. Populated by the optional Node env-file
 * module ([io.github.pixelcodes.lanes.nodejs.LanesNodeEnvFileExtension]) so the
 * Lanes Monitor can show, in its Env column, the active `.env` file name (when one is loaded)
 * and let the user open the variables actually loaded into the app.
 *
 * Deliberately separate from [LanesProcessRegistry]: a standalone app must keep its own run
 * configuration icon and stay ungrouped in the monitor - this enriches only the Env column, nothing
 * else. Keyed by the process handler (globally unique), cleaned up when the process ends. Values are
 * kept in memory for the read-only viewer and are never logged.
 */
object StandaloneEnvRegistry {

    /** The env a standalone app was launched with, as far as the plugin injected/observed it. */
    class Info internal constructor(
        rawEnvFileName: String?,
        rawLoadedEnv: Map<String, String>?,
        /** Whether the app also inherits the system/parent environment on top of [loadedEnv]. */
        @JvmField val includeSystemEnv: Boolean,
    ) {
        /** File name of the active `.env` file, or "-" when the app runs without one. */
        @JvmField
        val envFileName: String = if (rawEnvFileName.isNullOrEmpty()) "-" else rawEnvFileName

        /** The variables loaded into the app (its own env field + any `.env` file the plugin applied). */
        @JvmField
        val loadedEnv: Map<String, String> = if (rawLoadedEnv == null) emptyMap()
            else Collections.unmodifiableMap(LinkedHashMap(rawLoadedEnv))
    }

    private val BY_HANDLER = ConcurrentHashMap<ProcessHandler, Info>()

    @JvmStatic
    fun register(handler: ProcessHandler, envFileName: String?, loadedEnv: Map<String, String>?, includeSystemEnv: Boolean) {
        BY_HANDLER[handler] = Info(envFileName, loadedEnv, includeSystemEnv)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                BY_HANDLER.remove(handler)
            }
        })
        // the handler may have died between the run and this call
        if (handler.isProcessTerminated) {
            BY_HANDLER.remove(handler)
        }
    }

    /** Env info for a running standalone app, or null when it was not launched with plugin env data. */
    @JvmStatic
    fun find(handler: ProcessHandler): Info? = BY_HANDLER[handler]
}
