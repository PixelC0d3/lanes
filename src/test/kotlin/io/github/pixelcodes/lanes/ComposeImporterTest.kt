package io.github.pixelcodes.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeImporterTest {

    @Test
    fun parseMemoryHandlesDockerSuffixesAndRawBytes() {
        assertEquals(512, ComposeImporter.parseMemoryToMb("512m"))
        assertEquals(512, ComposeImporter.parseMemoryToMb("512MB"))
        assertEquals(1024, ComposeImporter.parseMemoryToMb("1g"))
        assertEquals(1536, ComposeImporter.parseMemoryToMb("1.5g"))
        assertEquals(1024, ComposeImporter.parseMemoryToMb("1073741824")) // bytes
        assertEquals(512, ComposeImporter.parseMemoryToMb(536870912L))    // number bytes
        assertNull(ComposeImporter.parseMemoryToMb(null))
        assertNull(ComposeImporter.parseMemoryToMb("  "))
        assertNull(ComposeImporter.parseMemoryToMb("not-a-size"))
    }

    @Test
    fun parsePublishedPortHandlesAllShortForms() {
        assertEquals(3000, ComposeImporter.parsePublishedPort("3000"))
        assertEquals(3000, ComposeImporter.parsePublishedPort("3000:3000"))
        assertEquals(8080, ComposeImporter.parsePublishedPort("127.0.0.1:8080:80"))
        assertEquals(3000, ComposeImporter.parsePublishedPort("3000-3005:3000-3005"))
        assertEquals(53, ComposeImporter.parsePublishedPort("53:53/udp"))
        val longForm = HashMap<String, Any>()
        longForm["published"] = 8080
        longForm["target"] = 80
        assertEquals(8080, ComposeImporter.parsePublishedPort(longForm))
        assertNull(ComposeImporter.parsePublishedPort(null))
    }

    @Test
    fun topologicalOrderPlacesDependenciesFirst() {
        val deps = HashMap<String, List<String>>()
        deps["a"] = listOf("b")
        deps["b"] = listOf("c")
        val order = ComposeImporter.topologicalOrder(listOf("a", "b", "c"), deps)
        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun topologicalOrderSurvivesCyclesAndUnknownDeps() {
        val deps = HashMap<String, List<String>>()
        deps["a"] = listOf("b", "ghost") // ghost is not in the name set
        deps["b"] = listOf("a")          // cycle a <-> b
        val order = ComposeImporter.topologicalOrder(listOf("a", "b"), deps)
        assertEquals(2, order.size)
        assertTrue(order.contains("a"))
        assertTrue(order.contains("b"))
        assertFalse(order.contains("ghost"))
    }

    @Test
    fun parseReadsTheRelevantServiceFields() {
        val yaml = """
            services:
              api:
                mem_limit: 512m
                env_file: .env.api
                depends_on:
                  - db
                ports:
                  - "3000:3000"
                healthcheck:
                  test: ["CMD", "curl", "localhost:3000"]
              db:
                deploy:
                  resources:
                    limits:
                      memory: 1g
                env_file:
                  - .env.shared
                  - .env.db
                ports:
                  - "5432:5432"
            """.trimIndent()

        val services = ComposeImporter.parse(yaml)
        assertEquals(2, services.size)

        val api = services[0]
        assertEquals("api", api.name)
        assertEquals(512, api.memLimitMb)
        assertEquals(listOf(".env.api"), api.envFiles)
        assertEquals(listOf("db"), api.dependsOn)
        assertEquals(listOf(3000), api.ports)
        assertTrue(api.hasHealthcheck)

        val db = services[1]
        assertEquals("db", db.name)
        assertEquals(1024, db.memLimitMb) // from deploy.resources.limits.memory
        assertEquals(listOf(".env.shared", ".env.db"), db.envFiles)
        assertEquals(listOf(5432), db.ports)
        assertFalse(db.hasHealthcheck)
    }

    @Test
    fun parseReadsDependsOnMapForm() {
        val yaml = """
            services:
              web:
                depends_on:
                  db:
                    condition: service_healthy
              db: {}
            """.trimIndent()
        val services = ComposeImporter.parse(yaml)
        assertEquals(2, services.size)
        assertEquals(listOf("db"), services[0].dependsOn)
    }

    @Test
    fun parseReturnsEmptyForBlankOrServiceless() {
        assertTrue(ComposeImporter.parse(null).isEmpty())
        assertTrue(ComposeImporter.parse("   ").isEmpty())
        assertTrue(ComposeImporter.parse("version: '3'\n").isEmpty())
    }
}
