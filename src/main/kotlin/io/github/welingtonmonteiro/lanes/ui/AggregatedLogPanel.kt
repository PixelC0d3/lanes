package io.github.welingtonmonteiro.lanes.ui

import java.awt.BorderLayout
import java.awt.Font
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField

import io.github.welingtonmonteiro.lanes.LogFilter

/**
 * The "Logs" tab of the Lanes Monitor tool window: a single, aggregated view of the console
 * output of every process the IDE is running - like `docker compose logs -f`. Each line is prefixed
 * with `[app]` in a per-application color, and a filter field narrows the view to lines containing
 * a substring.
 *
 * Output arrives on process threads, is buffered in a concurrent queue and flushed to the text
 * pane on the EDT by a Swing timer, which also attaches the listener to processes started after the
 * panel was opened.
 */
class AggregatedLogPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {

    /** One captured console line together with the application it came from. */
    class LogLine internal constructor(
        @JvmField val app: String,
        @JvmField val colorIndex: Int,
        /** Original text, may contain ANSI escape codes (used when ANSI rendering is on). */
        @JvmField val raw: String,
    ) {
        /** ANSI stripped, for filtering and the default (clean) rendering. */
        @JvmField val visible: String = stripAnsi(raw)
    }

    /** One run of text with a resolved ANSI style (foreground index, bold). */
    class AnsiSpan internal constructor(
        @JvmField val text: String,
        /** -1 = default foreground */
        @JvmField val fgIndex: Int,
        @JvmField val bold: Boolean,
    )

    private val textPane = JTextPane()
    private val filterField = JBTextField(22)
    private val appCombo = JComboBox<String>()
    private val modeCombo = JComboBox(arrayOf("Match any", "Match all"))
    private val actionCombo = JComboBox(arrayOf("Show matching", "Hide matching"))
    private val levelCombo = JComboBox(arrayOf("All levels", "Info+", "Warn+", "Errors"))
    private val caseBox = JBCheckBox("Aa")
    private val regexBox = JBCheckBox(".*")
    private val ansiBox = JBCheckBox("ANSI")
    private val timer: Timer

    /** All captured lines (bounded by [MAX_LINES]); the source of truth for re-rendering. */
    private val allLines: MutableList<LogLine> = ArrayList()

    /** Lines produced off the EDT, drained on the timer tick. */
    private val pending = ConcurrentLinkedQueue<Array<String>>()

    /** Handlers we already attached a listener to, so we never double-count their output. */
    private val listened: MutableSet<ProcessHandler> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Stable color index per application name, assigned on first appearance. */
    private val appColorIndex: MutableMap<String, Int> = LinkedHashMap()

    /** Every application name seen so far (for the app combo), in appearance order. */
    private val knownApps: MutableSet<String> = LinkedHashSet()

    @Volatile
    private var logFilter: LogFilter = LogFilter.all()
    private var scrollToEnd = true

    /** When true the log renders real ANSI colors; when false the escape codes are stripped. */
    private var ansiColors = false

    /** Guards combo repopulation so programmatic changes don't trigger a filter rebuild. */
    private var updatingCombo = false

