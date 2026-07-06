package io.github.welingtonmonteiro.multiplerun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses a {@code docker-compose.yml} into a plain list of {@link Service} records so the run
 * configuration editor can map them onto existing run configurations: {@code mem_limit} to the
 * memory limit, {@code env_file} to an environment profile, {@code depends_on} to start order plus
 * a "Ready when" gate. This class has no IDE dependencies and is unit-tested directly.
 *
 * <p>Only the subset of the Compose spec relevant to those mappings is read; everything else is
 * ignored. Both the short ({@code mem_limit}) and the Swarm-style
 * ({@code deploy.resources.limits.memory}) memory forms are understood, as are the list and map
 * shapes of {@code env_file}, {@code depends_on} and {@code ports}.
 */
public final class ComposeImporter {

    /** One service parsed from the compose file, with only the fields we map onto run configs. */
    public static final class Service {
        public final String name;
        /** Memory limit in MB, or null when the service declares none. */
        public final Integer memLimitMb;
        /** Referenced env files (relative to the compose file), in declared order. */
        public final List<String> envFiles;
        /** Names of the services this one depends on. */
        public final List<String> dependsOn;
        /** Published (host) TCP ports, in declared order. */
        public final List<Integer> ports;
        public final boolean hasHealthcheck;

        Service(String name, Integer memLimitMb, List<String> envFiles,
                List<String> dependsOn, List<Integer> ports, boolean hasHealthcheck) {
            this.name = name;
            this.memLimitMb = memLimitMb;
            this.envFiles = envFiles;
            this.dependsOn = dependsOn;
            this.ports = ports;
            this.hasHealthcheck = hasHealthcheck;
        }
    }

    private ComposeImporter() {
    }

    /**
     * Parses the {@code services:} section of a compose file. Returns an empty list when the text
     * has no services. Throws {@link IllegalArgumentException} when the YAML is malformed.
     */
    @NotNull
    public static List<Service> parse(@Nullable String yamlText) {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            return new ArrayList<>();
        }
        final Object root;
        try {
            root = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a valid YAML file: " + e.getMessage(), e);
        }
        if (!(root instanceof Map)) {
            return new ArrayList<>();
        }
        final Object servicesNode = ((Map<?, ?>) root).get("services");
        if (!(servicesNode instanceof Map)) {
            return new ArrayList<>();
        }

