package com.khmelyuk.multirun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.project.Project;

/**
 * Keeps track of every process started by a Multiple Run configuration, so the
 * "Multiple Run Monitor" tool window can list them with live memory statistics.
 * Entries are removed automatically when the process terminates.
 */
public final class MultirunProcessRegistry {

    /** One running application started by a Multirun configuration. */
    public static final class Entry {
        public final String multirunName;
        public final String appName;
        public final ProcessHandler handler;
        /** Configured memory cap in MB, or null when the application has no limit. */
        public final Integer memoryLimitMb;
        public final long startedAtMs;

        Entry(String multirunName, String appName, ProcessHandler handler, Integer memoryLimitMb) {
            this.multirunName = multirunName;
            this.appName = appName;
            this.handler = handler;
            this.memoryLimitMb = memoryLimitMb;
            this.startedAtMs = System.currentTimeMillis();
        }
    }

    private static final Map<Project, List<Entry>> ENTRIES = new ConcurrentHashMap<>();

    private MultirunProcessRegistry() {
    }

    public static void register(@NotNull Project project, String multirunName, String appName,
                                @NotNull ProcessHandler handler, @Nullable Integer memoryLimitMb) {
        final Entry entry = new Entry(multirunName, appName, handler, memoryLimitMb);
        ENTRIES.computeIfAbsent(project, p -> new CopyOnWriteArrayList<>()).add(entry);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                unregister(project, handler);
            }
        });
        // the handler may have died between the run and this call
        if (handler.isProcessTerminated()) {
            unregister(project, handler);
        }
    }

    public static void unregister(@NotNull Project project, @NotNull ProcessHandler handler) {
        final List<Entry> list = ENTRIES.get(project);
        if (list != null) {
            list.removeIf(entry -> entry.handler == handler);
        }
    }

    @NotNull
    public static List<Entry> getEntries(@NotNull Project project) {
        final List<Entry> list = ENTRIES.get(project);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /** The OS pid behind the handler, or -1 when it cannot be determined. */
    public static long pidOf(@NotNull ProcessHandler handler) {
        if (handler instanceof BaseProcessHandler) {
            try {
                return ((BaseProcessHandler<?>) handler).getProcess().pid();
            } catch (UnsupportedOperationException ignored) {
                // some Process implementations don't expose a pid
            }
        }
        return -1;
    }
}
