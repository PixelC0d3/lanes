package io.github.welingtonmonteiro.multiplerun

import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project

/**
 * Keeps track of every process started by a Multiple Run configuration, so the
 * "Multiple Run Monitor" tool window can list them with live memory statistics.
 * Entries are removed automatically when the process terminates.
 */
class MultirunProcessRegistry private constructor() {

    /** One running application started by a Multirun configuration. */
    class Entry internal constructor(
        @JvmField val multirunName: String,
        @JvmField val appName: String,
        @JvmField val handler: ProcessHandler,
        /** Configured memory cap in MB, or null when the application has no limit. */
        @JvmField val memoryLimitMb: Int?,
        /** The environment the app was launched with; lets the monitor restart just this app. */
        @JvmField val environment: ExecutionEnvironment?,
        rawEnvFileName: String?,
        rawLoadedEnv: Map<String, String>?,
        /** Whether the app also inherits the system/parent environment on top of [loadedEnv]. */
        @JvmField val includeSystemEnv: Boolean,
        /** Raw "Ready when" condition of the app (port:/log:/http...), or null when none. */
        @JvmField val readyCondition: String?,
        rawMemAlertThreshold: Int,
        /** true = restart the app at the threshold; false = just notify. */
        @JvmField val memLimitRestart: Boolean,
        rawCpuAlertThreshold: Int,
    ) {
        /** File name of the active env profile at launch time, or "-" when none. */
        @JvmField
        val envFileName: String = if (rawEnvFileName.isNullOrEmpty()) "-" else rawEnvFileName

        /**
         * The environment variables Multiple Run actually injected into this app at launch
         * (group variables + group env file + memory-limit options + per-app env file, merged),
         * so the monitor can show exactly what was loaded. Never logged - values stay in memory.
         */
        @JvmField
        val loadedEnv: Map<String, String> = if (rawLoadedEnv == null) emptyMap()
            else Collections.unmodifiableMap(LinkedHashMap(rawLoadedEnv))

        /** Percent of the memory limit that triggers the alert/action. */
        @JvmField
        val memAlertThreshold: Int = if (rawMemAlertThreshold <= 0) 90 else rawMemAlertThreshold

        /** Sustained CPU % that triggers an alert for this app, or 0 when disabled. */
        @JvmField
        val cpuAlertThreshold: Int = maxOf(0, rawCpuAlertThreshold)

        @JvmField
        val startedAtMs: Long = System.currentTimeMillis()
    }

    companion object {
        private val ENTRIES = ConcurrentHashMap<Project, MutableList<Entry>>()

        /**
         * Last multirun launch metadata per app name. Unlike ENTRIES this survives process
         * termination, so an app restarted individually (outside the multirun umbrella) still
         * shows its group, env profile and memory limit in the monitor.
         */
        private val LAST_BY_NAME = ConcurrentHashMap<Project, ConcurrentHashMap<String, Entry>>()

        @JvmStatic
        fun register(
            project: Project, multirunName: String, appName: String,
            handler: ProcessHandler, memoryLimitMb: Int?,
            environment: ExecutionEnvironment?, envFileName: String?,
            loadedEnv: Map<String, String>?, includeSystemEnv: Boolean,
            readyCondition: String?, memAlertThreshold: Int, memLimitRestart: Boolean,
            cpuAlertThreshold: Int,
        ) {
            val entry = Entry(multirunName, appName, handler, memoryLimitMb, environment,
                              envFileName, loadedEnv, includeSystemEnv, readyCondition,
                              memAlertThreshold, memLimitRestart, cpuAlertThreshold)
            ENTRIES.computeIfAbsent(project) { CopyOnWriteArrayList() }.add(entry)
            LAST_BY_NAME.computeIfAbsent(project) { ConcurrentHashMap() }.put(appName, entry)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    unregister(project, handler)
                }
            })
            // the handler may have died between the run and this call
            if (handler.isProcessTerminated()) {
                unregister(project, handler)
            }
            MemoryLimitWatcher.ensureStarted()
        }

        @JvmStatic
        fun unregister(project: Project, handler: ProcessHandler) {
            ENTRIES[project]?.removeAll { it.handler === handler }
        }

        @JvmStatic
        fun getEntries(project: Project): List<Entry> {
            val list = ENTRIES[project]
            return if (list == null) emptyList() else ArrayList(list)
        }

        /** A copy of all live entries of all projects; used by the background memory limit watcher. */
        @JvmStatic
        fun snapshot(): Map<Project, List<Entry>> {
            val copy = LinkedHashMap<Project, List<Entry>>()
            ENTRIES.forEach { project, entries ->
                if (entries.isNotEmpty()) {
                    copy[project] = ArrayList(entries)
                }
            }
            return copy
        }

        /**
         * Metadata of the last multirun launch of an app with this name, or null when the app was
         * never started by Multiple Run. The entry's handler/startedAt may belong to a dead process -
         * callers must only use the descriptive fields (group, limit, env profile, condition).
         */
        @JvmStatic
        fun findMetadataByName(project: Project, appName: String?): Entry? {
            val byName = LAST_BY_NAME[project]
            return if (byName == null || appName == null) null else byName[appName]
        }

        /** The OS pid behind the handler, or -1 when it cannot be determined. */
        @JvmStatic
        fun pidOf(handler: ProcessHandler): Long {
            if (handler is BaseProcessHandler<*>) {
                try {
                    return handler.getProcess().pid()
                } catch (ignored: UnsupportedOperationException) {
                    // some Process implementations don't expose a pid
                }
            }
            return -1
        }
    }
}