    init {
        textPane.setEditable(false)
        textPane.setFont(Font(Font.MONOSPACED, Font.PLAIN, textPane.getFont().getSize()))

        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Clear", "Clear the aggregated log", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                allLines.clear()
                rebuild()
            }
        })
        group.add(object : DumbAwareToggleAction("Scroll to End", "Follow the output as it arrives",
                                                 AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent): Boolean = scrollToEnd

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                scrollToEnd = state
                Toggleable.setSelected(e.getPresentation(), state)
                if (state) {
                    scrollToBottom()
                }
            }
        })
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar("LanesAggregatedLogs", group, true)
        toolbar.setTargetComponent(textPane)

        // header (filters) on top, the log below filling the whole width. Building the layout
        // explicitly (instead of setToolbar) keeps the header a full-width top strip rather than a
        // left-hand column - the wide filter row does not belong on the side.
        val main = JPanel(BorderLayout())
        main.add(buildHeader(toolbar), BorderLayout.NORTH)
        main.add(ScrollPaneFactory.createScrollPane(textPane), BorderLayout.CENTER)
        setContent(main)

        timer = Timer(FLUSH_INTERVAL_MS) {
            syncListeners()
            drainPending()
        }
        timer.start()
        syncListeners()
    }

    /** Builds the header strip: the action toolbar plus the app selector and advanced filter controls. */
    private fun buildHeader(toolbar: ActionToolbar): JComponent {
        appCombo.addItem(ALL_APPS)
        appCombo.setToolTipText("Show logs of a single application")
        caseBox.setToolTipText("Case sensitive")
        regexBox.setToolTipText("Interpret terms as regular expressions")
        ansiBox.setToolTipText("Render ANSI colors (off = strip the color/escape codes for a clean log)")
        modeCombo.setToolTipText("Combine several comma-separated terms with AND (all) or OR (any)")
        actionCombo.setToolTipText("Show only matching lines, or hide matching lines")
        levelCombo.setToolTipText("Only show lines at or above a log level (detected from the text)")
        filterField.getEmptyText().setText("Filter (comma-separated terms)")

        filterField.getDocument().addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildFilter()
            override fun removeUpdate(e: DocumentEvent) = rebuildFilter()
            override fun changedUpdate(e: DocumentEvent) = rebuildFilter()
        })
        val rebuildListener = ActionListener { rebuildFilter() }
        appCombo.addActionListener(rebuildListener)
        modeCombo.addActionListener(rebuildListener)
        actionCombo.addActionListener(rebuildListener)
        levelCombo.addActionListener(rebuildListener)
        caseBox.addActionListener(rebuildListener)
        regexBox.addActionListener(rebuildListener)
        // ANSI toggle is display-only (does not affect the filter), so re-render directly
        ansiBox.addActionListener {
            ansiColors = ansiBox.isSelected()
            rebuild()
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        controls.add(JBLabel("App:"))
        controls.add(appCombo)
        controls.add(JBLabel("Filter:"))
        controls.add(filterField)
        controls.add(modeCombo)
        controls.add(actionCombo)
        controls.add(caseBox)
        controls.add(regexBox)
        controls.add(JBLabel("Level:"))
        controls.add(levelCombo)
        controls.add(ansiBox)

        val header = JPanel(BorderLayout())
        header.add(toolbar.getComponent(), BorderLayout.WEST)
        header.add(controls, BorderLayout.CENTER)
        return header
    }

    /** Rebuilds [logFilter] from the current controls and re-renders. */
    private fun rebuildFilter() {
        if (updatingCombo) {
            return
        }
        val selectedApp = appCombo.getSelectedItem()
        val app = if (selectedApp == null || ALL_APPS == selectedApp) null else selectedApp.toString()
        val mode = if (modeCombo.getSelectedIndex() == 1) LogFilter.Mode.ALL else LogFilter.Mode.ANY
        val exclude = actionCombo.getSelectedIndex() == 1
        logFilter = LogFilter.from(app, filterField.getText(), mode, exclude,
                                   caseBox.isSelected(), regexBox.isSelected(),
                                   levelForIndex(levelCombo.getSelectedIndex()))
        rebuild()
    }

    /** Adds any newly seen application to the combo, preserving the current selection. */
    private fun refreshAppCombo() {
        val selected = appCombo.getSelectedItem()
        updatingCombo = true
        try {
            appCombo.removeAllItems()
            appCombo.addItem(ALL_APPS)
            for (app in knownApps) {
                appCombo.addItem(app)
            }
            appCombo.setSelectedItem(selected ?: ALL_APPS)
            if (appCombo.getSelectedItem() == null) {
                appCombo.setSelectedItem(ALL_APPS)
            }
        } finally {
            updatingCombo = false
        }
    }

    /** Attaches the capturing listener to every running process not yet followed. */
    private fun syncListeners() {
        var appsChanged = false
        for (descriptor in RunContentManager.getInstance(project).getAllDescriptors()) {
            val handler = descriptor.getProcessHandler()
            val app = descriptor.getDisplayName()
            if (knownApps.add(app)) {
                appsChanged = true // a new application appeared: offer it in the app combo
            }
            if (handler == null || handler.isProcessTerminated() || !listened.add(handler)) {
                continue
            }
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.getText()
                    if (text != null && text.isNotEmpty()) {
                        pending.add(arrayOf(app, text))
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    listened.remove(handler)
                }
            })
        }
        if (appsChanged) {
            refreshAppCombo()
        }
    }

    /** Moves buffered output into the model and the text pane; runs on the EDT (timer thread). */
    private fun drainPending() {
        var added = false
        var chunk = pending.poll()
        while (chunk != null) {
            val app = chunk[0]
            val colorIndex = colorIndexFor(app, appColorIndex, PALETTE.size)
            for (line in splitKeepingLines(chunk[1])) {
                val logLine = LogLine(app, colorIndex, line)
                allLines.add(logLine)
                if (logFilter.accepts(app, logLine.visible)) {
                    appendToPane(logLine)
                    added = true
                }
            }
            chunk = pending.poll()
        }
        if (allLines.size > MAX_LINES) {
            // trim oldest and re-render once; keeps memory bounded for long sessions
            allLines.subList(0, allLines.size - MAX_LINES).clear()
            rebuild()
        } else if (added && scrollToEnd) {
            scrollToBottom()
        }
    }

    /** Rebuilds the whole text pane from [allLines], honoring the current filter. */
    private fun rebuild() {
        textPane.setText("")
        for (line in allLines) {
            if (logFilter.accepts(line.app, line.visible)) {
                appendToPane(line)
            }
        }
        if (scrollToEnd) {
            scrollToBottom()
        }
    }

    private fun appendToPane(line: LogLine) {
        val doc = textPane.getStyledDocument()
        val appAttrs = SimpleAttributeSet()
        StyleConstants.setForeground(appAttrs, PALETTE[line.colorIndex])
        try {
            if (ansiColors) {
                // render real ANSI colors: keep the [app] prefix in the per-app color, then color
                // the body from its escape codes (default foreground where no color is set)
                doc.insertString(doc.getLength(), "[${line.app}] ", appAttrs)
                for (span in parseAnsi(line.raw)) {
                    val a = SimpleAttributeSet()
                    if (span.fgIndex >= 0 && span.fgIndex < ANSI_PALETTE.size) {
                        StyleConstants.setForeground(a, ANSI_PALETTE[span.fgIndex])
                    }
                    if (span.bold) {
                        StyleConstants.setBold(a, true)
                    }
                    doc.insertString(doc.getLength(), span.text, a)
                }
                doc.insertString(doc.getLength(), "\n", appAttrs)
            } else {
                // clean rendering: strip the escape codes, whole line in the per-app color
                doc.insertString(doc.getLength(), formatLine(line.app, line.visible) + "\n", appAttrs)
            }
        } catch (ignored: BadLocationException) {
            // the document length is always valid here; nothing to recover
        }
    }

    private fun scrollToBottom() {
        textPane.setCaretPosition(textPane.getDocument().getLength())
    }

    override fun dispose() {
        timer.stop()
        pending.clear()
        listened.clear()
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 1000

        /** Hard cap on retained lines, so a chatty app cannot grow the buffer without bound. */
        private const val MAX_LINES = 5000

        /** Distinct colors cycled through as new applications appear (docker-compose style). */
        private val PALETTE = arrayOf(
            JBColor(0x1F6FEB, 0x58A6FF), // blue
            JBColor(0x2DA44E, 0x3FB950), // green
            JBColor(0xBC4C00, 0xDB6D28), // orange
            JBColor(0x8250DF, 0xA371F7), // purple
            JBColor(0xCF222E, 0xF85149), // red
            JBColor(0x9A6700, 0xD29922), // gold
            JBColor(0x1B7C83, 0x39C5CF), // teal
            JBColor(0xBF3989, 0xDB61A2), // magenta
        )

        /** ANSI 16-color palette (standard 0-7 then bright 8-15), theme-aware for light/dark. */
        private val ANSI_PALETTE = arrayOf(
            JBColor(0x000000, 0xBBBBBB), // black (light gray on dark, so it stays visible)
            JBColor(0xCC0000, 0xFF5555), // red
            JBColor(0x00A000, 0x50FA7B), // green
            JBColor(0x998A00, 0xF1FA8C), // yellow
            JBColor(0x0000CC, 0x6699FF), // blue
            JBColor(0xA000A0, 0xFF79C6), // magenta
            JBColor(0x008B8B, 0x8BE9FD), // cyan
            JBColor(0x808080, 0xBFBFBF), // white / light gray
            JBColor(0x555555, 0x888888), // bright black
            JBColor(0xE00000, 0xFF6E6E), // bright red
            JBColor(0x00A800, 0x69FF94), // bright green
            JBColor(0xB0A000, 0xFFFFA5), // bright yellow
            JBColor(0x3333FF, 0x8AB4FF), // bright blue
            JBColor(0xD000D0, 0xFF92DF), // bright magenta
            JBColor(0x00A8A8, 0xA4FFFF), // bright cyan
            JBColor(0x606060, 0xFFFFFF), // bright white
        )

        /** A CSI escape sequence (colors, cursor moves, line erases): ESC [ params interm final. */
        private val ANSI_CSI: Pattern = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]")

        /** First item of the application combo: no per-app restriction. */
        private const val ALL_APPS = "All apps"

        private fun levelForIndex(index: Int): LogFilter.Level? {
            return when (index) {
                1 -> LogFilter.Level.INFO
                2 -> LogFilter.Level.WARN
                3 -> LogFilter.Level.ERROR
                else -> null // "All levels"
            }
        }

        /** Prefixes a single console line with its application tag: `[app] text`. */
        @JvmStatic
        fun formatLine(app: String, text: String): String = "[$app] $text"

        /** True when the line should be shown for the given filter (empty filter shows everything). */
        @JvmStatic
        fun matchesFilter(line: String, filter: String?): Boolean {
            if (filter.isNullOrBlank()) {
                return true
            }
            return line.lowercase(Locale.ROOT).contains(filter.trim().lowercase(Locale.ROOT))
        }

        /** Removes every ANSI/CSI escape sequence (colors, cursor moves, line erases) from a line. */
        @JvmStatic
        fun stripAnsi(text: String?): String {
            if (text == null) {
                return ""
            }
            if (text.indexOf('\u001B') < 0) {
                return text // fast path: no escape char at all
            }
            return ANSI_CSI.matcher(text).replaceAll("")
        }

        /**
         * Splits a line into styled runs by interpreting its ANSI SGR (color/bold) escape codes. Only
         * SGR sequences (ending in `m`) change the style; other CSI sequences (cursor moves, line
         * erases) are dropped. The result concatenated back equals [stripAnsi].
         */
        @JvmStatic
        fun parseAnsi(text: String?): List<AnsiSpan> {
            val spans = ArrayList<AnsiSpan>()
            if (text == null) {
                return spans
            }
            val current = StringBuilder()
            var fg = -1
            var bold = false
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '\u001B' && i + 1 < n && text[i + 1] == '[') {
                    var j = i + 2
                    while (j < n && (text[j] < '@' || text[j] > '~')) {
                        j++
                    }
                    if (j < n) {
                        if (text[j] == 'm') { // SGR: flush the current run, then update the style
                            if (current.isNotEmpty()) {
                                spans.add(AnsiSpan(current.toString(), fg, bold))
                                current.setLength(0)
                            }
                            val applied = applySgr(text.substring(i + 2, j), fg, bold)
                            fg = applied[0]
                            bold = applied[1] == 1
                        }
                        i = j + 1 // skip the whole sequence (SGR applied, others ignored)
                        continue
                    }
                    i++ // malformed trailing escape: drop the ESC and continue
                    continue
                }
                current.append(c)
                i++
            }
            if (current.isNotEmpty()) {
                spans.add(AnsiSpan(current.toString(), fg, bold))
            }
            return spans
        }

        /** Applies an SGR parameter string (`"1;32"`) to the current (fg, bold); returns {fg, bold}. */
        @JvmStatic
        fun applySgr(params: String, fg: Int, bold: Boolean): IntArray {
            if (params.isEmpty()) {
                return intArrayOf(-1, 0) // ESC[m is a full reset
            }
            var resultFg = fg
            var resultBold = bold
            for (part in params.split(";")) {
                val code = try {
                    if (part.isEmpty()) 0 else part.toInt()
                } catch (e: NumberFormatException) {
                    continue
                }
                when {
                    code == 0 -> {
                        resultFg = -1
                        resultBold = false
                    }
                    code == 1 -> resultBold = true
                    code == 22 -> resultBold = false
                    code == 39 -> resultFg = -1
                    code in 30..37 -> resultFg = code - 30
                    code in 90..97 -> resultFg = code - 90 + 8
                }
            }
            return intArrayOf(resultFg, if (resultBold) 1 else 0)
        }

        /**
         * Returns a stable color index for an application, assigning the next palette slot (cycling)
         * the first time the application is seen. The `assigned` map is mutated in place.
         */
        @JvmStatic
        fun colorIndexFor(app: String, assigned: MutableMap<String, Int>, paletteSize: Int): Int {
            val existing = assigned[app]
            if (existing != null) {
                return existing
            }
            val index = assigned.size % paletteSize
            assigned[app] = index
            return index
        }

        /**
         * Splits a raw output chunk into individual lines, dropping the line separators. A chunk
         * without a trailing newline still yields its (partial) last line, so nothing is lost.
         */
        @JvmStatic
        fun splitKeepingLines(text: String): List<String> {
            val lines = ArrayList<String>()
            var start = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    var line = text.substring(start, i)
                    if (line.isNotEmpty() && line[line.length - 1] == '\r') {
                        line = line.substring(0, line.length - 1)
                    }
                    lines.add(line)
                    start = i + 1
                }
            }
            if (start < text.length) {
                lines.add(text.substring(start))
            }
            return lines
        }
    }
}
