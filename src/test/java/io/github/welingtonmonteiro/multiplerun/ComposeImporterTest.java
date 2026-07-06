package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ComposeImporterTest {

    @Test
    public void parseMemoryHandlesDockerSuffixesAndRawBytes() {
        assertEquals(Integer.valueOf(512), ComposeImporter.parseMemoryToMb("512m"));
        assertEquals(Integer.valueOf(512), ComposeImporter.parseMemoryToMb("512MB"));
        assertEquals(Integer.valueOf(1024), ComposeImporter.parseMemoryToMb("1g"));
        assertEquals(Integer.valueOf(1536), ComposeImporter.parseMemoryToMb("1.5g"));
        assertEquals(Integer.valueOf(1024), ComposeImporter.parseMemoryToMb("1073741824")); // bytes
        assertEquals(Integer.valueOf(512), ComposeImporter.parseMemoryToMb(536870912L));     // number bytes
        assertNull(ComposeImporter.parseMemoryToMb(null));
        assertNull(ComposeImporter.parseMemoryToMb("  "));
        assertNull(ComposeImporter.parseMemoryToMb("not-a-size"));
    }

    @Test
    public void parsePublishedPortHandlesAllShortForms() {
        assertEquals(Integer.valueOf(3000), ComposeImporter.parsePublishedPort("3000"));
        assertEquals(Integer.valueOf(3000), ComposeImporter.parsePublishedPort("3000:3000"));
        assertEquals(Integer.valueOf(8080), ComposeImporter.parsePublishedPort("127.0.0.1:8080:80"));
        assertEquals(Integer.valueOf(3000), ComposeImporter.parsePublishedPort("3000-3005:3000-3005"));
        assertEquals(Integer.valueOf(53), ComposeImporter.parsePublishedPort("53:53/udp"));
        final Map<String, Object> longForm = new HashMap<>();
        longForm.put("published", 8080);
        longForm.put("target", 80);
        assertEquals(Integer.valueOf(8080), ComposeImporter.parsePublishedPort(longForm));
        assertNull(ComposeImporter.parsePublishedPort(null));
    }

    @Test
    public void topologicalOrderPlacesDependenciesFirst() {
        final Map<String, List<String>> deps = new HashMap<>();
        deps.put("a", Arrays.asList("b"));
        deps.put("b", Arrays.asList("c"));
        final List<String> order = ComposeImporter.topologicalOrder(Arrays.asList("a", "b", "c"), deps);
        assertEquals(Arrays.asList("c", "b", "a"), order);
    }

    @Test
    public void topologicalOrderSurvivesCyclesAndUnknownDeps() {
        final Map<String, List<String>> deps = new HashMap<>();
        deps.put("a", Arrays.asList("b", "ghost")); // ghost is not in the name set
        deps.put("b", Arrays.asList("a"));           // cycle a <-> b
        final List<String> order = ComposeImporter.topologicalOrder(Arrays.asList("a", "b"), deps);
        assertEquals(2, order.size());
        assertTrue(order.contains("a"));
        assertTrue(order.contains("b"));
        assertFalse(order.contains("ghost"));
    }

    @Test
    public void parseReadsTheRelevantServiceFields() {
        final String yaml =
                "services:\n" +
                "  api:\n" +
                "    mem_limit: 512m\n" +
                "    env_file: .env.api\n" +
                "    depends_on:\n" +
                "      - db\n" +
                "    ports:\n" +
                "      - \"3000:3000\"\n" +
                "    healthcheck:\n" +
                "      test: [\"CMD\", \"curl\", \"localhost:3000\"]\n" +
                "  db:\n" +
                "    deploy:\n" +
                "      resources:\n" +
                "        limits:\n" +
                "          memory: 1g\n" +
                "    env_file:\n" +
                "      - .env.shared\n" +
                "      - .env.db\n" +
                "    ports:\n" +
                "      - \"5432:5432\"\n";

        final List<ComposeImporter.Service> services = ComposeImporter.parse(yaml);
        assertEquals(2, services.size());

        final ComposeImporter.Service api = services.get(0);
        assertEquals("api", api.name);
        assertEquals(Integer.valueOf(512), api.memLimitMb);
        assertEquals(Arrays.asList(".env.api"), api.envFiles);
        assertEquals(Arrays.asList("db"), api.dependsOn);
        assertEquals(Arrays.asList(3000), api.ports);
        assertTrue(api.hasHealthcheck);

        final ComposeImporter.Service db = services.get(1);
        assertEquals("db", db.name);
        assertEquals(Integer.valueOf(1024), db.memLimitMb); // from deploy.resources.limits.memory
        assertEquals(Arrays.asList(".env.shared", ".env.db"), db.envFiles);
        assertEquals(Arrays.asList(5432), db.ports);
        assertFalse(db.hasHealthcheck);
    }

    @Test
    public void parseReadsDependsOnMapForm() {
        final String yaml =
                "services:\n" +
                "  web:\n" +
                "    depends_on:\n" +
                "      db:\n" +
                "        condition: service_healthy\n" +
                "  db: {}\n";
        final List<ComposeImporter.Service> services = ComposeImporter.parse(yaml);
        assertEquals(2, services.size());
        assertEquals(Arrays.asList("db"), services.get(0).dependsOn);
    }

    @Test
    public void parseReturnsEmptyForBlankOrServiceless() {
        assertTrue(ComposeImporter.parse(null).isEmpty());
        assertTrue(ComposeImporter.parse("   ").isEmpty());
        assertTrue(ComposeImporter.parse("version: '3'\n").isEmpty());
    }
}
