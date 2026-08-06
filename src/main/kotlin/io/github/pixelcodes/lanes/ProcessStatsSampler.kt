package io.github.pixelcodes.lanes

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

import com.intellij.openapi.diagnostic.Logger

/**
 * Samples memory (RSS) and CPU usage of the processes started by Lanes, the same numbers
 * `docker stats` shows for containers. A process is measured together with all its descendants
 * (an npm script spawns the actual node process, a shell script spawns the real server, ...),
 * so the reported usage is the whole process tree.
 *
 * CPU is sampled as cumulative CPU time; the monitor computes the instantaneous percentage from
 * the delta between two consecutive samples, exactly like docker stats does.
 *
 * Sampling shells out to `ps`/`lsof` (Linux/macOS). On platforms without them the sampler returns
 * no data and the monitor shows "n/a".
 */
class ProcessStatsSampler private constructor() {

    /** Usage of one process at one sampling moment. */
    class Stats(
        @JvmField val rssKb: Long,
        /** Cumulative CPU time since the process started, in seconds. */
        @JvmField val cpuTimeSeconds: Double,
    )

    companion object {
        private val LOG = Logger.getInstance(ProcessStatsSampler::class.java)
        private const val PS_TIMEOUT_SECONDS = 5L

        /** Linux scheduler tick rate, needed to convert /proc cpu ticks into seconds. */
        private val CLOCK_TICKS_PER_SECOND: Double = detectClockTicksPerSecond()

        /** The pid itself plus every live descendant (children, grandchildren, ...). */
        @JvmStatic
        fun processTreePids(rootPid: Long): Set<Long> {
            return processTreePidsFor(listOf(rootPid))[rootPid] ?: emptySet()
        }

        /**
         * Process trees of several roots at once - the batch form of [processTreePids], and the one
         * every periodic sampler should use.
         *
         * `ProcessHandle.descendants()` walks *all* processes on the machine per call (on Linux it
         * reads the whole /proc), so calling it once per monitored application made a refresh cost
         * O(apps x processes): measured at ~94 ms per call with ~640 processes running, i.e. ~600 ms
         * for 10 apps, repeated by every sampling loop. Building the parent -> children map from a
         * single [ProcessHandle.allProcesses] snapshot and deriving every tree from it is O(processes)
         * no matter how many applications are monitored (~130 ms for the same 10 apps, and flat as
         * apps are added). Verified to produce trees identical to the per-app `descendants()` walk.
         *
         * Roots <= 0 are skipped; a root with no live process maps to just itself.
         */
        @JvmStatic
        fun processTreePidsFor(rootPids: Collection<Long>): Map<Long, Set<Long>> {
            val roots = rootPids.filter { it > 0 }
            if (roots.isEmpty()) {
                return emptyMap()
            }
            val childrenByParent = HashMap<Long, MutableList<Long>>()
            try {
                ProcessHandle.allProcesses().forEach { handle ->
                    handle.parent().ifPresent { parent ->
                        childrenByParent.getOrPut(parent.pid()) { ArrayList() }.add(handle.pid())
                    }
                }
            } catch (t: Throwable) {
                // a process table snapshot can fail on a restricted platform - degrade to roots only
                LOG.debug("Lanes monitor: cannot enumerate processes", t)
            }

            val trees = LinkedHashMap<Long, Set<Long>>()
            for (root in roots) {
                if (trees.containsKey(root)) {
                    continue
                }
                val pids = LinkedHashSet<Long>()
                val queue = ArrayDeque<Long>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val pid = queue.removeFirst()
                    if (!pids.add(pid)) {
                        continue // already visited: a cycle cannot happen, but never loop forever
                    }
                    childrenByParent[pid]?.let { queue.addAll(it) }
                }
                trees[root] = pids
            }
            return trees
        }

        /** One `ps` (or PowerShell on Windows) call for all pids; missing pids are simply absent. */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun samplePids(pids: Collection<Long>): Map<Long, Stats> {
            if (pids.isEmpty()) {
                return emptyMap()
            }
            val pidList = pids.joinToString(",")
            if (isWindows()) {
                // Get-Process prints "pid rssKb cpuSeconds" lines in the same shape parsePsOutput expects
                return parsePsOutput(runCommand(
                    "powershell", "-NoProfile", "-Command",
                    "Get-Process -Id $pidList -ErrorAction SilentlyContinue | ForEach-Object { " +
                        "'{0} {1} {2}' -f \$_.Id, [math]::Round(\$_.WorkingSet64/1024), " +
                        "\$_.TotalProcessorTime.TotalSeconds }"))
            }
            val stats = parsePsOutput(runCommand("ps", "-o", "pid=,rss=,time=", "-p", pidList)) as MutableMap<Long, Stats>
            // the ps TIME column has 1-second resolution on Linux - useless for a CPU % computed
            // over a 2 s window (the delta is almost always 0). /proc has 10 ms ticks: prefer it.
            for (entry in stats.entries) {
                val procSeconds = procCpuSeconds(entry.key)
                if (procSeconds >= 0) {
                    entry.setValue(Stats(entry.value.rssKb, procSeconds))
                }
            }
            return stats
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
        }

