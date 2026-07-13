package io.github.pixelcodes.lanes

import org.yaml.snakeyaml.Yaml

/**
 * Parses a `docker-compose.yml` into a plain list of [Service] records so the run configuration
 * editor can map them onto existing run configurations: `mem_limit` to the memory limit, `env_file`
 * to an environment profile, `depends_on` to start order plus a "Ready when" gate. This class has no
 * IDE dependencies and is unit-tested directly.
 *
 * Only the subset of the Compose spec relevant to those mappings is read; everything else is
 * ignored. Both the short (`mem_limit`) and the Swarm-style (`deploy.resources.limits.memory`)
 * memory forms are understood, as are the list and map shapes of `env_file`, `depends_on` and
 * `ports`.
 *
 * Java callers keep the exact same surface: the nested [Service] type stays nested with
 * field-accessible members (`@JvmField`) and the entry points stay static (`@JvmStatic`).
 */
class ComposeImporter private constructor() {

    /** One service parsed from the compose file, with only the fields we map onto run configs. */
    class Service internal constructor(
        @JvmField val name: String,
        /** Memory limit in MB, or null when the service declares none. */
        @JvmField val memLimitMb: Int?,
        /** Referenced env files (relative to the compose file), in declared order. */
        @JvmField val envFiles: List<String>,
        /** Names of the services this one depends on. */
        @JvmField val dependsOn: List<String>,
        /** Published (host) TCP ports, in declared order. */
        @JvmField val ports: List<Int>,
        @JvmField val hasHealthcheck: Boolean,
    )

