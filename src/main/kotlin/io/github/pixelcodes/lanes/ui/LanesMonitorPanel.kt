package io.github.pixelcodes.lanes.ui

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableColumnModel

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil

import io.github.pixelcodes.lanes.MemoryHistory
import io.github.pixelcodes.lanes.LanesIcons
import io.github.pixelcodes.lanes.LanesProcessRegistry
import io.github.pixelcodes.lanes.LanesRunConfiguration
import io.github.pixelcodes.lanes.LanesRunnerState
import io.github.pixelcodes.lanes.ProcessStatsSampler
import io.github.pixelcodes.lanes.RunConfigurationHelper
import io.github.pixelcodes.lanes.StandaloneEnvRegistry
import io.github.pixelcodes.lanes.StopRunningLanesConfigurationsAction

/**
 * The "Lanes Monitor" tool window content: a docker-stats-like table with EVERY process
 * the IDE is running - the applications started by Lanes and standalone (singleton) runs
 * alike. The first column shows where the app came from: the Lanes icon for grouped apps,
 * the run configuration's own icon (node, npm, jest, ...) for standalone ones. Live memory/CPU,
 * listening ports, uptime and health are refreshed every couple of seconds; rows can be
 * restarted, stopped or force-killed, and any process squatting a TCP port can be killed
 * through the "Kill Process on Port" action. Columns are resizable by dragging their headers.
 */
class LanesMonitorPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {

    /**
     * Column header names the user chose to hide (empty = everything visible).
     * Restored from [PropertiesComponent] so the choice survives closing the tool window,
     * reopening the project and restarting the IDE.
     */
    private val hiddenColumns: MutableSet<String> = loadHiddenColumns(project)

    /**
     * A process the IDE ran, captured on the EDT (descriptor access) for the refresh.
     * [running] is false once the process terminated: the row stays in the table as history
     * (docker-ps-style) until the user removes it or closes its run tab.
     */
    private class ProcessSnapshot(
        val name: String,
        val icon: Icon?,
        val handler: ProcessHandler,
        val descriptor: RunContentDescriptor,
        val running: Boolean,
    )

    /** Immutable display row; built off the EDT with all texts precomputed. */
    class Row(
        @JvmField val name: String,
        @JvmField val icon: Icon?,
        @JvmField val lanesName: String?,
        @JvmField val envFileName: String?,
        @JvmField val handler: ProcessHandler?,
        @JvmField val descriptor: RunContentDescriptor?,
        /** Lanes metadata (limit/condition/...) or null for plain standalone runs. */
        @JvmField val meta: LanesProcessRegistry.Entry?,
        @JvmField val pid: String,
        @JvmField val ports: String,
        @JvmField val uptime: String,
        @JvmField val status: String,
        @JvmField val memUsage: String,
        @JvmField val memPercent: String,
        @JvmField val cpuPercent: String,
        @JvmField val memTrend: DoubleArray,
        /** The env-file profiles configured on the app's Lanes group (empty when unknown). */
        envProfiles: List<String>?,
        /** True when the user paused monitoring for this app: values are frozen, the row is greyed. */
        @JvmField val paused: Boolean = false,
        /**
         * False once the application terminated: the row stays as history, greyed and without the
         * live indicator on its icon, and can be removed from the list by the user.
         */
        @JvmField val running: Boolean = true,
    ) {
        @JvmField val envProfiles: List<String> = envProfiles ?: emptyList()
    }

    private val model: ListTableModel<Row>
    private val table: TableView<Row>
    private val timer: Timer
    private val sampling = AtomicBoolean()
    private val configuredAppIcon: Icon

    /** Swapped between the table and the "Lanes" empty-state illustration; see [CARD_TABLE]/[CARD_EMPTY]. */
    private val cardLayout = CardLayout()
    private val contentCards = JPanel(cardLayout)

    /** CPU time per pid at the previous sample - the baseline for the docker-style CPU %. */
    private var prevCpuSecondsByPid: Map<Long, Double> = emptyMap()
    private var prevSampleNanos: Long = 0

    /** Recent memory-percent samples per process, feeding the "Mem trend" sparkline column. */
    private val memHistory: MutableMap<ProcessHandler, ArrayDeque<Double>> = HashMap()

    /** Full session memory history per process, for the click-to-open chart (bounded). */
    private val fullHistory: MutableMap<ProcessHandler, MutableList<MemoryHistory.Sample>> = ConcurrentHashMap()

    /** Apps the user paused monitoring for: they keep running, but are not sampled (no ps/lsof/tree walk). */
    private val pausedHandlers: MutableSet<ProcessHandler> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Last built row per handler, so a paused app can keep showing its frozen last values. */
    private val lastRowByHandler: MutableMap<ProcessHandler, Row> = HashMap()

    /**
     * Terminated apps the user removed from the list. The table is rebuilt from the IDE's run
     * descriptors on every refresh, so a removed app would come straight back without this;
     * entries drop out on their own once the app's run tab is closed.
     */
    private val removedHandlers: MutableSet<ProcessHandler> = Collections.newSetFromMap(ConcurrentHashMap())
    private val sparklineRenderer = SparklineCellRenderer()
    private val portsRenderer = PortsCellRenderer()
    private val statusRenderer = StatusCellRenderer()