        /** OS start time of a process in epoch millis, or -1 when unknown; feeds the Uptime column. */
        @JvmStatic
        fun processStartMillis(pid: Long): Long {
            if (pid <= 0) {
                return -1
            }
            return ProcessHandle.of(pid)
                .flatMap { handle -> handle.info().startInstant() }
                .map { instant -> instant.toEpochMilli() }
                .orElse(-1L)
        }

        private fun detectClockTicksPerSecond(): Double {
            for (line in runCommand("getconf", "CLK_TCK")) {
                val parsed = line.trim().toLongOrNull()
                if (parsed != null) {
                    return parsed.toDouble()
                }
            }
            return 100.0 // the value on virtually every Linux
        }

        /** Cumulative CPU seconds of one pid from /proc (Linux); -1 where /proc does not exist. */
        private fun procCpuSeconds(pid: Long): Double {
            val statFile = java.io.File("/proc/$pid/stat")
            if (!statFile.exists()) {
                return -1.0
            }
            return try {
                val line = String(java.nio.file.Files.readAllBytes(statFile.toPath()), StandardCharsets.UTF_8)
                val ticks = parseProcStatCpuTicks(line)
                if (ticks < 0) -1.0 else ticks / CLOCK_TICKS_PER_SECOND
            } catch (e: IOException) {
                -1.0
            }
        }

        /**
         * Extracts utime+stime (clock ticks) from a /proc/[pid]/stat line. The command name
         * (field 2) is parenthesized and may contain spaces, so fields are counted after the
         * last ')': utime and stime are the 12th and 13th fields from there.
         * Returns -1 for a malformed line.
         */
        @JvmStatic
        fun parseProcStatCpuTicks(line: String): Long {
            val close = line.lastIndexOf(')')
            if (close < 0) {
                return -1
            }
            val fields = line.substring(close + 1).trim().split(Regex("\\s+"))
            if (fields.size < 13) {
                return -1
            }
            return try {
                fields[11].toLong() + fields[12].toLong()
            } catch (e: NumberFormatException) {
                -1
            }
        }

