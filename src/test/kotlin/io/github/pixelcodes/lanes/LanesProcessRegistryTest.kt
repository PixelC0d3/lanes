package io.github.pixelcodes.lanes

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removal of registry entries. Stopping a Lanes group terminates every app at once and the platform
 * fires the termination callbacks on several threads, so unregistering has to be safe concurrently.
 */
class LanesProcessRegistryTest {

    private fun entryFor(handler: ProcessHandler): LanesProcessRegistry.Entry =
        LanesProcessRegistry.Entry("group", "app", handler, null, null, null, null,
                                   true, null, 90, false, 0)

    @Test
    fun dropsOnlyTheEntriesOfTheGivenHandler() {
        val target = NopProcessHandler()
        val other = NopProcessHandler()
        val entries = CopyOnWriteArrayList(listOf(entryFor(target), entryFor(other), entryFor(target)))

        assertTrue(LanesProcessRegistry.dropEntriesOf(entries, target))

        assertEquals(1, entries.size)
        assertSameHandler(other, entries[0])
    }

    @Test
    fun droppingAnUnknownHandlerChangesNothing() {
        val entries = CopyOnWriteArrayList(listOf(entryFor(NopProcessHandler())))

        assertFalse(LanesProcessRegistry.dropEntriesOf(entries, NopProcessHandler()))

        assertEquals(1, entries.size)
    }

    /**
     * Reproduces the crash of 1.0.11: with Kotlin's `removeAll { }` this threw
     * ArrayIndexOutOfBoundsException, because that walks a CopyOnWriteArrayList by index while
     * another thread is shrinking it.
     */
    @Test
    fun concurrentRemovalsDoNotThrow() {
        val handlers = (1..24).map { NopProcessHandler() }
        val entries = CopyOnWriteArrayList(handlers.map { entryFor(it) })
        val failure = AtomicReference<Throwable>()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        try {
            val done = CountDownLatch(handlers.size)
            for (handler in handlers) {
                pool.execute {
                    try {
                        start.await()
                        LanesProcessRegistry.dropEntriesOf(entries, handler)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown() // release every thread at once, so the removals really overlap
            assertTrue("removals did not finish in time", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertNull("concurrent unregister must not throw: ${failure.get()}", failure.get())
        assertTrue("every entry should have been removed", entries.isEmpty())
    }

    private fun assertSameHandler(expected: ProcessHandler, entry: LanesProcessRegistry.Entry) {
        assertTrue("unexpected entry left in the registry", entry.handler === expected)
    }
}