    init {
        // a row only exists here because the app actually ran as part of a Lanes group, so
        // it is always "configured" in that context - same icon LanesRunConfiguration.getIcon()
        // shows for a group that has child apps, never the plain type mark
        configuredAppIcon = LanesIcons.Configured

        model = ListTableModel(
            object : ColumnInfo<Row, String>("Name") {
                override fun valueOf(row: Row): String? = row.name

                override fun getRenderer(row: Row): TableCellRenderer {
                    return object : DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: JTable, value: Any?, isSelected: Boolean,
                            hasFocus: Boolean, rowIndex: Int, column: Int,
                        ): Component {
                            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, rowIndex, column)
                            // Lanes icon for grouped apps, the app's own icon for standalone runs
                            setIcon(row.icon)
                            return this
                        }
                    }
                }
            },
            // shows the top-level group the user started (root), falling back to the owning group;
            // row.lanesName stays the owning group for restart/switch lookups (see findGroupConfig)
            column("Lanes") { it.meta?.rootLanesName ?: it.lanesName },
            object : ColumnInfo<Row, String>("Env") {
                // the root group's env profile when nested, else this app's own (see Lanes column)
                override fun valueOf(row: Row): String? = row.meta?.rootEnvFileName ?: row.envFileName

                override fun getRenderer(row: Row): TableCellRenderer =
                    EnvCellRenderer(hasLoadedEnv(row), row.envProfiles.size > 1)
            },
            column("PID") { it.pid },
            object : ColumnInfo<Row, String>("Ports") {
                override fun valueOf(row: Row): String? = row.ports

                override fun getRenderer(row: Row): TableCellRenderer = portsRenderer
            },
            column("Uptime") { it.uptime },
            object : ColumnInfo<Row, String>("Status") {
                override fun valueOf(row: Row): String? = row.status

                override fun getRenderer(row: Row): TableCellRenderer = statusRenderer
            },
            column("Mem Usage / Limit") { it.memUsage },
            column("Mem %") { it.memPercent },
            object : ColumnInfo<Row, DoubleArray>("Mem trend") {
                override fun valueOf(row: Row): DoubleArray? = row.memTrend

                override fun getRenderer(row: Row): TableCellRenderer = sparklineRenderer
            },
            column("CPU %") { it.cpuPercent },
        )
        table = object : TableView<Row>(model) {
            // grey out a paused row across every column: text cells get the disabled foreground, the
            // sparkline is told to paint muted (it ignores foreground). Selection keeps its own colors.
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                val component = super.prepareRenderer(renderer, row, column)
                val item = this@LanesMonitorPanel.model.getItem(convertRowIndexToModel(row))
                // both a paused app and one that already exited are drawn muted; they are told
                // apart by the live dot on the icon, which only a running app has
                val muted = item != null && (item.paused || !item.running)
                if (component is SparklineCellRenderer) {
                    component.paused = muted
                } else if (muted && !isCellSelected(row, column)) {
                    component.setForeground(UIUtil.getLabelDisabledForeground())
                }
                return component
            }
        }
        // apply initial widths (and honor any hidden columns); all columns stay resizable
        applyColumnVisibility()
        // batch actions: the row actions operate on every selected row
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)

        // shared action instances: the same objects go into the toolbar and the right-click popup
        val restartSelected: AnAction = RestartSelectedAction()
        val stopSelected: AnAction = StopSelectedAction()
        val killSelected: AnAction = KillSelectedAction()
        val pauseMonitoring: AnAction = PauseMonitoringAction()
        val resumeMonitoring: AnAction = ResumeMonitoringAction()
        val removeFromList: AnAction = RemoveFromListAction()
        val restartUnhealthy: AnAction = RestartUnhealthyAction()

        val rowActions = DefaultActionGroup()
        rowActions.add(restartSelected)
        rowActions.add(stopSelected)
        rowActions.add(killSelected)
        rowActions.addSeparator()
        rowActions.add(pauseMonitoring)
        rowActions.add(resumeMonitoring)
        rowActions.add(removeFromList)
        rowActions.addSeparator()
        rowActions.add(restartUnhealthy)

        val toolbarGroup = DefaultActionGroup()
        toolbarGroup.add(object : DumbAwareAction("Refresh", "Refresh the process list now", LanesIcons.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refresh()
            }
        })
        // add the row actions individually (avoids the deprecated DefaultActionGroup.addAll(ActionGroup))
        toolbarGroup.addAll(restartSelected, stopSelected, killSelected)
        toolbarGroup.addSeparator()
        toolbarGroup.addAll(pauseMonitoring, resumeMonitoring, removeFromList)
        toolbarGroup.addSeparator()
        toolbarGroup.add(restartUnhealthy)
        toolbarGroup.add(MemoryAnalysisAction())
        toolbarGroup.add(KillByPortAction())
        toolbarGroup.add(ShowColumnsAction())
        toolbarGroup.addSeparator()
        toolbarGroup.add(RestartAllWithCountAction())
        toolbarGroup.add(StopAllWithCountAction())
        toolbarGroup.add(BatchEnvSwitchAction())
        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("LanesMonitor", toolbarGroup, false)
        toolbar.setTargetComponent(table)
        setToolbar(toolbar.getComponent())
        contentCards.add(ScrollPaneFactory.createScrollPane(table), CARD_TABLE)
        contentCards.add(buildEmptyStatePanel(), CARD_EMPTY)
        cardLayout.show(contentCards, CARD_EMPTY)
        setContent(contentCards)
        PopupHandler.installPopupMenu(table, rowActions, "LanesMonitorPopup")
        // double click on a row jumps to the console tab of that application
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return
                }
                // single click on the Ports column opens the port(s) in the browser;
                // single click on the Mem trend column opens the full memory chart
                if (e.getClickCount() == 1 && isPortsColumn(e.getPoint())) {
                    openPortsAt(e)
                } else if (e.getClickCount() == 1 && isColumn(e.getPoint(), "Env")) {
                    openEnvAt(e)
                } else if (e.getClickCount() == 1 && isColumn(e.getPoint(), "Mem trend")) {
                    openMemChartAt(e)
                } else if (e.getClickCount() == 2) {
                    focusRunTabOfSelectedRow()
                }
            }
        })

        timer = Timer(REFRESH_INTERVAL_MS) {
            // don't burn cycles while the tool window is hidden
            if (isShowing()) {
                refresh()
            }
        }
        timer.start()
        refresh()
    }

    /** The "Lanes" illustration + hint shown instead of the table while no process is running. */
    private fun buildEmptyStatePanel(): JComponent {
        val icon = JLabel(LanesIcons.EmptyState)
        icon.setAlignmentX(Component.CENTER_ALIGNMENT)

        val title = JBLabel("No applications are being monitored")
        title.setFont(JBFont.label().asBold())
        title.setAlignmentX(Component.CENTER_ALIGNMENT)

        val subtitle = JBLabel("Add an application or discover running processes to get started")
        subtitle.setForeground(UIUtil.getContextHelpForeground())
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT)

        val stack = JPanel()
        stack.setOpaque(false)
        stack.setLayout(BoxLayout(stack, BoxLayout.Y_AXIS))
        stack.add(icon)
        stack.add(Box.createVerticalStrut(JBUI.scale(16)))
        stack.add(title)
        stack.add(Box.createVerticalStrut(JBUI.scale(4)))
        stack.add(subtitle)

        // GridBagLayout with a single, unconstrained child centers it both ways for free
        val wrapper = JPanel(GridBagLayout())
        wrapper.add(stack)
        return wrapper
    }

    /** All column header names, in model order. */
    private fun allColumnNames(): List<String> {
        val names = ArrayList<String>()
        for (i in 0 until model.getColumnCount()) {
            names.add(model.getColumnName(i))
        }
        return names
    }

    /**
     * Rebuilds the table's column model from [hiddenColumns]: visible columns are re-added in
     * model order with their preferred width. TableView resolves renderers/values through the model
     * index, so hiding a column never disturbs the others.
     */
    private fun applyColumnVisibility() {
        val cm: TableColumnModel = table.getColumnModel()
        while (cm.getColumnCount() > 0) {
            cm.removeColumn(cm.getColumn(0))
        }
        for (modelIdx in 0 until model.getColumnCount()) {
            val name = model.getColumnName(modelIdx)
            if (hiddenColumns.contains(name)) {
                continue
            }
            val col = TableColumn(modelIdx)
            col.setHeaderValue(name)
            if (modelIdx < PREFERRED_WIDTHS.size) {
                col.setPreferredWidth(PREFERRED_WIDTHS[modelIdx])
            }
            cm.addColumn(col)
        }
    }

    private fun selectedRow(): Row? = table.getSelectedObject()

    /** Every selected row (batch actions operate on all of them). */
    private fun selectedRows(): List<Row> = table.getSelectedObjects()

    /** Brings the console tab of the selected application to front (Run or Debug tool window). */
    private fun focusRunTabOfSelectedRow() {
        val row = selectedRow()
        if (row == null || row.descriptor == null) {
            return
        }
        val content = row.descriptor.getAttachedContent()
        if (content != null) {
            val manager = content.getManager()
            if (manager != null) {
                manager.setSelectedContent(content)
            }
        }
        val toolWindow = RunContentManager.getInstance(project).getToolWindowByDescriptor(row.descriptor)
        if (toolWindow != null) {
            toolWindow.activate(null)
        }
    }

    /** True when the point falls inside the (possibly reordered) "Ports" column. */
    private fun isPortsColumn(point: Point): Boolean = isColumn(point, "Ports")

    /** True when the point falls inside the (possibly reordered) column with the given header name. */
    private fun isColumn(point: Point, columnName: String): Boolean {
        val viewColumn = table.columnAtPoint(point)
        if (viewColumn < 0) {
            return false
        }
        return columnName == model.getColumnName(table.convertColumnIndexToModel(viewColumn))
    }

    /** Opens the full-session memory chart for the clicked row (on the Chart tab). */
    private fun openMemChartAt(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.getPoint())
        if (viewRow < 0) {
            return
        }
        val row = model.getItem(table.convertRowIndexToModel(viewRow))
        if (row != null) {
            openMemChart(row, false)
        }
    }

    /** Opens the full-session memory chart/analysis dialog for a row, on the chosen initial tab. */
    private fun openMemChart(row: Row, analysisFirst: Boolean) {
        val stored = fullHistory[row.handler]
        val copy: List<MemoryHistory.Sample> = if (stored == null) {
            emptyList()
        } else {
            synchronized(stored) { ArrayList(stored) }
        }
        MemoryChartDialog(project, row.name, copy, LanesProcessRegistry.pidOf(row.handler!!), analysisFirst).show()
    }

    /**
     * Handles a click on the Env cell: when the app's group has a single environment (or none) it
     * opens the read-only variable viewer straight away, as before; when the group has more than one
     * environment profile it opens a dropdown to switch between them, with a "view variables" entry.
     */
    private fun openEnvAt(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.getPoint())
        if (viewRow < 0) {
            return
        }
        val row = model.getItem(table.convertRowIndexToModel(viewRow)) ?: return
        if (row.envProfiles.size > 1) {
            showEnvSwitchPopup(row, e)
        } else if (hasLoadedEnv(row)) {
            openEnvViewer(row)
        }
    }

    /** The read-only viewer with the environment variables loaded for the row's application. */
    private fun openEnvViewer(row: Row) {
        val meta = row.meta
        if (meta != null && meta.loadedEnv.isNotEmpty()) {
            EnvVarsDialog(project, row.name, row.envFileName ?: "-", meta.includeSystemEnv, meta.loadedEnv).show()
            return
        }
        // standalone app: the variables the Node env-file module loaded into it
        val handler = row.handler ?: return
        val standalone = StandaloneEnvRegistry.find(handler) ?: return
        if (standalone.loadedEnv.isNotEmpty()) {
            EnvVarsDialog(project, row.name, standalone.envFileName, standalone.includeSystemEnv, standalone.loadedEnv).show()
        }
    }

    /**
     * Dropdown for the Env cell of a group with several profiles: one item per profile switches
     * **just this application** to it (via a per-app override) and restarts only that app; the
     * active one is disabled; a trailing "view loaded variables" item opens the read-only viewer.
     * (The toolbar's Switch Environment button is the group-wide counterpart.)
     */
    private fun showEnvSwitchPopup(row: Row, e: MouseEvent) {
        val menu = JPopupMenu("Environment")
        for (profile in row.envProfiles) {
            val active = RunConfigurationHelper.envFileDisplayName(profile) == row.envFileName
            val item = javax.swing.JMenuItem(
                RunConfigurationHelper.envFileDisplayName(profile) + (if (active) "  (active)" else ""))
            item.setToolTipText(profile)
            if (active) {
                item.setEnabled(false)
            } else {
                item.addActionListener { switchAppEnv(row, profile) }
            }
            menu.add(item)
        }
        if (hasLoadedEnv(row)) {
            menu.addSeparator()
            val detail = javax.swing.JMenuItem("View loaded variables…", AllIcons.Actions.Show)
            detail.addActionListener { openEnvViewer(row) }
            menu.add(detail)
        }
        menu.show(table, e.getX(), e.getY())
    }

    /** The Lanes group configuration with this name, or null when it no longer exists. */
    private fun findGroupConfig(groupName: String?): LanesRunConfiguration? {
        if (groupName == null) {
            return null
        }
        for (cfg in RunManager.getInstance(project).allConfigurationsList) {
            if (cfg is LanesRunConfiguration && groupName == cfg.getName()) {
                return cfg
            }
        }
        return null
    }

    /** Live rows belonging to a Lanes group (matched by the group name shown on the row). */
    private fun runningRowsOfGroup(groupName: String?): List<Row> {
        val result = ArrayList<Row>()
        for (row in model.getItems()) {
            if (groupName != null && groupName == row.lanesName
                && row.handler != null && !row.handler.isProcessTerminated()) {
                result.add(row)
            }
        }
        return result
    }

    /**
     * Switches **a single application** to `envProfile` through a per-app env override
     * (persisted on the group, so it also applies to the next launch) and restarts only that app -
     * everything else in the group keeps running with its own environment. Because Lanes
     * bakes the environment into each app at launch, a plain restart would keep the old values, so
     * the app is stopped and relaunched through the group's pipeline with the new profile - keeping
     * its executor (Run/Debug/...) and staying tracked in the monitor.
     */
    private fun switchAppEnv(row: Row, envProfile: String) {
        val group = findGroupConfig(row.lanesName)
        if (group == null) {
            Messages.showErrorDialog(project,
                "The Lanes group '${row.lanesName}' no longer exists.", "Switch Environment")
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "Switch '${row.name}' to environment '${RunConfigurationHelper.envFileDisplayName(envProfile)}'" +
                " and restart just this application?",
            "Switch Environment", "Switch & Restart", "Cancel", Messages.getQuestionIcon())
        if (answer != Messages.YES) {
            return
        }
        // per-app override wins over the group environment for this app only
        val appEnvFiles = LinkedHashMap(group.getAppEnvFiles())
        appEnvFiles[row.name] = envProfile
        group.setAppEnvFiles(appEnvFiles)
        relaunchApp(group, row.name, executorOf(row), row.handler)
    }

    /** The executor the app is running under (so a relaunch keeps Debug as Debug), Run as fallback. */
    private fun executorOf(row: Row): Executor {
        val meta = row.meta
        if (meta != null && meta.environment != null) {
            val executor = meta.environment.getExecutor()
            if (executor != null) {
                return executor
            }
        }
        return DefaultRunExecutor.getRunExecutorInstance()
    }

    /**
     * Stops the given app and relaunches only it through the group's pipeline (which re-reads the
     * environment, re-registers the app in the monitor and honors the group's settings), under the
     * given executor. Used by both the per-row env switch and the batch Switch Environment button.
     */
    private fun relaunchApp(group: LanesRunConfiguration, appName: String, executor: Executor, oldHandler: ProcessHandler?) {
        var base: RunConfiguration? = null
        for (child in group.getRunConfigurations()) {
            if (appName == child.getName()) {
                base = child
                break
            }
        }
        val target = base ?: return // the app was removed from the group in the meantime
        val runner = ProgramRunner.getRunner(executor.getId(), target)
        if (runner == null) {
            Messages.showErrorDialog(project,
                "No runner is available for '$appName' with ${executor.getId()}.", "Switch Environment")
            return
        }
        if (oldHandler != null && !oldHandler.isProcessTerminated()) {
            oldHandler.destroyProcess()
        }
        val state: LanesRunnerState = group.createStateForApps(listOf(target))
        ApplicationManager.getApplication().executeOnPooledThread {
            // let the old process release its port(s) before the new one starts
            if (oldHandler != null) {
                oldHandler.waitFor(10_000)
            }
            ApplicationManager.getApplication().invokeLater {
                state.execute(executor, runner)
                refresh()
            }
        }
    }

    /**
     * Modal combo to pick one env profile to apply to every running app (null when cancelled). The
     * currently-active profile(s) are marked "(active)" and one is pre-selected, so the dialog opens
     * on the environment already in use.
     */
    private fun chooseEnvProfile(profiles: List<String>, activePaths: Set<String>): String? {
        val combo = ComboBox(profiles.toTypedArray())
        combo.setRenderer(EnvProfileListRenderer(activePaths))
        for (profile in profiles) {
            if (activePaths.contains(profile)) {
                combo.setSelectedItem(profile)
                break
            }
        }
        val dialog = object : DialogWrapper(project, true) {
            init {
                setTitle("Switch Environment")
                setOKButtonText("Apply & Restart")
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout(8, 8))
                panel.add(JLabel("Environment applied to all running apps (they will restart):"), BorderLayout.NORTH)
                panel.add(combo, BorderLayout.CENTER)
                return panel
            }

            override fun getPreferredFocusedComponent(): JComponent = combo
        }
        if (!dialog.showAndGet()) {
            return null
        }
        return combo.getSelectedItem() as String?
    }

    /** Shows env profiles by their file name (full path as tooltip), marking the active one(s). */

    /** Opens the port(s) of the clicked row in the browser (a menu when there is more than one). */
    private fun openPortsAt(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.getPoint())
        if (viewRow < 0) {
            return
        }
        val row = model.getItem(table.convertRowIndexToModel(viewRow)) ?: return
        val ports = parsePorts(row.ports)
        if (ports.isEmpty()) {
            return
        }
        if (ports.size == 1) {
            BrowserUtil.browse(urlForPort(ports[0]))
            return
        }
        val menu = JPopupMenu()
        for (port in ports) {
            val item = javax.swing.JMenuItem("Open ${urlForPort(port)}")
            item.addActionListener { BrowserUtil.browse(urlForPort(port)) }
            menu.add(item)
        }
        menu.show(table, e.getX(), e.getY())
    }

    /** Renders the Ports cell as a clickable hyperlink when the row has any listening port. */

    /**
     * Renders the Env cell as a clickable hyperlink when the row has a loaded environment to show.
     * When the app's group has more than one environment profile the cell also gets a `▾`
     * affordance, so it reads as a dropdown: clicking it offers the profiles to switch to (plus a
     * "view variables" entry) instead of opening the viewer straight away.
     */

    /** Colors the Status cell: green for healthy/running, red for a failing readiness check. */

    /** Restarts every selected application; everything else keeps running. */
    private inner class RestartSelectedAction : DumbAwareAction(
        "Restart", "Stop the selected application(s) and start them again (everything else keeps running)",
        LanesIcons.Restart) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            var anyRestartable = false
            for (row in selectedRows()) {
                if (row.descriptor != null) {
                    anyRestartable = true
                    break
                }
            }
            e.getPresentation().setEnabled(anyRestartable)
        }

        override fun actionPerformed(e: AnActionEvent) {
            for (row in selectedRows()) {
                if (row.descriptor != null) {
                    // the platform stops the old process and reruns the same environment; the new
                    // process shows up again on the next refresh (all IDE processes are listed)
                    ExecutionUtil.restart(row.descriptor)
                }
            }
        }
    }

    /** Graceful stop of every selected application - same as the red stop button of its tab. */
    private inner class StopSelectedAction : DumbAwareAction(
        "Stop", "Request the selected application(s) to terminate", LanesIcons.Stop) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // nothing to stop on a history row of an app that already exited
            e.getPresentation().setEnabled(selectedRows().any { it.running })
        }

        override fun actionPerformed(e: AnActionEvent) {
            for (row in selectedRows()) {
                if (row.running) {
                    row.handler?.destroyProcess()
                }
            }
        }
    }

    /** SIGKILL of every selected application and each process it spawned. */
    private inner class KillSelectedAction : DumbAwareAction(
        "Force Kill", "Forcibly kill the selected application(s) and their whole process tree (SIGKILL)",
        AllIcons.Debugger.KillProcess) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // nothing to kill on a history row of an app that already exited
            e.getPresentation().setEnabled(selectedRows().any { it.running })
        }

        override fun actionPerformed(e: AnActionEvent) {
            val rows = selectedRows().filter { it.running }
            if (rows.isEmpty()) {
                return
            }
            val list = StringBuilder()
            for (row in rows) {
                list.append("\n  - '").append(row.name).append("' (PID ").append(row.pid).append(')')
            }
            val answer = Messages.showYesNoDialog(
                project,
                "Forcibly kill the following application(s) and all their child processes?$list\n\n" +
                    "They get no chance to shut down cleanly.",
                "Force Kill", "Kill", "Cancel", Messages.getWarningIcon())
            if (answer != Messages.YES) {
                return
            }
            val handlers = ArrayList<ProcessHandler>()
            for (row in rows) {
                row.handler?.let { handlers.add(it) }
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                for (handler in handlers) {
                    // resolve the tree fresh - children may have been spawned after the last refresh
                    val treePids = ProcessStatsSampler.processTreePids(LanesProcessRegistry.pidOf(handler))
                    for (pid in treePids) {
                        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                    }
                    // tell the IDE the process is gone, so the run tab stops its spinner too
                    handler.destroyProcess()
                }
                ApplicationManager.getApplication().invokeLater { refresh() }
            }
        }
    }

    /**
     * Pauses monitoring for the selected app(s): they keep running, but Lanes stops sampling their
     * memory/CPU (no process-tree walk, no ps/lsof), so a heavy app can be excluded from monitoring.
     * The row stays in the table, frozen at its last values and greyed out.
     */
    private inner class PauseMonitoringAction : DumbAwareAction(
        "Pause Monitoring",
        "Stop sampling memory/CPU for the selected application(s) - they keep running; the row freezes at its last values",
        LanesIcons.PauseMonitoring) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // only a running app can be taken out of sampling; a stopped one is restarted, not resumed
            e.getPresentation().setEnabled(selectedRows().any {
                it.handler != null && it.running && !pausedHandlers.contains(it.handler)
            })
        }

        override fun actionPerformed(e: AnActionEvent) {
            var changed = false
            for (row in selectedRows()) {
                val handler = row.handler ?: continue
                if (row.running && pausedHandlers.add(handler)) {
                    changed = true
                }
            }
            if (changed) {
                refresh()
            }
        }
    }

    /** Resumes monitoring (re-enables sampling) for the selected paused app(s). */
    private inner class ResumeMonitoringAction : DumbAwareAction(
        "Resume Monitoring", "Resume sampling memory/CPU for the selected application(s)",
        LanesIcons.ResumeMonitoring) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // never offered for a stopped app: resuming does not start anything, which read as a
            // broken "start" button before
            e.getPresentation().setEnabled(selectedRows().any {
                it.handler != null && it.running && pausedHandlers.contains(it.handler)
            })
        }

        override fun actionPerformed(e: AnActionEvent) {
            var changed = false
            for (row in selectedRows()) {
                val handler = row.handler ?: continue
                if (row.running && pausedHandlers.remove(handler)) {
                    changed = true
                }
            }
            if (changed) {
                refresh()
            }
        }
    }

    /**
     * Removes the selected stopped app(s) from the list, dropping their history (memory chart
     * samples included). Only stopped apps can be removed: a running one would be listed again on
     * the next refresh, and taking it out of monitoring is what Pause Monitoring is for.
     */
    private inner class RemoveFromListAction : DumbAwareAction(
        "Remove from List",
        "Remove the selected stopped application(s) from the monitor and forget their history",
        AllIcons.General.Remove) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.getPresentation().setEnabled(selectedRows().any { it.handler != null && !it.running })
        }

        override fun actionPerformed(e: AnActionEvent) {
            var changed = false
            for (row in selectedRows()) {
                val handler = row.handler ?: continue
                if (row.running || !removedHandlers.add(handler)) {
                    continue
                }
                memHistory.remove(handler)
                fullHistory.remove(handler)
                lastRowByHandler.remove(handler)
                pausedHandlers.remove(handler)
                changed = true
            }
            if (changed) {
                refresh()
            }
        }
    }

    /** Restarts every application whose readiness status is currently "down". */
    private inner class RestartUnhealthyAction : DumbAwareAction(
        "Restart Unhealthy", "Restart every application whose port/http readiness check is currently down",
        AllIcons.Actions.Restart) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.getPresentation().setEnabled(unhealthyRows(model.getItems()).isNotEmpty())
        }

        override fun actionPerformed(e: AnActionEvent) {
            for (row in unhealthyRows(model.getItems())) {
                if (row.descriptor != null) {
                    ExecutionUtil.restart(row.descriptor)
                }
            }
        }
    }

    /** Lets the user choose which columns are visible through a checkbox popup. */
    private inner class ShowColumnsAction : DumbAwareAction("Show Columns", "Choose which columns are visible", LanesIcons.ColumnChooser) {

        override fun actionPerformed(e: AnActionEvent) {
            val list = CheckBoxList<String>()
            for (name in allColumnNames()) {
                if (MANDATORY_COLUMN == name) {
                    continue // the Name column is always shown
                }
                list.addItem(name, name, !hiddenColumns.contains(name))
            }
            list.setCheckBoxListListener { index, value ->
                val name = list.getItemAt(index)
                if (name != null) {
                    if (value) {
                        hiddenColumns.remove(name)
                    } else {
                        hiddenColumns.add(name)
                    }
                    saveHiddenColumns(project, hiddenColumns)
                    applyColumnVisibility()
                }
            }
            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(ScrollPaneFactory.createScrollPane(list), list)
                .setTitle("Show Columns")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
            val source = e.getInputEvent()?.getComponent() ?: table
            popup.showUnderneathOf(source)
        }
    }

    /** Opens the memory chart/analysis of the selected application straight on the Analysis tab. */
    private inner class MemoryAnalysisAction : DumbAwareAction(
        "Memory Analysis", "Open the memory chart and leak analysis of the selected application",
        LanesIcons.MemoryLeak) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.getPresentation().setEnabled(selectedRow() != null)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val row = selectedRow()
            if (row != null) {
                openMemChart(row, true) // open on the Analysis tab
            }
        }
    }

    /**
     * Number of running processes that belong to Lanes - counted from the monitor rows, so an
     * app restarted individually from here (which the platform relaunches as a standalone run, no
     * longer in the plugin's own tracking map) still counts. A row belongs to Lanes when it
     * carries group metadata (meta != null); plain standalone runs never started by the plugin don't.
     */
    private fun runningLanesCount(): Int {
        var count = 0
        for (row in model.getItems()) {
            if (row.meta != null && row.handler != null && !row.handler.isProcessTerminated()) {
                count++
            }
        }
        return count
    }

    /** Number of running applications currently listed - grouped or standalone, every live row. */
    private fun runningAppCount(): Int {
        var count = 0
        for (row in model.getItems()) {
            if (row.handler != null && !row.handler.isProcessTerminated()) {
                count++
            }
        }
        return count
    }

    /**
     * Restarts every running application at once. Like the Stop-all button, it shows the
     * **count of running applications** as a badge next to a restart icon, so a single click
     * relaunches the whole set instead of restarting apps one by one; disabled when nothing runs.
     */
    private inner class RestartAllWithCountAction : DumbAwareAction("Restart All", "Restart every running application", LanesIcons.RestartAll), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val count = runningAppCount()
            val p = e.getPresentation()
            p.setText(if (count > 0) count.toString() else "")
            p.setEnabled(count > 0)
            p.setDescription(if (count == 1) "Restart the 1 running application"
                             else "Restart all $count running applications")
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            // an icon+text toolbar button, so the running-app count is visible as a badge
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun actionPerformed(e: AnActionEvent) {
            for (row in model.getItems()) {
                // re-run the same environment of every live application; each one reappears on the
                // next refresh (all IDE processes are listed)
                if (row.descriptor != null && row.handler != null && !row.handler.isProcessTerminated()) {
                    ExecutionUtil.restart(row.descriptor)
                }
            }
        }
    }

    /**
     * Stops every process Lanes started. Unlike the per-row Stop, this button shows the
     * **count of running processes** next to a stop icon (WebStorm-style), so it is not confused
     * with the per-row stop; it is disabled when nothing the plugin started is running.
     */
    private inner class StopAllWithCountAction : DumbAwareAction("Stop Lanes", "Stop every process started by Lanes", LanesIcons.StopAll), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val count = runningLanesCount()
            val p = e.getPresentation()
            p.setText(if (count > 0) count.toString() else "")
            p.setEnabled(count > 0)
            p.setDescription(if (count == 1) "Stop the 1 running Lanes process"
                             else "Stop the $count running Lanes processes")
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            // an icon+text toolbar button, so the running-process count is actually visible
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun actionPerformed(e: AnActionEvent) {
            // raise the stop flag and stop everything still tracked by the plugin...
            val action = ActionManager.getInstance().getAction(StopRunningLanesConfigurationsAction.ACTION_ID)
            if (action is StopRunningLanesConfigurationsAction) {
                action.stopAll(project)
            }
            // ...plus every Lanes row that is no longer tracked (e.g. restarted individually,
            // now a standalone run) - so the count and the button stay consistent.
            for (row in model.getItems()) {
                if (row.meta != null && row.handler != null && !row.handler.isProcessTerminated()) {
                    row.handler.destroyProcess()
                }
            }
            refresh()
        }
    }

    /**
     * Switches the environment of every running Lanes group that offers more than one env
     * profile, and restarts their apps with it - the batch counterpart of the per-row Env dropdown,
     * so the whole set moves to a chosen .env from a single modal instead of app by app. Shows the
     * running-app count as a badge; enabled only when at least one running group has a choice.
     */
    private inner class BatchEnvSwitchAction : DumbAwareAction(
        "Switch Environment", "Switch the environment of all running apps and restart them",
        LanesIcons.EnvVariables), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val count = runningAppCount()
            val hasChoices = switchableEnvProfiles(model.getItems()).isNotEmpty()
            val p = e.getPresentation()
            p.setText(if (count > 0) count.toString() else "")
            p.setEnabled(count > 0 && hasChoices)
            p.setDescription(if (hasChoices) "Switch the environment of all running apps and restart them"
                             else "Add more than one environment profile to a group to switch between them here")
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            // an icon+text toolbar button, so the running-app count shows as a badge like Stop All
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val profiles = switchableEnvProfiles(model.getItems())
            if (profiles.isEmpty()) {
                return
            }
            val chosen = chooseEnvProfile(profiles, activeGroupEnvPaths()) ?: return
            // group-wide switch: set the active env file of each affected group, then relaunch every
            // running app individually - each keeps its own executor (Run/Debug/...)
            for (groupName in groupsWithProfile(model.getItems(), chosen)) {
                val group = findGroupConfig(groupName) ?: continue
                val rows = runningRowsOfGroup(groupName)
                // a group-wide switch is authoritative: drop any per-app override on the running apps
                // so they all follow the new group environment (and the Env column shows it uniformly)
                val appEnvFiles = LinkedHashMap(group.getAppEnvFiles())
                for (row in rows) {
                    appEnvFiles.remove(row.name)
                }
                group.setAppEnvFiles(appEnvFiles)
                group.setEnvFilePath(chosen)
                for (row in rows) {
                    relaunchApp(group, row.name, executorOf(row), row.handler)
                }
            }
        }
    }

    /** The env file currently active on each switchable group (to mark/pre-select it in the modal). */
    private fun activeGroupEnvPaths(): Set<String> {
        val groupNames = LinkedHashSet<String>()
        for (row in model.getItems()) {
            if (row.envProfiles.size > 1 && row.lanesName != null && "-" != row.lanesName) {
                groupNames.add(row.lanesName)
            }
        }
        val paths = LinkedHashSet<String>()
        for (groupName in groupNames) {
            val group = findGroupConfig(groupName)
            if (group != null && group.getEnvFilePath().isNotEmpty()) {
                paths.add(group.getEnvFilePath())
            }
        }
        return paths
    }

    /** Kills whatever is listening on a TCP port - started by the IDE or not (the EADDRINUSE classic). */
    private inner class KillByPortAction : DumbAwareAction(
        "Kill Process on Port...", "Find the process listening on a TCP port and kill it", AllIcons.General.Web) {

        override fun actionPerformed(e: AnActionEvent) {
            val input = Messages.showInputDialog(project, "TCP port:", "Kill Process on Port", Messages.getQuestionIcon())
            if (input.isNullOrBlank()) {
                return
            }
            val port: Int
            try {
                port = input.trim().toInt()
            } catch (ex: NumberFormatException) {
                Messages.showErrorDialog(project, "'$input' is not a valid port number.", "Kill Process on Port")
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val pids = ProcessStatsSampler.pidsListeningOnPort(port)
                ApplicationManager.getApplication().invokeLater { confirmAndKill(port, pids) }
            }
        }

        private fun confirmAndKill(port: Int, pids: List<Long>) {
            if (pids.isEmpty()) {
                Messages.showInfoMessage(project,
                    "No process is listening on port $port (or lsof is not available).", "Kill Process on Port")
                return
            }
            val processList = pids.joinToString("\n") { pid ->
                "  PID $pid - " + ProcessHandle.of(pid).flatMap { it.info().command() }.orElse("unknown command")
            }
            val answer = Messages.showYesNoDialog(
                project,
                "Kill the process(es) listening on port $port?\n\n$processList",
                "Kill Process on Port", "Kill", "Cancel", Messages.getWarningIcon())
            if (answer != Messages.YES) {
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                for (pid in pids) {
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                }
                ApplicationManager.getApplication().invokeLater { refresh() }
            }
        }
    }

    private fun refresh() {
        if (!sampling.compareAndSet(false, true)) {
            return
        }
        // descriptors must be collected on the EDT; heavy sampling then runs pooled.
        // terminated processes are kept as history rows - the IDE keeps their descriptor until the
        // run tab is closed, which is exactly the lifetime the history should have
        val snapshots = ArrayList<ProcessSnapshot>()
        val knownHandlers = HashSet<ProcessHandler>()
        for (descriptor in RunContentManager.getInstance(project).getAllDescriptors()) {
            val handler = descriptor.getProcessHandler() ?: continue
            knownHandlers.add(handler)
            if (removedHandlers.contains(handler)) {
                continue
            }
            snapshots.add(ProcessSnapshot(descriptor.getDisplayName(), descriptor.getIcon(),
                                          handler, descriptor, !handler.isProcessTerminated()))
        }
        // a removed app only needs to stay suppressed while its run tab still exists
        removedHandlers.retainAll(knownHandlers)
        val entries = LanesProcessRegistry.getEntries(project)
        // the env profiles configured on each Lanes group - read on the EDT (RunManager),
        // so the Env column can offer them as a dropdown and the batch env switch can list them
        val envProfilesByGroup = HashMap<String, List<String>>()
        for (cfg in RunManager.getInstance(project).allConfigurationsList) {
            if (cfg is LanesRunConfiguration) {
                envProfilesByGroup[cfg.getName()] = ArrayList(cfg.getEnvProfiles())
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val rows = buildRows(snapshots, entries, envProfilesByGroup)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed()) {
                        setItemsKeepingSelection(rows)
                    }
                }
            } finally {
                sampling.set(false)
            }
        }
    }

    /**
     * Replaces the table content without losing the user's selection: rows are fresh objects on
     * every refresh, so the selected applications are matched back by their process handlers. The
     * whole multi-selection is preserved (not just a single row), so selecting several apps and
     * then running a batch action still targets all of them even across a refresh.
     */
    private fun setItemsKeepingSelection(rows: List<Row>) {
        val previouslySelected = table.getSelectedObjects()
        model.setItems(rows)
        cardLayout.show(contentCards, if (rows.isEmpty()) CARD_EMPTY else CARD_TABLE)
        if (previouslySelected.isEmpty()) {
            return
        }
        val selectedHandlers = HashSet<ProcessHandler?>()
        for (row in previouslySelected) {
            selectedHandlers.add(row.handler)
        }
        val rowHandlers = ArrayList<ProcessHandler?>(rows.size)
        for (row in rows) {
            rowHandlers.add(row.handler)
        }
        val selectionModel = table.getSelectionModel()
        selectionModel.setValueIsAdjusting(true)
        selectionModel.clearSelection()
        for (modelIndex in selectionIndices(rowHandlers, selectedHandlers)) {
            val viewIndex = table.convertRowIndexToView(modelIndex)
            selectionModel.addSelectionInterval(viewIndex, viewIndex)
        }
        selectionModel.setValueIsAdjusting(false)
    }

    /**
     * Builds the display rows; runs on a pooled thread (process tree walk + one ps and one
     * lsof call). Also keeps the previous CPU-time sample, so CPU % is the instantaneous
     * docker-stats-style delta between two refreshes - not the lifetime average.
     */
    private fun buildRows(
        snapshots: List<ProcessSnapshot>, entries: List<LanesProcessRegistry.Entry>,
        envProfilesByGroup: Map<String, List<String>>,
    ): List<Row> {
        val hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb()

        val liveByHandler = HashMap<ProcessHandler, LanesProcessRegistry.Entry>()
        for (entry in entries) {
            liveByHandler[entry.handler] = entry
        }

        // resolve the process tree of every row first, then sample everything in single ps/lsof calls.
        // paused apps are skipped entirely here - the whole point is to stop spending the tree walk /
        // ps / lsof on them; their row is rebuilt from the last known values below.
        // stopped apps are history rows and paused apps were opted out: neither is sampled
        val sampled = snapshots.filter { it.running && !pausedHandlers.contains(it.handler) }
        val rootPidBySnapshot = LinkedHashMap<ProcessSnapshot, Long>()
        for (snapshot in sampled) {
            rootPidBySnapshot[snapshot] = LanesProcessRegistry.pidOf(snapshot.handler)
        }
        // one process-table scan resolves every tree at once (see processTreePidsFor)
        val treeByRootPid = ProcessStatsSampler.processTreePidsFor(rootPidBySnapshot.values)
        val treeBySnapshot = LinkedHashMap<ProcessSnapshot, Set<Long>>()
        val allPids = LinkedHashSet<Long>()
        for ((snapshot, rootPid) in rootPidBySnapshot) {
            val treePids = treeByRootPid[rootPid] ?: continue
            treeBySnapshot[snapshot] = treePids
            allPids.addAll(treePids)
        }
        val statsByPid = ProcessStatsSampler.samplePids(allPids)
        val portsByPid = ProcessStatsSampler.sampleListeningPorts(allPids)

        val nowNanos = System.nanoTime()
        val elapsedSeconds = if (prevSampleNanos == 0L) -1.0 else (nowNanos - prevSampleNanos) / 1_000_000_000.0
        val prevCpu = prevCpuSecondsByPid

        val rows = ArrayList<Row>(snapshots.size)
        for (snapshot in snapshots) {
            if (!snapshot.running) {
                // history row: the app already exited, so there is nothing left to sample
                rows.add(stoppedRow(snapshot))
                continue
            }
            if (pausedHandlers.contains(snapshot.handler)) {
                // frozen row: keep the last known values, greyed out, no fresh sampling
                rows.add(pausedRow(snapshot))
                continue
            }
            val treePids = treeBySnapshot[snapshot] ?: emptySet()
            val rootPid = if (treePids.isEmpty()) -1L else treePids.iterator().next()
            val stats = ProcessStatsSampler.aggregate(statsByPid, treePids)

            // grouped app? live registry entry (matched by this exact process handler) first.
            // A Node app launched with the IDE's own Play/Debug is tracked in StandaloneEnvRegistry
            // by its real handler - when present it's a genuine standalone run, so we must NOT guess
            // a Lanes group by name: an app named like a Lanes child (e.g. a Mocha "eparts-api" that
            // also exists inside a "CORE" group) would otherwise be mislabeled with that group and
            // its env. Only when neither handler-based source knows the process do we fall back to
            // the last-known Lanes metadata by name (keeps a truly individually-restarted app grouped).
            val live = liveByHandler[snapshot.handler]
            val standalone = StandaloneEnvRegistry.find(snapshot.handler)
            val meta = live ?: if (standalone != null) null
                               else LanesProcessRegistry.findMetadataByName(project, snapshot.name)

            val name = live?.appName ?: snapshot.name
            // the running indicator is the same green dot the platform puts on the Run button
            val icon: Icon = runningIcon(if (live != null) configuredAppIcon
                                         else snapshot.icon ?: AllIcons.RunConfigurations.Application)
            val lanesName = meta?.lanesName ?: "-"
            // grouped app: its Lanes env; standalone app: the env the plugin loaded into it
            // (active .env file name, or "-" when it runs with only its own variables)
            val envFileName = meta?.envFileName ?: standalone?.envFileName ?: "-"
            val memoryLimitMb = meta?.memoryLimitMb

            val treePorts = TreeSet<Int>()
            for (pid in treePids) {
                val ports = portsByPid[pid]
                if (ports != null) {
                    treePorts.addAll(ports)
                }
            }
            val portsText = if (treePorts.isEmpty()) "-" else treePorts.joinToString(", ")

            val startedAtMs = live?.startedAtMs ?: ProcessStatsSampler.processStartMillis(rootPid)
            val uptimeText = if (startedAtMs > 0) ProcessStatsSampler.formatUptime(System.currentTimeMillis() - startedAtMs) else "n/a"
            val statusText = healthStatus(meta)

            val limitText = if (memoryLimitMb != null) ProcessStatsSampler.formatMemory(memoryLimitMb * 1024L)
                            else ProcessStatsSampler.formatMemory(hostTotalKb)
            val memUsage: String
            val memPercent: String
            val cpuPercent: String
            var percentValue = -1.0
            if (stats != null) {
                memUsage = "${ProcessStatsSampler.formatMemory(stats.rssKb)} / $limitText"
                percentValue = ProcessStatsSampler.memoryPercent(stats.rssKb, memoryLimitMb, hostTotalKb)
                memPercent = if (percentValue < 0) "n/a" else String.format(Locale.US, "%.2f%%", percentValue)
                val deltaCpuSeconds = ProcessStatsSampler.cpuDeltaSeconds(statsByPid, prevCpu, treePids)
                cpuPercent = if (deltaCpuSeconds >= 0 && elapsedSeconds > 0)
                    String.format(Locale.US, "%.2f%%", deltaCpuSeconds / elapsedSeconds * 100)
                else "n/a"
            } else {
                memUsage = "n/a / $limitText"
                memPercent = "n/a"
                cpuPercent = "n/a"
            }

            // sparkline history (memory percent over the last ~minute)
            val history = memHistory.getOrPut(snapshot.handler) { ArrayDeque() }
            if (percentValue >= 0) {
                history.addLast(percentValue)
                while (history.size > TREND_SAMPLES) {
                    history.removeFirst()
                }
            }
            val memTrend = history.toDoubleArray()

            // full session history (bounded) feeds the click-to-open memory chart
            if (stats != null) {
                val full = fullHistory.getOrPut(snapshot.handler) { ArrayList() }
                synchronized(full) {
                    full.add(MemoryHistory.Sample(System.currentTimeMillis(), stats.rssKb, percentValue))
                    while (full.size > MAX_FULL_SAMPLES) {
                        full.removeAt(0)
                    }
                }
            }

            val envProfiles = if (meta != null) envProfilesByGroup[meta.lanesName] ?: emptyList() else emptyList()

            val row = Row(name, icon, lanesName, envFileName, snapshot.handler, snapshot.descriptor, meta,
                        if (rootPid > 0) rootPid.toString() else "n/a",
                        portsText, uptimeText, statusText, memUsage, memPercent, cpuPercent, memTrend,
                        envProfiles)
            lastRowByHandler[snapshot.handler] = row
            rows.add(row)
        }

        // baseline for the next CPU delta
        val newPrev = HashMap<Long, Double>()
        statsByPid.forEach { (pid, stats) -> newPrev[pid] = stats.cpuTimeSeconds }
        prevCpuSecondsByPid = newPrev
        prevSampleNanos = nowNanos

        // drop the state of processes that are gone
        val liveHandlers = HashSet<ProcessHandler>()
        val runningHandlers = HashSet<ProcessHandler>()
        for (snapshot in snapshots) {
            liveHandlers.add(snapshot.handler)
            if (snapshot.running) {
                runningHandlers.add(snapshot.handler)
            }
        }
        memHistory.keys.retainAll(liveHandlers)
        fullHistory.keys.retainAll(liveHandlers)
        lastRowByHandler.keys.retainAll(liveHandlers)
        // pausing only means something while the app runs: a stopped one must not come back paused,
        // and this is what keeps Resume Monitoring from lighting up on a history row
        pausedHandlers.retainAll(runningHandlers)

        // a live app takes over the port of the stopped one it replaced; running rows stay on top
        return runningFirst(dropSupersededByPort(rows))
    }

    /** The app's icon with the platform's green "live" dot, the same mark the Run button uses. */
    private fun runningIcon(icon: Icon): Icon = ExecutionUtil.getLiveIndicator(icon)

    /**
     * A history row for an application that already exited. Its last sampled values (PID, ports,
     * memory, CPU, trend) are kept exactly as they were the moment it stopped - that is the whole
     * point of the history: what this app was using before it went away. Only the Status column
     * changes, to report how it ended. The descriptor is kept so Restart reruns exactly this app.
     */
    private fun stoppedRow(snapshot: ProcessSnapshot): Row {
        val status = stoppedStatusText(snapshot.handler.getExitCode())
        val last = lastRowByHandler[snapshot.handler]
        if (last == null) {
            // it stopped before the monitor ever sampled it: nothing to freeze
            return Row(snapshot.name, plainIcon(snapshot), "-", "-", snapshot.handler, snapshot.descriptor,
                        null, "-", "-", "-", status, "-", "-", "-", DoubleArray(0), emptyList(), false, false)
        }
        return Row(last.name, plainIcon(snapshot), last.lanesName, last.envFileName,
                    snapshot.handler, snapshot.descriptor, last.meta,
                    last.pid, last.ports, last.uptime, status,
                    last.memUsage, last.memPercent, last.cpuPercent, last.memTrend,
                    last.envProfiles, false, false)
    }

    /** The app's plain icon, without the live dot: it is not running anymore. */
    private fun plainIcon(snapshot: ProcessSnapshot): Icon =
        snapshot.icon ?: AllIcons.RunConfigurations.Application

    /**
     * A frozen display row for a paused app: its last sampled values kept as-is (memory, CPU,
     * ports, uptime, status), only marked [Row.paused] so the table greys it out. Identity fields
     * (handler/descriptor) come from the fresh snapshot so restart/stop/resume still target it. When
     * paused before any sample exists, falls back to neutral placeholders.
     */
    private fun pausedRow(snapshot: ProcessSnapshot): Row {
        val last = lastRowByHandler[snapshot.handler]
        if (last == null) {
            // still running, just not sampled: it keeps the live indicator
            val icon = runningIcon(snapshot.icon ?: AllIcons.RunConfigurations.Application)
            return Row(snapshot.name, icon, "-", "-", snapshot.handler, snapshot.descriptor, null,
                        "n/a", "-", "n/a", "paused", "n/a", "n/a", "n/a", DoubleArray(0), emptyList(), true)
        }
        // the Status column says "paused" rather than repeating the frozen health state, so the
        // reason the numbers stopped moving is visible in the table itself
        return Row(last.name, last.icon, last.lanesName, last.envFileName, snapshot.handler, snapshot.descriptor,
                    last.meta, last.pid, last.ports, last.uptime, "paused", last.memUsage, last.memPercent,
                    last.cpuPercent, last.memTrend, last.envProfiles, true)
    }

    /**
     * Tiny polyline with the recent memory history of a row, docker-desktop style. The shape is
     * normalized to the min/max of the series (so trends are visible at any scale); the color
     * reflects the latest memory percent: green, orange from 70%, red from 90%.
     */

    override fun dispose() {
        timer.stop()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2000

        /** [contentCards] card names - swapped between the table and the empty-state illustration. */
        private const val CARD_TABLE = "table"
        private const val CARD_EMPTY = "empty"

        /** Initial column widths, by model index; also reapplied when columns are shown/hidden. */
        private val PREFERRED_WIDTHS = intArrayOf(220, 110, 90, 70, 100, 80, 80, 160, 70, 120, 70)

        /** The one column that can never be hidden (it identifies the row). */
        private const val MANDATORY_COLUMN = "Name"

        /** Recent memory-percent samples per process, feeding the "Mem trend" sparkline column. */
        private const val TREND_SAMPLES = 30

        /** Full session memory history per process, for the click-to-open chart (bounded). */
        private const val MAX_FULL_SAMPLES = 10_000

        private fun column(name: String, getter: (Row) -> String?): ColumnInfo<Row, String> {
            return object : ColumnInfo<Row, String>(name) {
                override fun valueOf(row: Row): String? = getter(row)
            }
        }

        /** The names that stay visible: every column in `all` not present in `hidden`. */
        @JvmStatic
        fun visibleColumns(all: List<String>, hidden: Set<String>): List<String> {
            val result = ArrayList<String>()
            for (name in all) {
                if (!hidden.contains(name)) {
                    result.add(name)
                }
            }
            return result
        }

        /** The rows whose readiness status is currently "down" - used by "Restart Unhealthy". */
        @JvmStatic
        fun unhealthyRows(rows: List<Row>): List<Row> {
            val result = ArrayList<Row>()
            for (row in rows) {
                if ("down" == row.status) {
                    result.add(row)
                }
            }
            return result
        }

        /** True when the row carries a Lanes environment that can be shown in the Env viewer. */
        private fun hasLoadedEnv(row: Row): Boolean {
            val meta = row.meta
            if (meta != null && meta.loadedEnv.isNotEmpty()) {
                return true
            }
            // standalone app (no group meta): env captured by the Node env-file module, if any
            val handler = row.handler ?: return false
            return StandaloneEnvRegistry.find(handler)?.loadedEnv?.isNotEmpty() == true
        }

        /** The distinct env profiles offered for switching: only groups with more than one qualify. */
        @JvmStatic
        fun switchableEnvProfiles(rows: List<Row>): List<String> {
            val profiles = LinkedHashSet<String>()
            for (row in rows) {
                if (row.envProfiles.size > 1) {
                    profiles.addAll(row.envProfiles)
                }
            }
            return ArrayList(profiles)
        }

        /**
         * Key under which the hidden-column choice is stored, per project.
         * Kept as a list, never a joined string: column headers contain spaces and slashes
         * ("Mem Usage / Limit"), so any separator would be a bug waiting to happen.
         */
        private const val HIDDEN_COLUMNS_KEY = "lanes.monitor.hiddenColumns"

        /** Restores the hidden-column choice of this project (empty when nothing was stored). */
        private fun loadHiddenColumns(project: Project): MutableSet<String> {
            val stored = PropertiesComponent.getInstance(project).getList(HIDDEN_COLUMNS_KEY)
            return if (stored == null) LinkedHashSet() else LinkedHashSet(stored)
        }

        /** Persists the hidden-column choice; an empty selection clears the stored value. */
        private fun saveHiddenColumns(project: Project, columns: Set<String>) {
            val properties = PropertiesComponent.getInstance(project)
            if (columns.isEmpty()) {
                properties.unsetValue(HIDDEN_COLUMNS_KEY)
            } else {
                properties.setList(HIDDEN_COLUMNS_KEY, ArrayList(columns))
            }
        }

        /**
         * Orders the table: running applications first (in their original order), then the history
         * of the ones that stopped. Stable, so a refresh never shuffles rows around.
         */
        @JvmStatic
        fun runningFirst(rows: List<Row>): List<Row> =
            rows.filter { it.running } + rows.filterNot { it.running }

        /**
         * Drops the history rows whose ports a running application has taken over. Restarting an
         * app - or starting another one on the same port - makes the old stopped row stale and
         * confusing (two rows claiming port 3003), so the live one replaces it.
         */
        @JvmStatic
        fun dropSupersededByPort(rows: List<Row>): List<Row> {
            val livePorts = HashSet<Int>()
            for (row in rows) {
                if (row.running) {
                    livePorts.addAll(parsePorts(row.ports))
                }
            }
            if (livePorts.isEmpty()) {
                return rows
            }
            return rows.filter { row -> row.running || parsePorts(row.ports).none { livePorts.contains(it) } }
        }

        /**
         * Exit codes that mean "someone stopped this app", not "this app failed": the shell
         * convention 128+signal for SIGINT/SIGTERM/SIGKILL (which is how Stop and Force Kill end a
         * process), plus the bare signal numbers some launchers report instead.
         */
        private val STOP_EXIT_CODES = setOf(130, 143, 137, 2, 15, 9)

        /**
         * Status text of an application that is no longer running. Stopping or killing it reads as
         * `stopped` - the exit code there is just how the signal surfaced and says nothing useful.
         * A genuine failure keeps its code (`exited (1)`), because that is worth seeing.
         */
        @JvmStatic
        fun stoppedStatusText(exitCode: Int?): String =
            if (exitCode == null || STOP_EXIT_CODES.contains(exitCode)) "stopped" else "exited ($exitCode)"

        /** Group names (from the rows) whose configured profiles include the chosen one - the batch targets. */
        @JvmStatic
        fun groupsWithProfile(rows: List<Row>, profile: String): Set<String> {
            val groups = LinkedHashSet<String>()
            for (row in rows) {
                if (row.lanesName != null && "-" != row.lanesName
                    && row.envProfiles.size > 1 && row.envProfiles.contains(profile)) {
                    groups.add(row.lanesName)
                }
            }
            return groups
        }

        /**
         * Indices of the rows whose key was selected before a refresh, used to restore a multi-selection
         * after the row objects are rebuilt. Kept pure (keyed on any identity) so it is unit-testable
         * without a live table.
         */
        @JvmStatic
        fun <T> selectionIndices(rowKeys: List<T>, selectedKeys: Set<T>): List<Int> {
            val indices = ArrayList<Int>()
            for (i in rowKeys.indices) {
                if (selectedKeys.contains(rowKeys[i])) {
                    indices.add(i)
                }
            }
            return indices
        }

        /** Parses the comma-separated Ports cell text ("3000, 8080") into a list of port numbers. */
        @JvmStatic
        fun parsePorts(portsText: String?): List<Int> {
            val ports = ArrayList<Int>()
            if (portsText == null) {
                return ports
            }
            for (part in portsText.split(",")) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) {
                    continue
                }
                val parsed = trimmed.toIntOrNull()
                if (parsed != null) {
                    ports.add(parsed)
                }
            }
            return ports
        }

        /** The URL opened when a port is clicked. */
        @JvmStatic
        fun urlForPort(port: Int): String = "http://localhost:$port"

        /**
         * Health of the app according to its "Ready when" condition (docker-compose style): every row
         * in the monitor is a live process, so the baseline is "running"; a port/http condition that is
         * re-checked on every refresh refines that into "healthy" or "down". Log conditions cannot be
         * re-evaluated after startup, so those apps simply stay "running".
         */
        private fun healthStatus(meta: LanesProcessRegistry.Entry?): String {
            if (meta == null) {
                return statusLabel(RunConfigurationHelper.ReadyCondition.Type.NONE, false)
            }
            val condition = RunConfigurationHelper.parseReadyCondition(meta.readyCondition)
            return when (condition.type) {
                RunConfigurationHelper.ReadyCondition.Type.PORT ->
                    statusLabel(condition.type, RunConfigurationHelper.isPortOpen(condition.port))
                RunConfigurationHelper.ReadyCondition.Type.HTTP ->
                    statusLabel(condition.type, RunConfigurationHelper.isHttpHealthy(condition.value))
                else -> statusLabel(condition.type, false)
            }
        }

        /**
         * The Status label for a live process: a PORT/HTTP readiness check maps to "healthy"/"down"
         * depending on `checkPassed`; every other case (no condition, or a log condition) is a
         * plain "running", since the row only exists while the process is alive.
         */
        @JvmStatic
        fun statusLabel(type: RunConfigurationHelper.ReadyCondition.Type, checkPassed: Boolean): String {
            return when (type) {
                RunConfigurationHelper.ReadyCondition.Type.PORT, RunConfigurationHelper.ReadyCondition.Type.HTTP ->
                    if (checkPassed) "healthy" else "down"
                else -> "running"
            }
        }
    }
}
