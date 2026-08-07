package io.github.pixelcodes.lanes

import com.intellij.execution.process.NopProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Registry behaviour against a real (headless) [com.intellij.openapi.project.Project].
 *
 * The pure-unit tests can only reach [LanesProcessRegistry.dropEntriesOf]; everything around it -
 * registering, the termination listener that unregisters on its own, the per-project isolation -
 * needs a Project, which is why it went untested and why the 1.0.12 crash reached users. This is
 * the fixture that closes that gap; [BasePlatformTestCase] gives a disposable in-memory project.
 */
class LanesProcessRegistryPlatformTest : BasePlatformTestCase() {

    /**
     * The registry is a process-wide singleton keyed by project, and it only drops an entry when
     * that process terminates - nothing clears it per project. The fixture reuses one project
     * across test methods, so an app left running by one test would be visible to the next.
     */
    override fun tearDown() {
        try {
            for (entry in LanesProcessRegistry.getEntries(project)) {
                LanesProcessRegistry.unregister(project, entry.handler)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun register(name: String, handler: NopProcessHandler) {
        LanesProcessRegistry.register(
            project, "group", name, handler, null, null, null, null,
            true, null, 90, false, 0)
    }

    fun testRegisteredApplicationIsListedForItsProject() {
        val handler = NopProcessHandler()

        register("api", handler)

        val entries = LanesProcessRegistry.getEntries(project)
        assertEquals(1, entries.size)
        assertEquals("api", entries[0].appName)
        assertSame(handler, entries[0].handler)
    }

    fun testTerminationUnregistersTheApplicationOnItsOwn() {
        val handler = NopProcessHandler()
        register("api", handler)

        // the registry installs a listener at register() time; this is what fires on a real exit
        handler.startNotify()
        handler.destroyProcess()
        handler.waitFor()

        assertTrue("a terminated app must not stay in the registry",
                   LanesProcessRegistry.getEntries(project).isEmpty())
    }

    fun testUnregisterRemovesOnlyTheGivenApplication() {
        val kept = NopProcessHandler()
        val removed = NopProcessHandler()
        register("kept", kept)
        register("removed", removed)

        LanesProcessRegistry.unregister(project, removed)

        val entries = LanesProcessRegistry.getEntries(project)
        assertEquals(1, entries.size)
        assertEquals("kept", entries[0].appName)
    }

    /**
     * The 1.0.12 crash: stopping a group terminates several apps at once and the platform fires
     * their callbacks on different threads. This drives the real registry, not just the helper.
     */
    fun testTerminatingManyApplicationsAtOnceDoesNotThrow() {
        val handlers = (1..16).map { NopProcessHandler() }
        for ((index, handler) in handlers.withIndex()) {
            register("app$index", handler)
            handler.startNotify()
        }

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val threads = handlers.map { handler ->
            Thread {
                try {
                    handler.destroyProcess()
                    handler.waitFor()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }

        assertNull("concurrent termination must not throw: ${failure.get()}", failure.get())
        assertTrue("every terminated app should be gone",
                   LanesProcessRegistry.getEntries(project).isEmpty())
    }

    fun testMetadataSurvivesTerminationForTheMonitor() {
        val handler = NopProcessHandler()
        register("api", handler)
        handler.startNotify()
        handler.destroyProcess()
        handler.waitFor()

        // LAST_BY_NAME deliberately outlives the process: the monitor still labels an app that was
        // restarted individually with the group it belongs to
        val metadata = LanesProcessRegistry.findMetadataByName(project, "api")
        assertNotNull("launch metadata must outlive the process", metadata)
        assertEquals("group", metadata!!.lanesName)
    }
}
