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

    /** One captured console line together with the application it came from. */
    static final class LogLine {
        final String app;
        final int colorIndex;
        final String text;

        LogLine(String app, int colorIndex, String text) {
            this.app = app;
            this.colorIndex = colorIndex;
            this.text = text;
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
                if (logFilter.accepts(app, line)) {
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
            if (logFilter.accepts(line.app, line.text)) {
                appendToPane(line);
            }
        }
        if (scrollToEnd) {
            scrollToBottom();
        }
    }

    private void appendToPane(LogLine line) {
        final javax.swing.text.StyledDocument doc = textPane.getStyledDocument();
        final javax.swing.text.SimpleAttributeSet attrs = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setForeground(attrs, PALETTE[line.colorIndex]);
        try {
            doc.insertString(doc.getLength(), formatLine(line.app, line.text) + "\n", attrs);
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
