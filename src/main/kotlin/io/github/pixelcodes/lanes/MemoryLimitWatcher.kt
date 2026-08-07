package io.github.pixelcodes.lanes

import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Background watcher that raises IDE notifications, independently of the Lanes Monitor tool
 * window, so you get warned even when the monitor is closed. It handles two things:
 * - memory: an application with a configured limit crossing [ALERT_THRESHOLD_PERCENT]
 *   of it (once per process, until it is restarted);
 * - health: an application whose `port:`/`http` "Ready when" condition stays down
 *   for [UNHEALTHY_STREAK] consecutive checks - notified once, with a Restart action,
 *   until it recovers.
 */
class MemoryLimitWatcher private constructor() {

    companion object {
        const val ALERT_THRESHOLD_PERCENT = 90

        /** Consecutive failed health checks before an app is reported unhealthy. */
        const val UNHEALTHY_STREAK = 3

        /** Consecutive over-threshold samples before a high-CPU alert is raised. */
        const val CPU_SUSTAINED_CHECKS = 3
        private const val PERIOD_SECONDS = 10L
        private val LOG = Logger.getInstance(MemoryLimitWatcher::class.java)

        private val started = AtomicBoolean()

        /** Weak keys: entries vanish together with their terminated process handlers. */
        private val alreadyNotified: MutableSet<ProcessHandler> =
            Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<ProcessHandler, Boolean>()))

        /** Consecutive down-count per process, for the health check. */
        private val downStreaks: MutableMap<ProcessHandler, Int> =
            Collections.synchronizedMap(WeakHashMap<ProcessHandler, Int>())

        /** Processes already reported unhealthy, cleared when they recover. */
        private val unhealthyNotified: MutableSet<ProcessHandler> =
            Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<ProcessHandler, Boolean>()))

        /** Previous CPU-time sample per pid and its timestamp, for the sustained-CPU delta. */
        private val prevCpuByPid: MutableMap<Long, Double> = Collections.synchronizedMap(HashMap<Long, Double>())

        @Volatile
        private var prevCpuNanos: Long = 0

        /** Consecutive over-threshold CPU samples per process. */
        private val cpuOverStreaks: MutableMap<ProcessHandler, Int> =
            Collections.synchronizedMap(WeakHashMap<ProcessHandler, Int>())

        /** Processes already reported for high CPU, cleared when they drop back under the threshold. */
        private val cpuNotified: MutableSet<ProcessHandler> =
            Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<ProcessHandler, Boolean>()))

        /** Starts the periodic check once per IDE session; called whenever a process is registered. */
        @JvmStatic
        fun ensureStarted() {
            if (started.compareAndSet(false, true)) {
                AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                    { checkAll() }, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS)
            }
        }

        /** true when the measured usage crossed the alert threshold of the configured limit. */
        @JvmStatic
        fun isNearLimit(rssKb: Long, limitMb: Int): Boolean = isNearLimit(rssKb, limitMb, ALERT_THRESHOLD_PERCENT)

        /** Same, with a configurable threshold percent. */
        @JvmStatic
        fun isNearLimit(rssKb: Long, limitMb: Int, thresholdPercent: Int): Boolean {
            return rssKb >= 0 && limitMb > 0 && thresholdPercent > 0 &&
                rssKb * 100.0 / (limitMb * 1024L) >= thresholdPercent
        }

        /** Process trees and their usage, resolved once per tick and shared by every check. */
        private class Sample(
            val treeByEntry: Map<LanesProcessRegistry.Entry, Set<Long>>,
            val statsByPid: Map<Long, ProcessStatsSampler.Stats>,
        )

        private fun checkAll() {
            try {
                val snapshot = LanesProcessRegistry.snapshot()
                // one process-table scan and one ps call feed both the memory-limit and the CPU
                // check; they used to resolve the trees and shell out to ps separately each tick
                val sample = sampleAll(snapshot)
                for ((project, entries) in snapshot) {
                    if (!project.isDisposed()) {
                        check(project, entries, sample)
                        checkHealth(project, entries)
                    }
                }
                // CPU needs a delta across the whole tick, so it is evaluated once for all projects
                checkCpu(snapshot, sample)
            } catch (t: Throwable) {
                // never let an exception kill the scheduled task
                LOG.warn("Lanes watcher failed", t)
            }
        }

        /**
         * Resolves the process tree and usage of every application this tick has to look at - the
         * ones with a memory limit and the ones with a CPU threshold, in a single pass.
         */
        private fun sampleAll(snapshot: Map<Project, List<LanesProcessRegistry.Entry>>): Sample {
            val rootPidByEntry = LinkedHashMap<LanesProcessRegistry.Entry, Long>()
            for ((project, entries) in snapshot) {
                if (project.isDisposed()) {
                    continue
                }
                for (entry in entries) {
                    val watchesMemory = entry.memoryLimitMb != null && entry.memoryLimitMb > 0
                    val watchesCpu = entry.cpuAlertThreshold > 0
                    if ((watchesMemory || watchesCpu) && !entry.handler.isProcessTerminated()) {
                        rootPidByEntry[entry] = LanesProcessRegistry.pidOf(entry.handler)
                    }
                }
            }
            if (rootPidByEntry.isEmpty()) {
                return Sample(emptyMap(), emptyMap())
            }

            val treeByRootPid = ProcessStatsSampler.processTreePidsFor(rootPidByEntry.values)
            val treeByEntry = LinkedHashMap<LanesProcessRegistry.Entry, Set<Long>>()
            val allPids = LinkedHashSet<Long>()
            for ((entry, rootPid) in rootPidByEntry) {
                val treePids = treeByRootPid[rootPid] ?: continue
                treeByEntry[entry] = treePids
                allPids.addAll(treePids)
            }
            if (allPids.isEmpty()) {
                return Sample(emptyMap(), emptyMap())
            }
            return Sample(treeByEntry, ProcessStatsSampler.samplePids(allPids))
        }

        /** Next consecutive-down streak given the previous one and whether the app is down right now. */
        @JvmStatic
        fun nextDownStreak(previous: Int, down: Boolean): Int = if (down) previous + 1 else 0

        /** Whether a high-CPU alert is due: the app has been over its threshold for enough checks. */
        @JvmStatic
        fun isCpuSustained(consecutiveOver: Int, checks: Int): Boolean = consecutiveOver >= checks

        /**
         * Reports applications whose CPU % (docker-stats style, delta between two ticks) stays at or
         * above their configured `cpuAlertThreshold` for [CPU_SUSTAINED_CHECKS] consecutive
         * checks. Cross-platform (uses the same sampler as the monitor). The alert re-arms when the app
         * drops back under the threshold. The first tick only records the baseline.
         */
        private fun checkCpu(snapshot: Map<Project, List<LanesProcessRegistry.Entry>>, sample: Sample) {
            val now = System.nanoTime()
            val elapsedSeconds = if (prevCpuNanos == 0L) -1.0 else (now - prevCpuNanos) / 1_000_000_000.0

            val projectByEntry = HashMap<LanesProcessRegistry.Entry, Project>()
            for ((project, entries) in snapshot) {
                if (project.isDisposed()) {
                    continue
                }
                for (entry in entries) {
                    projectByEntry[entry] = project
                }
            }
            // only the applications with a CPU threshold are evaluated; the shared sample may also
            // carry trees that are there just for the memory-limit check
            val treeByEntry = sample.treeByEntry.filterKeys { it.cpuAlertThreshold > 0 }
            val statsByPid = sample.statsByPid
            if (treeByEntry.isEmpty() || statsByPid.isEmpty()) {
                prevCpuByPid.clear()
                prevCpuNanos = now
                return
            }

            val prev = HashMap(prevCpuByPid)
            if (elapsedSeconds > 0) {
                for ((entry, pids) in treeByEntry) {
                    val deltaCpuSeconds = ProcessStatsSampler.cpuDeltaSeconds(statsByPid, prev, pids)
                    val cpuPercent = if (deltaCpuSeconds >= 0) deltaCpuSeconds / elapsedSeconds * 100 else -1.0
                    val over = cpuPercent >= 0 && cpuPercent >= entry.cpuAlertThreshold

                    val streak = nextDownStreak(cpuOverStreaks.getOrDefault(entry.handler, 0), over)
                    cpuOverStreaks[entry.handler] = streak
                    if (!over) {
                        cpuNotified.remove(entry.handler)
                        continue
                    }
                    if (isCpuSustained(streak, CPU_SUSTAINED_CHECKS) && cpuNotified.add(entry.handler)) {
                        val project = projectByEntry[entry]
                        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Lanes")
                            .createNotification("High CPU usage",
                                                String.format(Locale.US,
                                                    "'%s' has been using %.0f%% CPU (threshold %d%%) for %d checks.",
                                                    entry.appName, cpuPercent, entry.cpuAlertThreshold,
                                                    CPU_SUSTAINED_CHECKS),
                                                NotificationType.WARNING)
                        val environment = entry.environment
                        if (environment != null) {
                            notification.addAction(NotificationAction.createSimpleExpiring("Restart") {
                                ExecutionUtil.restart(environment)
                            })
                        }
                        notification.notify(project)
                    }
                }
            }

            prevCpuByPid.clear()
            statsByPid.forEach { (pid, stats) -> prevCpuByPid[pid] = stats.cpuTimeSeconds }
            prevCpuNanos = now
        }

        /** Whether to raise the unhealthy alert: the streak reached the limit and we did not alert yet. */
        @JvmStatic
        fun shouldAlertUnhealthy(streak: Int, alreadyNotified: Boolean): Boolean =
            streak >= UNHEALTHY_STREAK && !alreadyNotified

        /**
         * Re-checks the port/http readiness of each app and reports the ones that stayed down for
         * [UNHEALTHY_STREAK] consecutive checks, once, with a Restart action. The alert re-arms
         * when the app recovers, so a later outage is reported again.
         */
        private fun checkHealth(project: Project, entries: List<LanesProcessRegistry.Entry>) {
            for (entry in entries) {
                if (entry.handler.isProcessTerminated()) {
                    downStreaks.remove(entry.handler)
                    unhealthyNotified.remove(entry.handler)
                    continue
                }
                val condition = RunConfigurationHelper.parseReadyCondition(entry.readyCondition)
                val down: Boolean = when (condition.type) {
                    RunConfigurationHelper.ReadyCondition.Type.PORT -> !RunConfigurationHelper.isPortOpen(condition.port)
                    RunConfigurationHelper.ReadyCondition.Type.HTTP -> !RunConfigurationHelper.isHttpHealthy(condition.value)
                    else -> continue // no monitorable readiness condition
                }

                val streak = nextDownStreak(downStreaks.getOrDefault(entry.handler, 0), down)
                downStreaks[entry.handler] = streak
                if (!down) {
                    unhealthyNotified.remove(entry.handler) // recovered: allow a future alert
                    continue
                }
                if (shouldAlertUnhealthy(streak, unhealthyNotified.contains(entry.handler))) {
                    unhealthyNotified.add(entry.handler)
                    val notification = NotificationGroupManager.getInstance().getNotificationGroup("Lanes")
                        .createNotification("Application unhealthy",
                                            "'${entry.appName}' is not answering its readiness check (${entry.readyCondition}).",
                                            NotificationType.WARNING)
                    val environment = entry.environment
                    if (environment != null) {
                        notification.addAction(NotificationAction.createSimpleExpiring("Restart") {
                            ExecutionUtil.restart(environment)
                        })
                    }
                    notification.notify(project)
                }
            }
        }

        private fun check(project: Project, entries: List<LanesProcessRegistry.Entry>, sample: Sample) {
            // only this project's applications with a configured limit, out of the shared sample
            val ofThisProject = HashSet(entries)
            val treeByEntry = sample.treeByEntry.filterKeys { entry ->
                ofThisProject.contains(entry) && (entry.memoryLimitMb ?: 0) > 0
            }
            if (treeByEntry.isEmpty()) {
                return
            }

            val statsByPid = sample.statsByPid
            for ((entry, pids) in treeByEntry) {
                val stats = ProcessStatsSampler.aggregate(statsByPid, pids)
                val limit = entry.memoryLimitMb ?: continue
                if (stats == null || !isNearLimit(stats.rssKb, limit, entry.memAlertThreshold)) {
                    continue
                }
                if (!alreadyNotified.add(entry.handler)) {
                    continue // this process was already warned about
                }
                val percent = stats.rssKb * 100.0 / (limit * 1024L)
                val usage = String.format(Locale.US, "'%s' is using %s of its %d MB limit (%.0f%%).",
                                          entry.appName, ProcessStatsSampler.formatMemory(stats.rssKb),
                                          limit, percent)
                val environment = entry.environment
                if (entry.memLimitRestart && environment != null) {
                    // docker-like OOM handling: restart the application instead of letting it degrade
                    NotificationGroupManager.getInstance().getNotificationGroup("Lanes")
                        .createNotification("Application restarted (memory limit)",
                                            "$usage Restarting it as configured.",
                                            NotificationType.WARNING)
                        .notify(project)
                    ApplicationManager.getApplication().invokeLater {
                        ExecutionUtil.restart(environment)
                    }
                } else {
                    NotificationGroupManager.getInstance().getNotificationGroup("Lanes")
                        .createNotification("Memory limit almost reached", usage, NotificationType.WARNING)
                        .notify(project)
                }
            }
        }
    }
}