        /**
         * Parses `ps -o pid=,rss=,time=` output lines (`"1234 151200 00:01:30"`). Malformed
         * lines are skipped.
         */
        @JvmStatic
        fun parsePsOutput(lines: List<String>): Map<Long, Stats> {
            val stats = LinkedHashMap<Long, Stats>()
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) {
                    continue
                }
                try {
                    val pid = parts[0].toLong()
                    val rssKb = parts[1].toLong()
                    val cpuTime = if (parts.size > 2) parseCpuTime(parts[2]) else 0.0
                    stats[pid] = Stats(rssKb, maxOf(0.0, cpuTime))
                } catch (ignored: NumberFormatException) {
                    // header junk or truncated line - skip it
                }
            }
            return stats
        }

        /**
         * Parses the ps TIME column into seconds. Handles the Linux form `[dd-]hh:mm:ss`, the
         * macOS form `mm:ss.xx` and a comma decimal separator (locale-aware ps). Returns -1 when
         * the value is not a time.
         */
        @JvmStatic
        fun parseCpuTime(value: String): Double {
            return try {
                var days = 0.0
                var rest = value
                val dash = value.indexOf('-')
                if (dash >= 0) {
                    days = value.substring(0, dash).toLong().toDouble()
                    rest = value.substring(dash + 1)
                }
                var seconds = 0.0
                for (part in rest.split(":")) {
                    seconds = seconds * 60 + part.replace(',', '.').toDouble()
                }
                days * 86_400 + seconds
            } catch (e: NumberFormatException) {
                -1.0
            }
        }

        /** Sums the stats of the pids belonging to one process tree. */
        @JvmStatic
        fun aggregate(byPid: Map<Long, Stats>, treePids: Set<Long>): Stats? {
            var rssKb = 0L
            var cpuTime = 0.0
            var found = false
            for (pid in treePids) {
                val stats = byPid[pid]
                if (stats != null) {
                    rssKb += stats.rssKb
                    cpuTime += stats.cpuTimeSeconds
                    found = true
                }
            }
            return if (found) Stats(rssKb, cpuTime) else null
        }

        /**
         * CPU seconds burned by a process tree between two samples - the docker-stats way of getting
         * an instantaneous CPU percentage (delta / elapsed * 100). Only pids present in both samples
         * count (a pid missing from the previous sample has no meaningful delta).
         * Returns -1 when no pid of the tree has a previous sample yet.
         */
        @JvmStatic
        fun cpuDeltaSeconds(current: Map<Long, Stats>, previous: Map<Long, Double>, treePids: Set<Long>): Double {
            var delta = 0.0
            var matched = false
            for (pid in treePids) {
                val now = current[pid]
                val before = previous[pid]
                if (now != null && before != null) {
                    delta += maxOf(0.0, now.cpuTimeSeconds - before)
                    matched = true
                }
            }
            return if (matched) delta else -1.0
        }

        /** Formats kilobytes the way docker stats does: `151.2MiB`, `1.50GiB`. */
        @JvmStatic
        fun formatMemory(kb: Long): String {
            if (kb < 0) {
                return "n/a"
            }
            val mib = kb / 1024.0
            return if (mib < 1024) String.format(Locale.US, "%.1fMiB", mib)
                   else String.format(Locale.US, "%.2fGiB", mib / 1024.0)
        }

        /** Formats an uptime like docker ps: `42s`, `5m 12s`, `2h 08m`, `3d 4h`. */
        @JvmStatic
        fun formatUptime(ms: Long): String {
            if (ms < 0) {
                return "n/a"
            }
            val seconds = ms / 1000
            if (seconds < 60) {
                return "${seconds}s"
            }
            val minutes = seconds / 60
            if (minutes < 60) {
                return String.format(Locale.US, "%dm %02ds", minutes, seconds % 60)
            }
            val hours = minutes / 60
            if (hours < 24) {
                return String.format(Locale.US, "%dh %02dm", hours, minutes % 60)
            }
            return String.format(Locale.US, "%dd %dh", hours / 24, hours % 24)
        }

        /**
         * Memory percentage like docker stats: usage against the configured limit when one is set,
         * against the total host memory otherwise. Returns -1 when it cannot be computed.
         */
        @JvmStatic
        fun memoryPercent(rssKb: Long, limitMb: Int?, hostTotalKb: Long): Double {
            val baseKb = if (limitMb != null && limitMb > 0) limitMb * 1024L else hostTotalKb
            if (baseKb <= 0 || rssKb < 0) {
                return -1.0
            }
            return rssKb * 100.0 / baseKb
        }

        /**
         * TCP ports in LISTEN state per pid, like the PORTS column of `docker ps`.
         * Uses `lsof -nPbw -a -p <pids> -iTCP -sTCP:LISTEN`; empty on platforms without lsof.
         *
         * `-b` avoids the kernel calls that can block (notably `stat()` on every mounted file
         * system) and `-w` silences the warnings `-b` would otherwise print for each one. On a
         * machine with many mounts - a Docker host with a few dozen overlay mounts is enough -
         * that stat storm dominated the call: measured at 0.31-0.77 s with plain `-nP` versus
         * 0.06-0.11 s with `-nPbw`, on every refresh. Neither flag changes the listening sockets
         * that are reported.
         */
        @JvmStatic
        fun sampleListeningPorts(pids: Collection<Long>): Map<Long, Set<Int>> {
            if (pids.isEmpty()) {
                return emptyMap()
            }
            val pidList = pids.joinToString(",")
            return parseLsofOutput(runCommand("lsof", "-nPbw", "-a", "-p", pidList, "-iTCP", "-sTCP:LISTEN"))
        }

        /** Pids listening on the given TCP port (`lsof -t`); used by "Kill Process on Port". */
        @JvmStatic
        fun pidsListeningOnPort(port: Int): List<Long> {
            return parseTersePids(runCommand("lsof", "-t", "-iTCP:$port", "-sTCP:LISTEN"))
        }

        /**
         * Parses regular `lsof -iTCP -sTCP:LISTEN` lines
         * (`"node 41234 user 23u IPv6 ... TCP *:3015 (LISTEN)"`) into pid -> listening ports.
         * The pid is the first numeric token (command names may contain spaces); the port is the
         * digits after the last ':' of the address token, so IPv4, IPv6 and wildcard forms all work.
         */
        @JvmStatic
        fun parseLsofOutput(lines: List<String>): Map<Long, Set<Int>> {
            val ports = LinkedHashMap<Long, MutableSet<Int>>()
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                var pid: Long? = null
                var port: Int? = null
                for (part in parts) {
                    if (pid == null) {
                        val parsed = part.toLongOrNull()
                        if (parsed != null) {
                            pid = parsed
                            continue
                        }
                    }
                    val colon = part.lastIndexOf(':')
                    if (colon >= 0 && colon < part.length - 1) {
                        val candidate = part.substring(colon + 1)
                        if (candidate.isNotEmpty() && candidate.all { it.isDigit() }) {
                            port = candidate.toInt()
                        }
                    }
                }
                if (pid != null && port != null) {
                    ports.getOrPut(pid) { java.util.TreeSet() }.add(port)
                }
            }
            return ports
        }

        /** Parses `lsof -t` output: one pid per line, anything else is skipped. */
        @JvmStatic
        fun parseTersePids(lines: List<String>): List<Long> {
            val pids = ArrayList<Long>()
            for (line in lines) {
                val parsed = line.trim().toLongOrNull()
                if (parsed != null) {
                    pids.add(parsed)
                }
            }
            return pids
        }

        private fun runCommand(vararg command: String): List<String> {
            val lines = ArrayList<String>()
            try {
                val process = ProcessBuilder(*command).redirectErrorStream(false).start()
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { lines.add(it) }
                }
                if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (e: IOException) {
                LOG.debug("Lanes monitor: command not available: ${command[0]}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return lines
        }

        /** Total physical memory of the machine in KB, or -1 when unknown. */
        @JvmStatic
        fun hostTotalMemoryKb(): Long {
            return try {
                val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                os.totalMemorySize / 1024
            } catch (t: Throwable) {
                -1
            }
        }
    }
}