    companion object {
        /**
         * Parses the `services:` section of a compose file. Returns an empty list when the text has
         * no services. Throws [IllegalArgumentException] when the YAML is malformed.
         */
        @JvmStatic
        fun parse(yamlText: String?): List<Service> {
            if (yamlText.isNullOrBlank()) {
                return ArrayList()
            }
            val loaded: Any? = try {
                Yaml().load<Any>(yamlText)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("Not a valid YAML file: ${e.message}", e)
            }
            val root = asMap(loaded) ?: return ArrayList()
            val servicesNode = asMap(root["services"]) ?: return ArrayList()

            val services = ArrayList<Service>()
            for ((key, value) in servicesNode) {
                val name = key.toString()
                val body = asMap(value) ?: LinkedHashMap<Any?, Any?>()
                services.add(Service(name, memLimitMbOf(body), envFilesOf(body),
                                     dependsOnOf(body), portsOf(body), body.containsKey("healthcheck")))
            }
            return services
        }

        private fun memLimitMbOf(body: Map<Any?, Any?>): Int? {
            val shortForm = parseMemoryToMb(body["mem_limit"])
            if (shortForm != null) {
                return shortForm
            }
            // deploy.resources.limits.memory
            val resources = asMap(asMap(body["deploy"])?.get("resources")) ?: return null
            val limits = asMap(resources["limits"]) ?: return null
            return parseMemoryToMb(limits["memory"])
        }

        private fun envFilesOf(body: Map<Any?, Any?>): List<String> {
            val result = ArrayList<String>()
            when (val envFile = body["env_file"]) {
                is String -> result.add(envFile.trim())
                is List<*> -> for (each in envFile) {
                    if (each is String) {
                        result.add(each.trim())
                    } else {
                        // long form: { path: ./x.env, required: false }
                        val path = asMap(each)?.get("path")
                        if (path is String) {
                            result.add(path.trim())
                        }
                    }
                }
            }
            return result
        }

        private fun dependsOnOf(body: Map<Any?, Any?>): List<String> {
            val result = ArrayList<String>()
            when (val dependsOn = body["depends_on"]) {
                is List<*> -> for (each in dependsOn) result.add(each.toString())
                // long form: { db: { condition: service_healthy } }
                is Map<*, *> -> for (key in dependsOn.keys) result.add(key.toString())
            }
            return result
        }

        private fun portsOf(body: Map<Any?, Any?>): List<Int> {
            val result = ArrayList<Int>()
            val ports = body["ports"]
            if (ports is List<*>) {
                for (each in ports) {
                    val port = parsePublishedPort(each)
                    if (port != null) {
                        result.add(port)
                    }
                }
            }
            return result
        }

        // --- pure helpers (unit-tested) -----------------------------------------------------------

        /**
         * Converts a docker memory value to whole megabytes. Accepts a raw byte count (number or
         * digit string) or a suffixed string using docker's 1024-based units: `b`, `k`/kb, `m`/mb,
         * `g`/gb (case-insensitive). Returns null for null/blank/unparseable input.
         */
        @JvmStatic
        fun parseMemoryToMb(value: Any?): Int? {
            if (value == null) {
                return null
            }
            if (value is Number) {
                return (value.toLong() / (1024L * 1024L)).toInt()
            }
            var text = value.toString().trim().lowercase()
            if (text.isEmpty()) {
                return null
            }
            var multiplier = 1L
            if (text.endsWith("gb") || text.endsWith("g")) {
                multiplier = 1024L * 1024L * 1024L
                text = text.substring(0, text.length - if (text.endsWith("gb")) 2 else 1)
            } else if (text.endsWith("mb") || text.endsWith("m")) {
                multiplier = 1024L * 1024L
                text = text.substring(0, text.length - if (text.endsWith("mb")) 2 else 1)
            } else if (text.endsWith("kb") || text.endsWith("k")) {
                multiplier = 1024L
                text = text.substring(0, text.length - if (text.endsWith("kb")) 2 else 1)
            } else if (text.endsWith("b")) {
                text = text.substring(0, text.length - 1)
            }
            return try {
                val bytes = (text.trim().toDouble() * multiplier).toLong()
                (bytes / (1024L * 1024L)).toInt()
            } catch (e: NumberFormatException) {
                null
            }
        }

        /**
         * Extracts the published (host) port from a compose `ports` entry. Handles the string short
         * forms (`"3000"`, `"3000:3000"`, `"127.0.0.1:8080:80"`, `"3000-3005:3000-3005"`, optional
         * `/tcp` suffix) and the long map form with a `published` key. Returns null when no host port
         * can be determined.
         */
        @JvmStatic
        fun parsePublishedPort(value: Any?): Int? {
            if (value == null) {
                return null
            }
            if (value is Number) {
                return value.toInt()
            }
            val map = asMap(value)
            if (map != null) {
                val published = map["published"]
                return if (published == null) null else firstPortNumber(published.toString())
            }
            var text = value.toString().trim()
            val slash = text.indexOf('/')
            if (slash >= 0) {
                text = text.substring(0, slash) // drop the /tcp or /udp protocol
            }
            val parts = text.split(":")
            // "target" -> parts[0]; "published:target" -> parts[0]; "ip:published:target" -> parts[1]
            val hostPart = if (parts.size == 3) parts[1] else parts[0]
            return firstPortNumber(hostPart)
        }

        /** First integer of a port token, unwrapping a `3000-3005` range to its start. */
        private fun firstPortNumber(token: String): Int? {
            var value = token.trim()
            val dash = value.indexOf('-')
            if (dash > 0) {
                value = value.substring(0, dash)
            }
            return try {
                value.trim().toInt()
            } catch (e: NumberFormatException) {
                null
            }
        }

        /**
         * Orders service names so every dependency comes before the services that depend on it
         * (depth-first topological sort). Unknown dependencies are ignored and dependency cycles are
         * broken gracefully, so the result always contains every input name exactly once.
         */
        @JvmStatic
        @JvmSuppressWildcards
        fun topologicalOrder(names: List<String>, dependsOn: Map<String, List<String>>): List<String> {
            val known: Set<String> = LinkedHashSet(names)
            val ordered = ArrayList<String>()
            val done = LinkedHashSet<String>()
            val visiting = LinkedHashSet<String>()
            for (name in names) {
                visit(name, known, dependsOn, done, visiting, ordered)
            }
            return ordered
        }

        private fun visit(name: String, known: Set<String>, dependsOn: Map<String, List<String>>,
                          done: MutableSet<String>, visiting: MutableSet<String>, ordered: MutableList<String>) {
            if (done.contains(name) || !known.contains(name) || !visiting.add(name)) {
                return // already placed, unknown, or a cycle we refuse to follow again
            }
            val deps = dependsOn[name]
            if (deps != null) {
                for (dep in deps) {
                    visit(dep, known, dependsOn, done, visiting, ordered)
                }
            }
            visiting.remove(name)
            if (done.add(name)) {
                ordered.add(name)
            }
        }

        /** Reads a YAML node as a string-keyed map (SnakeYAML returns `Map<Object, Object>`), or null. */
        @Suppress("UNCHECKED_CAST")
        private fun asMap(value: Any?): Map<Any?, Any?>? = value as? Map<Any?, Any?>
    }
}
