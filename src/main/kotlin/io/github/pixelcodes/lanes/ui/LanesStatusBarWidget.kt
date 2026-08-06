package io.github.pixelcodes.lanes.ui

import java.awt.Component
import java.awt.event.MouseEvent
import java.util.LinkedHashSet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil

import io.github.pixelcodes.lanes.LanesProcessRegistry
import io.github.pixelcodes.lanes.ProcessStatsSampler
import io.github.pixelcodes.lanes.RunConfigurationHelper

/**
 * A status bar widget summarizing the applications started by Lanes: how many are running,
 * their combined memory, and how many are unhealthy (a port/http "Ready when" that is currently
 * down). Clicking it opens the Lanes Monitor. It refreshes on a background timer and hides
 * itself (empty text) when nothing this plugin started is running.
 */
class LanesStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var updater: ScheduledFuture<*>? = null
    @Volatile
    private var text: String = ""

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updater = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ refresh() }, 0L, REFRESH_SECONDS.toLong(), TimeUnit.SECONDS)
    }

    override fun dispose() {
        updater?.cancel(true)
        updater = null
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    // --- TextPresentation -----------------------------------------------------------------------

    override fun getText(): String = text

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getTooltipText(): String =
        "Lanes: running apps, total memory and unhealthy count. Click to open the monitor."

    override fun getClickConsumer(): Consumer<MouseEvent> {
        return Consumer { _ ->
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(LanesMonitorToolWindowFactory.TOOL_WINDOW_ID)
            toolWindow?.activate(null)
        }
    }

    // --- refresh --------------------------------------------------------------------------------

    private fun refresh() {
        if (project.isDisposed()) {
            return
        }
        val entries = LanesProcessRegistry.getEntries(project)

        // resolve every process tree in a single process-table scan, then reuse it: this used to
        // walk the tree of each app twice per tick (once to collect pids, once to aggregate)
        val rootPidByEntry = LinkedHashMap<LanesProcessRegistry.Entry, Long>()
        for (entry in entries) {
            rootPidByEntry[entry] = LanesProcessRegistry.pidOf(entry.handler)
        }
        val treeByRootPid = ProcessStatsSampler.processTreePidsFor(rootPidByEntry.values)
        val allPids = LinkedHashSet<Long>()
        for (treePids in treeByRootPid.values) {
            allPids.addAll(treePids)
        }
        val statsByPid = ProcessStatsSampler.samplePids(allPids)

        var totalRssKb = 0L
        var unhealthy = 0
        for ((entry, rootPid) in rootPidByEntry) {
            val treePids = treeByRootPid[rootPid]
            val stats = if (treePids == null) null else ProcessStatsSampler.aggregate(statsByPid, treePids)
            if (stats != null) {
                totalRssKb += stats.rssKb
            }
            if (isUnhealthy(entry.readyCondition)) {
                unhealthy++
            }
        }

        text = widgetText(entries.size, totalRssKb, unhealthy)
        statusBar?.updateWidget(ID)
    }

    companion object {
        const val ID = "Lanes.Monitor.Widget"
        private const val REFRESH_SECONDS = 5

        /** True when the app has a port/http readiness condition that is currently not answering. */
        private fun isUnhealthy(readyCondition: String?): Boolean {
            val condition = RunConfigurationHelper.parseReadyCondition(readyCondition)
            return when (condition.type) {
                RunConfigurationHelper.ReadyCondition.Type.PORT -> !RunConfigurationHelper.isPortOpen(condition.port)
                RunConfigurationHelper.ReadyCondition.Type.HTTP -> !RunConfigurationHelper.isHttpHealthy(condition.value)
                else -> false
            }
        }

        /**
         * Builds the widget label. Empty when nothing is running (so the widget stays out of the way),
         * otherwise "▶ N apps · &lt;mem&gt;" with a "· ⚠ K" suffix when K apps are unhealthy.
         */
        @JvmStatic
        fun widgetText(appCount: Int, totalRssKb: Long, unhealthy: Int): String {
            if (appCount <= 0) {
                return ""
            }
            val sb = StringBuilder()
            sb.append('▶').append(' ').append(appCount).append(if (appCount == 1) " app" else " apps")
            sb.append(" · ").append(ProcessStatsSampler.formatMemory(totalRssKb))
            if (unhealthy > 0) {
                sb.append(" · ⚠ ").append(unhealthy)
            }
            return sb.toString()
        }
    }
}
