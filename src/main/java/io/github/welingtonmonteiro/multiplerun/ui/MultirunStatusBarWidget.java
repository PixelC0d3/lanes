package io.github.welingtonmonteiro.multiplerun.ui;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import io.github.welingtonmonteiro.multiplerun.MultirunProcessRegistry;
import io.github.welingtonmonteiro.multiplerun.ProcessStatsSampler;
import io.github.welingtonmonteiro.multiplerun.RunConfigurationHelper;

/**
 * A status bar widget summarizing the applications started by Multiple Run: how many are running,
 * their combined memory, and how many are unhealthy (a port/http "Ready when" that is currently
 * down). Clicking it opens the Multiple Run Monitor. It refreshes on a background timer and hides
 * itself (empty text) when nothing this plugin started is running.
 */
public class MultirunStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    static final String ID = "MultipleRun.Monitor.Widget";
    private static final int REFRESH_SECONDS = 5;

    private final Project project;
    @Nullable
    private StatusBar statusBar;
    @Nullable
    private ScheduledFuture<?> updater;
    private volatile String text = "";

    MultirunStatusBarWidget(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    @Override
    public String ID() {
        return ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        updater = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::refresh, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        if (updater != null) {
            updater.cancel(true);
            updater = null;
        }
        statusBar = null;
    }

    @Nullable
    @Override
    public WidgetPresentation getPresentation() {
        return this;
    }

    // --- TextPresentation -----------------------------------------------------------------------

    @NotNull
    @Override
    public String getText() {
        return text;
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Nullable
    @Override
    public String getTooltipText() {
        return "Multiple Run: running apps, total memory and unhealthy count. Click to open the monitor.";
    }

    @Nullable
    @Override
    public com.intellij.util.Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            final ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(MultirunMonitorToolWindowFactory.TOOL_WINDOW_ID);
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
        };
    }

    // --- refresh --------------------------------------------------------------------------------

    private void refresh() {
        if (project.isDisposed()) {
            return;
        }
        final List<MultirunProcessRegistry.Entry> entries = MultirunProcessRegistry.getEntries(project);

        final Set<Long> allPids = new LinkedHashSet<>();
        for (MultirunProcessRegistry.Entry entry : entries) {
            allPids.addAll(ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler)));
        }
        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);

        long totalRssKb = 0;
        int unhealthy = 0;
        for (MultirunProcessRegistry.Entry entry : entries) {
            final Set<Long> treePids =
                    ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler));
            final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(statsByPid, treePids);
            if (stats != null) {
                totalRssKb += stats.rssKb;
            }
            if (isUnhealthy(entry.readyCondition)) {
                unhealthy++;
            }
        }

        text = widgetText(entries.size(), totalRssKb, unhealthy);
        if (statusBar != null) {
            statusBar.updateWidget(ID);
        }
    }

    /** True when the app has a port/http readiness condition that is currently not answering. */
    private static boolean isUnhealthy(@Nullable String readyCondition) {
        final RunConfigurationHelper.ReadyCondition condition =
                RunConfigurationHelper.parseReadyCondition(readyCondition);
        switch (condition.type) {
            case PORT:
                return !RunConfigurationHelper.isPortOpen(condition.port);
            case HTTP:
                return !RunConfigurationHelper.isHttpHealthy(condition.value);
            default:
                return false;
        }
    }

    /**
     * Builds the widget label. Empty when nothing is running (so the widget stays out of the way),
     * otherwise "&#9654; N apps &middot; &lt;mem&gt;" with a "&middot; &#9888; K" suffix when K apps are unhealthy.
     */
    static String widgetText(int appCount, long totalRssKb, int unhealthy) {
        if (appCount <= 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append('▶').append(' ').append(appCount).append(appCount == 1 ? " app" : " apps");
        sb.append(" · ").append(ProcessStatsSampler.formatMemory(totalRssKb));
        if (unhealthy > 0) {
            sb.append(" · ⚠ ").append(unhealthy);
        }
        return sb.toString();
    }
}
