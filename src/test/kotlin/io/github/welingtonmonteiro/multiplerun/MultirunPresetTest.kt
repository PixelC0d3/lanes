package io.github.welingtonmonteiro.multiplerun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.jdom.Element

class MultirunPresetTest {

    @Test
    fun writeThenReadRoundTrip() {
        val presets = LinkedHashMap<String, MultirunRunConfiguration.Preset>()
        val disabled = linkedSetOf("web", "worker")
        presets["backend only"] = MultirunRunConfiguration.Preset("backend only", disabled, "/proj/.env.dev")
        presets["full stack"] = MultirunRunConfiguration.Preset("full stack", LinkedHashSet(), "")

        val root = Element("configuration")
        MultirunRunConfiguration.writePresets(root, presets)
        val read = MultirunRunConfiguration.readPresets(root)

        assertEquals(2, read.size)
        val backend = read["backend only"]
        assertNotNull(backend)
        assertEquals("/proj/.env.dev", backend!!.envFilePath)
        assertEquals(2, backend.disabledApps.size)
        assertTrue(backend.disabledApps.contains("web"))
        assertTrue(backend.disabledApps.contains("worker"))

        val full = read["full stack"]
        assertNotNull(full)
        assertEquals("", full!!.envFilePath)
        assertTrue(full.disabledApps.isEmpty())
    }

    @Test
    fun presetConstructorDefensivelyCopiesAndNormalizes() {
        val disabled = linkedSetOf("a")
        val preset = MultirunRunConfiguration.Preset("x", disabled, null)
        disabled.add("b")
        // the preset keeps its own copy, unaffected by later mutation of the source set
        assertEquals(1, preset.disabledApps.size)
        // a null env file path is normalized to empty
        assertEquals("", preset.envFilePath)
    }

    @Test
    fun readIgnoresPresetsWithoutAName() {
        val root = Element("configuration")
        root.addContent(Element("preset")) // no name attribute
        assertTrue(MultirunRunConfiguration.readPresets(root).isEmpty())
    }
}
