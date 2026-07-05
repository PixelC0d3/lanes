package com.khmelyuk.multirun;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

/**
 * Background watcher that raises an IDE notification when an application with a configured
 * memory limit crosses {@link #ALERT_THRESHOLD_PERCENT} of it. Runs independently of the
 * Multiple Run Monitor tool window, so you get warned even when the monitor is closed.
 * Each process is notified at most once (until it is restarted).
 */
public final class MemoryLimitWatcher {

    public static final int ALERT_THRESHOLD_PERCENT = 90;
    private static final int PERIOD_SECONDS = 10;
    private static final Logger LOG = Logger.getInstance(MemoryLimitWatcher.class);

    private static final AtomicBoolean started = new AtomicBoolean();
    /** Weak keys: entries vanish together with their terminated process handlers. */
    private static final Set<ProcessHandler> alreadyNotified =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private MemoryLimitWatcher() {
    }

    /** Starts the periodic check once per IDE session; called whenever a process is registered. */
    public static void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                    MemoryLimitWatcher::checkAll, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** true when the measured usage crossed the alert threshold of the configured limit. */
    public static boolean isNearLimit(long rssKb, int limitMb) {
        return rssKb >= 0 && limitMb > 0 && rssKb * 100.0 / (limitMb * 1024L) >= ALERT_THRESHOLD_PERCENT;
    }

    private static void checkAll() {
        try {
            for (Map.Entry<Project, List<MultirunProcessRegistry.Entry>> byProject
                    : MultirunProcessRegistry.snapshot().entrySet()) {
                if (!byProject.getKey().isDisposed()) {
                    check(byProject.getKey(), byProject.getValue());
                }
            }
        } catch (Throwable t) {
            // never let an exception kill the scheduled task
            LOG.warn("Multiple Run memory limit watcher failed", t);
        }
    }

    private static void check(Project project, List<MultirunProcessRegistry.Entry> entries) {
        // only applications with a configured limit are worth a ps call
        final Map<MultirunProcessRegistry.Entry, Set<Long>> treeByEntry = new LinkedHashMap<>();
        final Set<Long> allPids = new LinkedHashSet<>();
        for (MultirunProcessRegistry.Entry entry : entries) {
            if (entry.memoryLimitMb == null || entry.memoryLimitMb <= 0 || entry.handler.isProcessTerminated()) {
                continue;
            }
            final Set<Long> treePids =
                    ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler));
            treeByEntry.put(entry, treePids);
            allPids.addAll(treePids);
        }
        if (allPids.isEmpty()) {
            return;
        }

        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);
        for (Map.Entry<MultirunProcessRegistry.Entry, Set<Long>> measured : treeByEntry.entrySet()) {
            final MultirunProcessRegistry.Entry entry = measured.getKey();
            final ProcessStatsSampler.Stats stats = ProcessStatsSampler.aggregate(statsByPid, measured.getValue());
            if (stats == null || !isNearLimit(stats.rssKb, entry.memoryLimitMb)) {
                continue;
            }
            if (!alreadyNotified.add(entry.handler)) {
                continue; // this process was already warned about
            }
            final double percent = stats.rssKb * 100.0 / (entry.memoryLimitMb * 1024L);
            NotificationGroupManager.getInstance().getNotificationGroup("Multiple Run")
                    .createNotification(
                            "Memory limit almost reached",
                            String.format(Locale.US, "'%s' is using %s of its %d MB limit (%.0f%%).",
                                          entry.appName, ProcessStatsSampler.formatMemory(stats.rssKb),
                                          entry.memoryLimitMb, percent),
                            NotificationType.WARNING)
                    .notify(project);
        }
    }
}
