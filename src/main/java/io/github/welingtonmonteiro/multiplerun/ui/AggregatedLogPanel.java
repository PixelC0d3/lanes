package io.github.welingtonmonteiro.multiplerun.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.swing.Timer;

import org.jetbrains.annotations.NotNull;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;

import io.github.welingtonmonteiro.multiplerun.LogFilter;

/**
 * The "Logs" tab of the Multiple Run Monitor tool window: a single, aggregated view of the console
 * output of every process the IDE is running - like {@code docker compose logs -f}. Each line is
 * prefixed with {@code [app]} in a per-application color, and a filter field narrows the view to
 * lines containing a substring.
 *
 * <p>Output arrives on process threads, is buffered in a concurrent queue and flushed to the text
 * pane on the EDT by a Swing timer, which also attaches the listener to processes started after the
 * panel was opened.
 */
public class AggregatedLogPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int FLUSH_INTERVAL_MS = 1000;
    /** Hard cap on retained lines, so a chatty app cannot grow the buffer without bound. */
    private static final int MAX_LINES = 5000;

    /** Distinct colors cycled through as new applications appear (docker-compose style). */
    private static final JBColor[] PALETTE = {
            new JBColor(0x1F6FEB, 0x58A6FF), // blue
            new JBColor(0x2DA44E, 0x3FB950), // green
            new JBColor(0xBC4C00, 0xDB6D28), // orange
            new JBColor(0x8250DF, 0xA371F7), // purple
            new JBColor(0xCF222E, 0xF85149), // red
            new JBColor(0x9A6700, 0xD29922), // gold
            new JBColor(0x1B7C83, 0x39C5CF), // teal
            new JBColor(0xBF3989, 0xDB61A2), // magenta
    };

    /** ANSI 16-color palette (standard 0-7 then bright 8-15), theme-aware for light/dark. */
    private static final JBColor[] ANSI_PALETTE = {
            new JBColor(0x000000, 0xBBBBBB), // black (light gray on dark, so it stays visible)
            new JBColor(0xCC0000, 0xFF5555), // red
            new JBColor(0x00A000, 0x50FA7B), // green
            new JBColor(0x998A00, 0xF1FA8C), // yellow
            new JBColor(0x0000CC, 0x6699FF), // blue
            new JBColor(0xA000A0, 0xFF79C6), // magenta
            new JBColor(0x008B8B, 0x8BE9FD), // cyan
            new JBColor(0x808080, 0xBFBFBF), // white / light gray
            new JBColor(0x555555, 0x888888), // bright black
            new JBColor(0xE00000, 0xFF6E6E), // bright red
            new JBColor(0x00A800, 0x69FF94), // bright green
            new JBColor(0xB0A000, 0xFFFFA5), // bright yellow
            new JBColor(0x3333FF, 0x8AB4FF), // bright blue
            new JBColor(0xD000D0, 0xFF92DF), // bright magenta
            new JBColor(0x00A8A8, 0xA4FFFF), // bright cyan
            new JBColor(0x606060, 0xFFFFFF), // bright white
    };

    /** A CSI escape sequence (colors, cursor moves, line erases): ESC [ params interm final. */
    private static final java.util.regex.Pattern ANSI_CSI =
            java.util.regex.Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    /** One captured console line together with the application it came from. */
    static final class LogLine {
        final String app;
        final int colorIndex;
        /** Original text, may contain ANSI escape codes (used when ANSI rendering is on). */
        final String raw;
        /** ANSI stripped, for filtering and the default (clean) rendering. */
        final String visible;

        LogLine(String app, int colorIndex, String raw) {
            this.app = app;
            this.colorIndex = colorIndex;
            this.raw = raw;
            this.visible = stripAnsi(raw);
        }
    }

    /** One run of text with a resolved ANSI style (foreground index, bold). */
    static final class AnsiSpan {
        final String text;
        final int fgIndex; // -1 = default foreground
        final boolean bold;

        AnsiSpan(String text, int fgIndex, boolean bold) {
            this.text = text;
            this.fgIndex = fgIndex;
            this.bold = bold;
        }
    }

    /** First item of the application combo: no per-app restriction. */
    private static final String ALL_APPS = "All apps";

    private final Project project;
    private final javax.swing.JTextPane textPane = new javax.swing.JTextPane();
    private final JBTextField filterField = new JBTextField(22);
    private final javax.swing.JComboBox<String> appCombo = new javax.swing.JComboBox<>();
    private final javax.swing.JComboBox<String> modeCombo =
            new javax.swing.JComboBox<>(new String[]{"Match any", "Match all"});
    private final javax.swing.JComboBox<String> actionCombo =
            new javax.swing.JComboBox<>(new String[]{"Show matching", "Hide matching"});
    private final javax.swing.JComboBox<String> levelCombo =
            new javax.swing.JComboBox<>(new String[]{"All levels", "Info+", "Warn+", "Errors"});
    private final JBCheckBox caseBox = new JBCheckBox("Aa");
    private final JBCheckBox regexBox = new JBCheckBox(".*");
    private final JBCheckBox ansiBox = new JBCheckBox("ANSI");
    private final Timer timer;

    /** All captured lines (bounded by {@link #MAX_LINES}); the source of truth for re-rendering. */
    private final List<LogLine> allLines = new ArrayList<>();
    /** Lines produced off the EDT, drained on the timer tick. */
    private final ConcurrentLinkedQueue<String[]> pending = new ConcurrentLinkedQueue<>();
    /** Handlers we already attached a listener to, so we never double-count their output. */
    private final Set<ProcessHandler> listened =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Stable color index per application name, assigned on first appearance. */
    private final Map<String, Integer> appColorIndex = new LinkedHashMap<>();
    /** Every application name seen so far (for the app combo), in appearance order. */
    private final Set<String> knownApps = new java.util.LinkedHashSet<>();

    private volatile LogFilter logFilter = LogFilter.all();
    private boolean scrollToEnd = true;
    /** When true the log renders real ANSI colors; when false the escape codes are stripped. */
    private boolean ansiColors = false;
    /** Guards combo repopulation so programmatic changes don't trigger a filter rebuild. */
    private boolean updatingCombo = false;

    public AggregatedLogPanel(@NotNull Project project) {
        super(false, true);
        this.project = project;

        textPane.setEditable(false);
        textPane.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                                           textPane.getFont().getSize()));

        final DefaultActionGroup group = new DefaultActionGroup();
        group.add(new DumbAwareAction("Clear", "Clear the aggregated log", AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                allLines.clear();
                rebuild();
            }
        });
        group.add(new DumbAwareToggleAction("Scroll to End", "Follow the output as it arrives",
                                            AllIcons.RunConfigurations.Scroll_down) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return scrollToEnd;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                scrollToEnd = state;
                Toggleable.setSelected(e.getPresentation(), state);
                if (state) {
                    scrollToBottom();
                }
            }
        });
        final ActionToolbar toolbar =
                ActionManager.getInstance().createActionToolbar("MultipleRunAggregatedLogs", group, true);
        toolbar.setTargetComponent(textPane);

        setToolbar(buildHeader(toolbar));
        setContent(ScrollPaneFactory.createScrollPane(textPane));

        timer = new Timer(FLUSH_INTERVAL_MS, e -> {
            syncListeners();
            drainPending();
        });
        timer.start();
        syncListeners();
    }

    /** Builds the header strip: the action toolbar plus the app selector and advanced filter controls. */
    private javax.swing.JComponent buildHeader(ActionToolbar toolbar) {
        appCombo.addItem(ALL_APPS);
        appCombo.setToolTipText("Show logs of a single application");
        caseBox.setToolTipText("Case sensitive");
        regexBox.setToolTipText("Interpret terms as regular expressions");
        ansiBox.setToolTipText("Render ANSI colors (off = strip the color/escape codes for a clean log)");
        modeCombo.setToolTipText("Combine several comma-separated terms with AND (all) or OR (any)");
        actionCombo.setToolTipText("Show only matching lines, or hide matching lines");
        levelCombo.setToolTipText("Only show lines at or above a log level (detected from the text)");
        filterField.getEmptyText().setText("Filter (comma-separated terms)");

        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildFilter(); }
        });
        final java.awt.event.ActionListener rebuild = e -> rebuildFilter();
        appCombo.addActionListener(rebuild);
        modeCombo.addActionListener(rebuild);
        actionCombo.addActionListener(rebuild);
        levelCombo.addActionListener(rebuild);
        caseBox.addActionListener(rebuild);
        regexBox.addActionListener(rebuild);
        // ANSI toggle is display-only (does not affect the filter), so re-render directly
        ansiBox.addActionListener(e -> {
            ansiColors = ansiBox.isSelected();
            rebuild();
        });

        final javax.swing.JPanel controls =
                new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        controls.add(new JBLabel("App:"));
        controls.add(appCombo);
        controls.add(new JBLabel("Filter:"));
        controls.add(filterField);
        controls.add(modeCombo);
        controls.add(actionCombo);
        controls.add(caseBox);
        controls.add(regexBox);
        controls.add(new JBLabel("Level:"));
        controls.add(levelCombo);
        controls.add(ansiBox);

        final javax.swing.JPanel header = new javax.swing.JPanel(new java.awt.BorderLayout());
        header.add(toolbar.getComponent(), java.awt.BorderLayout.WEST);
        header.add(controls, java.awt.BorderLayout.CENTER);
        return header;
    }

    /** Rebuilds {@link #logFilter} from the current controls and re-renders. */
    private void rebuildFilter() {
        if (updatingCombo) {
            return;
        }
        final Object selectedApp = appCombo.getSelectedItem();
        final String app = selectedApp == null || ALL_APPS.equals(selectedApp) ? null : selectedApp.toString();
        final LogFilter.Mode mode = modeCombo.getSelectedIndex() == 1 ? LogFilter.Mode.ALL : LogFilter.Mode.ANY;
        final boolean exclude = actionCombo.getSelectedIndex() == 1;
        logFilter = LogFilter.from(app, filterField.getText(), mode, exclude,
                                   caseBox.isSelected(), regexBox.isSelected(),
                                   levelForIndex(levelCombo.getSelectedIndex()));
        rebuild();
    }

    private static LogFilter.Level levelForIndex(int index) {
        switch (index) {
            case 1:  return LogFilter.Level.INFO;
            case 2:  return LogFilter.Level.WARN;
            case 3:  return LogFilter.Level.ERROR;
            default: return null; // "All levels"
        }
    }

    /** Adds any newly seen application to the combo, preserving the current selection. */
    private void refreshAppCombo() {
        final Object selected = appCombo.getSelectedItem();
        updatingCombo = true;
        try {
            appCombo.removeAllItems();
            appCombo.addItem(ALL_APPS);
            for (String app : knownApps) {
                appCombo.addItem(app);
            }
            appCombo.setSelectedItem(selected == null ? ALL_APPS : selected);
            if (appCombo.getSelectedItem() == null) {
                appCombo.setSelectedItem(ALL_APPS);
            }
        } finally {
            updatingCombo = false;
        }
    }

    /** Attaches the capturing listener to every running process not yet followed. */
    private void syncListeners() {
        boolean appsChanged = false;
        for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
            final ProcessHandler handler = descriptor.getProcessHandler();
            final String app = descriptor.getDisplayName();
            if (knownApps.add(app)) {
                appsChanged = true; // a new application appeared: offer it in the app combo
            }
            if (handler == null || handler.isProcessTerminated() || !listened.add(handler)) {
                continue;
            }
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull com.intellij.openapi.util.Key outputType) {
                    if (event.getText() != null && !event.getText().isEmpty()) {
                        pending.add(new String[]{app, event.getText()});
                    }
                }

                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    listened.remove(handler);
                }
            });
        }
        if (appsChanged) {
            refreshAppCombo();
        }
    }

    /** Moves buffered output into the model and the text pane; runs on the EDT (timer thread). */
    private void drainPending() {
        String[] chunk;
        boolean added = false;
        while ((chunk = pending.poll()) != null) {
            final String app = chunk[0];
            final int colorIndex = colorIndexFor(app, appColorIndex, PALETTE.length);
            for (String line : splitKeepingLines(chunk[1])) {
                final LogLine logLine = new LogLine(app, colorIndex, line);
                allLines.add(logLine);
                if (logFilter.accepts(app, logLine.visible)) {
                    appendToPane(logLine);
                    added = true;
                }
            }
        }
        if (allLines.size() > MAX_LINES) {
            // trim oldest and re-render once; keeps memory bounded for long sessions
            allLines.subList(0, allLines.size() - MAX_LINES).clear();
            rebuild();
        } else if (added && scrollToEnd) {
            scrollToBottom();
        }
    }

    /** Rebuilds the whole text pane from {@link #allLines}, honoring the current filter. */
    private void rebuild() {
        textPane.setText("");
        for (LogLine line : allLines) {
            if (logFilter.accepts(line.app, line.visible)) {
                appendToPane(line);
            }
        }
        if (scrollToEnd) {
            scrollToBottom();
        }
    }

    private void appendToPane(LogLine line) {
        final javax.swing.text.StyledDocument doc = textPane.getStyledDocument();
        final javax.swing.text.SimpleAttributeSet appAttrs = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setForeground(appAttrs, PALETTE[line.colorIndex]);
        try {
            if (ansiColors) {
                // render real ANSI colors: keep the [app] prefix in the per-app color, then color
                // the body from its escape codes (default foreground where no color is set)
                doc.insertString(doc.getLength(), "[" + line.app + "] ", appAttrs);
                for (AnsiSpan span : parseAnsi(line.raw)) {
                    final javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
                    if (span.fgIndex >= 0 && span.fgIndex < ANSI_PALETTE.length) {
                        javax.swing.text.StyleConstants.setForeground(a, ANSI_PALETTE[span.fgIndex]);
                    }
                    if (span.bold) {
                        javax.swing.text.StyleConstants.setBold(a, true);
                    }
                    doc.insertString(doc.getLength(), span.text, a);
                }
                doc.insertString(doc.getLength(), "\n", appAttrs);
            } else {
                // clean rendering: strip the escape codes, whole line in the per-app color
                doc.insertString(doc.getLength(), formatLine(line.app, line.visible) + "\n", appAttrs);
            }
        } catch (javax.swing.text.BadLocationException ignored) {
            // the document length is always valid here; nothing to recover
        }
    }

    private void scrollToBottom() {
        textPane.setCaretPosition(textPane.getDocument().getLength());
    }

    // --- pure helpers (unit-tested) -------------------------------------------------------------

    /** Prefixes a single console line with its application tag: {@code [app] text}. */
    static String formatLine(String app, String text) {
        return "[" + app + "] " + text;
    }

    /** True when the line should be shown for the given filter (empty filter shows everything). */
    static boolean matchesFilter(String line, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        return line.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    /** Removes every ANSI/CSI escape sequence (colors, cursor moves, line erases) from a line. */
    static String stripAnsi(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('\u001B') < 0) {
            return text; // fast path: no escape char at all
        }
        return ANSI_CSI.matcher(text).replaceAll("");
    }

    /**
     * Splits a line into styled runs by interpreting its ANSI SGR (color/bold) escape codes. Only
     * SGR sequences (ending in {@code m}) change the style; other CSI sequences (cursor moves, line
     * erases) are dropped. The result concatenated back equals {@link #stripAnsi(String)}.
     */
    static List<AnsiSpan> parseAnsi(String text) {
        final List<AnsiSpan> spans = new ArrayList<>();
        if (text == null) {
            return spans;
        }
        final StringBuilder current = new StringBuilder();
        int fg = -1;
        boolean bold = false;
        int i = 0;
        final int n = text.length();
        while (i < n) {
            final char c = text.charAt(i);
            if (c == '\u001B' && i + 1 < n && text.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < n && (text.charAt(j) < '@' || text.charAt(j) > '~')) {
                    j++;
                }
                if (j < n) {
                    if (text.charAt(j) == 'm') { // SGR: flush the current run, then update the style
                        if (current.length() > 0) {
                            spans.add(new AnsiSpan(current.toString(), fg, bold));
                            current.setLength(0);
                        }
                        final int[] applied = applySgr(text.substring(i + 2, j), fg, bold);
                        fg = applied[0];
                        bold = applied[1] == 1;
                    }
                    i = j + 1; // skip the whole sequence (SGR applied, others ignored)
                    continue;
                }
                i++; // malformed trailing escape: drop the ESC and continue
                continue;
            }
            current.append(c);
            i++;
        }
        if (current.length() > 0) {
            spans.add(new AnsiSpan(current.toString(), fg, bold));
        }
        return spans;
    }

    /** Applies an SGR parameter string ({@code "1;32"}) to the current (fg, bold); returns {fg, bold}. */
    static int[] applySgr(String params, int fg, boolean bold) {
        if (params.isEmpty()) {
            return new int[]{-1, 0}; // ESC[m is a full reset
        }
        for (String part : params.split(";")) {
            final int code;
            try {
                code = part.isEmpty() ? 0 : Integer.parseInt(part);
            } catch (NumberFormatException e) {
                continue;
            }
            if (code == 0) {
                fg = -1;
                bold = false;
            } else if (code == 1) {
                bold = true;
            } else if (code == 22) {
                bold = false;
            } else if (code == 39) {
                fg = -1;
            } else if (code >= 30 && code <= 37) {
                fg = code - 30;
            } else if (code >= 90 && code <= 97) {
                fg = code - 90 + 8;
            }
        }
        return new int[]{fg, bold ? 1 : 0};
    }

    /**
     * Returns a stable color index for an application, assigning the next palette slot (cycling)
     * the first time the application is seen. The {@code assigned} map is mutated in place.
     */
    static int colorIndexFor(String app, Map<String, Integer> assigned, int paletteSize) {
        final Integer existing = assigned.get(app);
        if (existing != null) {
            return existing;
        }
        final int index = assigned.size() % paletteSize;
        assigned.put(app, index);
        return index;
    }

    /**
     * Splits a raw output chunk into individual lines, dropping the line separators. A chunk
     * without a trailing newline still yields its (partial) last line, so nothing is lost.
     */
    static List<String> splitKeepingLines(String text) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                String line = text.substring(start, i);
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    @Override
    public void dispose() {
        timer.stop();
        pending.clear();
        listened.clear();
    }
}
