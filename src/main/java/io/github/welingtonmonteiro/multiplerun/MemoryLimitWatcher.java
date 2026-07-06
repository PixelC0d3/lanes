package io.github.welingtonmonteiro.multiplerun;

import java.util.Collections;
import java.util.HashMap;
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
 * Background watcher that raises IDE notifications, independently of the Multiple Run Monitor tool
 * window, so you get warned even when the monitor is closed. It handles two things:
 * <ul>
 *   <li>memory: an application with a configured limit crossing {@link #ALERT_THRESHOLD_PERCENT}
 *       of it (once per process, until it is restarted);</li>
 *   <li>health: an application whose {@code port:}/{@code http} "Ready when" condition stays down
 *       for {@link #UNHEALTHY_STREAK} consecutive checks - notified once, with a Restart action,
 *       until it recovers.</li>
 * </ul>
 */
public final class MemoryLimitWatcher {

    public static final int ALERT_THRESHOLD_PERCENT = 90;
    /** Consecutive failed health checks before an app is reported unhealthy. */
    public static final int UNHEALTHY_STREAK = 3;
    /** Consecutive over-threshold samples before a high-CPU alert is raised. */
    public static final int CPU_SUSTAINED_CHECKS = 3;
    private static final int PERIOD_SECONDS = 10;
    private static final Logger LOG = Logger.getInstance(MemoryLimitWatcher.class);

    private static final AtomicBoolean started = new AtomicBoolean();
    /** Weak keys: entries vanish together with their terminated process handlers. */
    private static final Set<ProcessHandler> alreadyNotified =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    /** Consecutive down-count per process, for the health check. */
    private static final Map<ProcessHandler, Integer> downStreaks =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Processes already reported unhealthy, cleared when they recover. */
    private static final Set<ProcessHandler> unhealthyNotified =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    /** Previous CPU-time sample per pid and its timestamp, for the sustained-CPU delta. */
    private static final Map<Long, Double> prevCpuByPid = Collections.synchronizedMap(new HashMap<>());
    private static volatile long prevCpuNanos = 0;
    /** Consecutive over-threshold CPU samples per process. */
    private static final Map<ProcessHandler, Integer> cpuOverStreaks =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Processes already reported for high CPU, cleared when they drop back under the threshold. */
    private static final Set<ProcessHandler> cpuNotified =
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
        return isNearLimit(rssKb, limitMb, ALERT_THRESHOLD_PERCENT);
    }

    /** Same, with a configurable threshold percent. */
    public static boolean isNearLimit(long rssKb, int limitMb, int thresholdPercent) {
        return rssKb >= 0 && limitMb > 0 && thresholdPercent > 0
                && rssKb * 100.0 / (limitMb * 1024L) >= thresholdPercent;
    }

    private static void checkAll() {
        try {
            final Map<Project, List<MultirunProcessRegistry.Entry>> snapshot = MultirunProcessRegistry.snapshot();
            for (Map.Entry<Project, List<MultirunProcessRegistry.Entry>> byProject : snapshot.entrySet()) {
                if (!byProject.getKey().isDisposed()) {
                    check(byProject.getKey(), byProject.getValue());
                    checkHealth(byProject.getKey(), byProject.getValue());
                }
            }
            // CPU needs a delta across the whole tick, so it is sampled once for all projects
            checkCpu(snapshot);
        } catch (Throwable t) {
            // never let an exception kill the scheduled task
            LOG.warn("Multiple Run watcher failed", t);
        }
    }

    /** Next consecutive-down streak given the previous one and whether the app is down right now. */
    public static int nextDownStreak(int previous, boolean down) {
        return down ? previous + 1 : 0;
    }

    /** Whether a high-CPU alert is due: the app has been over its threshold for enough checks. */
    public static boolean isCpuSustained(int consecutiveOver, int checks) {
        return consecutiveOver >= checks;
    }

    /**
     * Reports applications whose CPU % (docker-stats style, delta between two ticks) stays at or
     * above their configured {@code cpuAlertThreshold} for {@link #CPU_SUSTAINED_CHECKS} consecutive
     * checks. Cross-platform (uses the same sampler as the monitor). The alert re-arms when the app
     * drops back under the threshold. The first tick only records the baseline.
     */
    private static void checkCpu(Map<Project, List<MultirunProcessRegistry.Entry>> snapshot) {
        final long now = System.nanoTime();
        final double elapsedSeconds = prevCpuNanos == 0 ? -1 : (now - prevCpuNanos) / 1_000_000_000.0;

        final Map<MultirunProcessRegistry.Entry, Set<Long>> treeByEntry = new LinkedHashMap<>();
        final Map<MultirunProcessRegistry.Entry, Project> projectByEntry = new HashMap<>();
        final Set<Long> allPids = new LinkedHashSet<>();
        for (Map.Entry<Project, List<MultirunProcessRegistry.Entry>> byProject : snapshot.entrySet()) {
            if (byProject.getKey().isDisposed()) {
                continue;
            }
            for (MultirunProcessRegistry.Entry entry : byProject.getValue()) {
                if (entry.cpuAlertThreshold <= 0 || entry.handler.isProcessTerminated()) {
                    continue;
                }
                final Set<Long> treePids =
                        ProcessStatsSampler.processTreePids(MultirunProcessRegistry.pidOf(entry.handler));
                treeByEntry.put(entry, treePids);
                projectByEntry.put(entry, byProject.getKey());
                allPids.addAll(treePids);
            }
        }
        if (allPids.isEmpty()) {
            prevCpuByPid.clear();
            prevCpuNanos = now;
            return;
        }

        final Map<Long, ProcessStatsSampler.Stats> statsByPid = ProcessStatsSampler.samplePids(allPids);
        final Map<Long, Double> prev = new HashMap<>(prevCpuByPid);
        if (elapsedSeconds > 0) {
            for (Map.Entry<MultirunProcessRegistry.Entry, Set<Long>> measured : treeByEntry.entrySet()) {
                final MultirunProcessRegistry.Entry entry = measured.getKey();
                final double deltaCpuSeconds =
                        ProcessStatsSampler.cpuDeltaSeconds(statsByPid, prev, measured.getValue());
                final double cpuPercent = deltaCpuSeconds >= 0 ? deltaCpuSeconds / elapsedSeconds * 100 : -1;
                final boolean over = cpuPercent >= 0 && cpuPercent >= entry.cpuAlertThreshold;

                final int streak = nextDownStreak(cpuOverStreaks.getOrDefault(entry.handler, 0), over);
                cpuOverStreaks.put(entry.handler, streak);
                if (!over) {
                    cpuNotified.remove(entry.handler);
                    continue;
                }
                if (isCpuSustained(streak, CPU_SUSTAINED_CHECKS) && cpuNotified.add(entry.handler)) {
                    final Project project = projectByEntry.get(entry);
                    final com.intellij.notification.Notification notification =
                            NotificationGroupManager.getInstance().getNotificationGroup("Multiple Run")
                                    .createNotification("High CPU usage",
                                                        String.format(Locale.US,
                                                                "'%s' has been using %.0f%% CPU (threshold %d%%) for %d checks.",
                                                                entry.appName, cpuPercent, entry.cpuAlertThreshold,
                                                                CPU_SUSTAINED_CHECKS),
                                                        NotificationType.WARNING);
                    if (entry.environment != null) {
                        notification.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(
                                "Restart", () -> com.intellij.execution.runners.ExecutionUtil.restart(entry.environment)));
                    }
                    notification.notify(project);
                }
            }
        }

        prevCpuByPid.clear();
        statsByPid.forEach((pid, stats) -> prevCpuByPid.put(pid, stats.cpuTimeSeconds));
        prevCpuNanos = now;
    }

    /** Whether to raise the unhealthy alert: the streak reached the limit and we did not alert yet. */
    public static boolean shouldAlertUnhealthy(int streak, boolean alreadyNotified) {
        return streak >= UNHEALTHY_STREAK && !alreadyNotified;
    }

    /**
     * Re-checks the port/http readiness of each app and reports the ones that stayed down for
     * {@link #UNHEALTHY_STREAK} consecutive checks, once, with a Restart action. The alert re-arms
     * when the app recovers, so a later outage is reported again.
     */
    private static void checkHealth(Project project, List<MultirunProcessRegistry.Entry> entries) {
        for (MultirunProcessRegistry.Entry entry : entries) {
            if (entry.handler.isProcessTerminated()) {
                downStreaks.remove(entry.handler);
                unhealthyNotified.remove(entry.handler);
                continue;
            }
            final RunConfigurationHelper.ReadyCondition condition =
                    RunConfigurationHelper.parseReadyCondition(entry.readyCondition);
            final boolean down;
            switch (condition.type) {
                case PORT:
                    down = !RunConfigurationHelper.isPortOpen(condition.port);
                    break;
                case HTTP:
                    down = !RunConfigurationHelper.isHttpHealthy(condition.value);
                    break;
                default:
                    continue; // no monitorable readiness condition
            }

            final int streak = nextDownStreak(downStreaks.getOrDefault(entry.handler, 0), down);
            downStreaks.put(entry.handler, streak);
            if (!down) {
                unhealthyNotified.remove(entry.handler); // recovered: allow a future alert
                continue;
            }
            if (shouldAlertUnhealthy(streak, unhealthyNotified.contains(entry.handler))) {
                unhealthyNotified.add(entry.handler);
                final com.intellij.notification.Notification notification =
                        NotificationGroupManager.getInstance().getNotificationGroup("Multiple Run")
                                .createNotification("Application unhealthy",
                                                    "'" + entry.appName + "' is not answering its readiness check ("
                                                            + entry.readyCondition + ").",
                                                    NotificationType.WARNING);
                if (entry.environment != null) {
                    notification.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(
                            "Restart", () -> com.intellij.execution.runners.ExecutionUtil.restart(entry.environment)));
                }
                notification.notify(project);
            }
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
            if (stats == null || !isNearLimit(stats.rssKb, entry.memoryLimitMb, entry.memAlertThreshold)) {
                continue;
            }
            if (!alreadyNotified.add(entry.handler)) {
                continue; // this process was already warned about
            }
            final double percent = stats.rssKb * 100.0 / (entry.memoryLimitMb * 1024L);
            final String usage = String.format(Locale.US, "'%s' is using %s of its %d MB limit (%.0f%%).",
                                               entry.appName, ProcessStatsSampler.formatMemory(stats.rssKb),
                                               entry.memoryLimitMb, percent);
            if (entry.memLimitRestart && entry.environment != null) {
                // docker-like OOM handling: restart the application instead of letting it degrade
                NotificationGroupManager.getInstance().getNotificationGroup("Multiple Run")
                        .createNotification("Application restarted (memory limit)",
                                            usage + " Restarting it as configured.",
                                            NotificationType.WARNING)
                        .notify(project);
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                        () -> com.intellij.execution.runners.ExecutionUtil.restart(entry.environment));
            } else {
                NotificationGroupManager.getInstance().getNotificationGroup("Multiple Run")
                        .createNotification("Memory limit almost reached", usage, NotificationType.WARNING)
                        .notify(project);
            }
        }
    }
}