        final List<Service> services = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) servicesNode).entrySet()) {
            final String name = String.valueOf(entry.getKey());
            final Map<?, ?> body = entry.getValue() instanceof Map ? (Map<?, ?>) entry.getValue() : new LinkedHashMap<>();
            services.add(new Service(name, memLimitMbOf(body), envFilesOf(body),
                                     dependsOnOf(body), portsOf(body), body.containsKey("healthcheck")));
        }
        return services;
    }

    private static Integer memLimitMbOf(Map<?, ?> body) {
        final Integer shortForm = parseMemoryToMb(body.get("mem_limit"));
        if (shortForm != null) {
            return shortForm;
        }
        // deploy.resources.limits.memory
        final Object deploy = body.get("deploy");
        if (deploy instanceof Map) {
            final Object resources = ((Map<?, ?>) deploy).get("resources");
            if (resources instanceof Map) {
                final Object limits = ((Map<?, ?>) resources).get("limits");
                if (limits instanceof Map) {
                    return parseMemoryToMb(((Map<?, ?>) limits).get("memory"));
                }
            }
        }
        return null;
    }

    private static List<String> envFilesOf(Map<?, ?> body) {
        final List<String> result = new ArrayList<>();
        final Object envFile = body.get("env_file");
        if (envFile instanceof String) {
            result.add(((String) envFile).trim());
        } else if (envFile instanceof List) {
            for (Object each : (List<?>) envFile) {
                if (each instanceof String) {
                    result.add(((String) each).trim());
                } else if (each instanceof Map) {
                    // long form: { path: ./x.env, required: false }
                    final Object path = ((Map<?, ?>) each).get("path");
                    if (path instanceof String) {
                        result.add(((String) path).trim());
                    }
                }
            }
        }
        return result;
    }

    private static List<String> dependsOnOf(Map<?, ?> body) {
        final List<String> result = new ArrayList<>();
        final Object dependsOn = body.get("depends_on");
        if (dependsOn instanceof List) {
            for (Object each : (List<?>) dependsOn) {
                result.add(String.valueOf(each));
            }
        } else if (dependsOn instanceof Map) {
            // long form: { db: { condition: service_healthy } }
            for (Object key : ((Map<?, ?>) dependsOn).keySet()) {
                result.add(String.valueOf(key));
            }
        }
        return result;
    }

    private static List<Integer> portsOf(Map<?, ?> body) {
        final List<Integer> result = new ArrayList<>();
        final Object ports = body.get("ports");
        if (ports instanceof List) {
            for (Object each : (List<?>) ports) {
                final Integer port = parsePublishedPort(each);
                if (port != null) {
                    result.add(port);
                }
            }
        }
        return result;
    }

    // --- pure helpers (unit-tested) -------------------------------------------------------------

    /**
     * Converts a docker memory value to whole megabytes. Accepts a raw byte count (number or
     * digit string) or a suffixed string using docker's 1024-based units: {@code b}, {@code k}/kb,
     * {@code m}/mb, {@code g}/gb (case-insensitive). Returns null for null/blank/unparseable input.
     */
    @Nullable
    static Integer parseMemoryToMb(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return (int) (((Number) value).longValue() / (1024L * 1024L));
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        long multiplier = 1;
        if (text.endsWith("gb") || text.endsWith("g")) {
            multiplier = 1024L * 1024L * 1024L;
            text = text.substring(0, text.length() - (text.endsWith("gb") ? 2 : 1));
        } else if (text.endsWith("mb") || text.endsWith("m")) {
            multiplier = 1024L * 1024L;
            text = text.substring(0, text.length() - (text.endsWith("mb") ? 2 : 1));
        } else if (text.endsWith("kb") || text.endsWith("k")) {
            multiplier = 1024L;
            text = text.substring(0, text.length() - (text.endsWith("kb") ? 2 : 1));
        } else if (text.endsWith("b")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            final long bytes = (long) (Double.parseDouble(text.trim()) * multiplier);
            return (int) (bytes / (1024L * 1024L));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts the published (host) port from a compose {@code ports} entry. Handles the string
     * short forms ({@code "3000"}, {@code "3000:3000"}, {@code "127.0.0.1:8080:80"},
     * {@code "3000-3005:3000-3005"}, optional {@code /tcp} suffix) and the long map form with a
     * {@code published} key. Returns null when no host port can be determined.
     */
    @Nullable
    static Integer parsePublishedPort(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Map) {
            final Object published = ((Map<?, ?>) value).get("published");
            return published == null ? null : firstPortNumber(String.valueOf(published));
        }
        String text = String.valueOf(value).trim();
        final int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash); // drop the /tcp or /udp protocol
        }
        final String[] parts = text.split(":");
        // "target" -> parts[0]; "published:target" -> parts[0]; "ip:published:target" -> parts[1]
        final String hostPart = parts.length == 3 ? parts[1] : parts[0];
        return firstPortNumber(hostPart);
    }

    /** First integer of a port token, unwrapping a {@code 3000-3005} range to its start. */
    @Nullable
    private static Integer firstPortNumber(String token) {
        String value = token.trim();
        final int dash = value.indexOf('-');
        if (dash > 0) {
            value = value.substring(0, dash);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Orders service names so every dependency comes before the services that depend on it
     * (depth-first topological sort). Unknown dependencies are ignored and dependency cycles are
     * broken gracefully, so the result always contains every input name exactly once.
     */
    @NotNull
    public static List<String> topologicalOrder(@NotNull List<String> names,
                                                @NotNull Map<String, List<String>> dependsOn) {
        final Set<String> known = new LinkedHashSet<>(names);
        final List<String> ordered = new ArrayList<>();
        final Set<String> done = new LinkedHashSet<>();
        final Set<String> visiting = new LinkedHashSet<>();
        for (String name : names) {
            visit(name, known, dependsOn, done, visiting, ordered);
        }
        return ordered;
    }

    private static void visit(String name, Set<String> known, Map<String, List<String>> dependsOn,
                              Set<String> done, Set<String> visiting, List<String> ordered) {
        if (done.contains(name) || !known.contains(name) || !visiting.add(name)) {
            return; // already placed, unknown, or a cycle we refuse to follow again
        }
        final List<String> deps = dependsOn.get(name);
        if (deps != null) {
            for (String dep : deps) {
                visit(dep, known, dependsOn, done, visiting, ordered);
            }
        }
        visiting.remove(name);
        if (done.add(name)) {
            ordered.add(name);
        }
    }
}
