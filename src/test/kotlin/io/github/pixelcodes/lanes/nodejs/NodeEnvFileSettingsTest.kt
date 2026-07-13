package io.github.pixelcodes.lanes.nodejs

import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-configuration Node env-file settings: list normalization (trim / de-dup / drop
 * blanks, keeping order) and the XML round-trip used to persist the settings inside a run
 * configuration - including that empty settings write nothing, so a config without our data stays
 * clean and the leftover element is inert after the plugin is uninstalled.
 */
class NodeEnvFileSettingsTest {

    @Test
    fun normalizesProfilesTrimDedupDropBlankKeepOrder() {
        val settings = NodeEnvFileSettings(
            listOf("  a.env ", "b.env", "a.env", "", "   ", "c.env"),
            "b.env",
        )
        assertEquals(listOf("a.env", "b.env", "c.env"), settings.profiles)
        assertEquals("b.env", settings.active)
    }

    @Test
    fun activeIsTrimmed() {
        assertEquals("x.env", NodeEnvFileSettings(listOf("x.env"), "  x.env  ").active)
    }

    @Test
    fun isEmptyOnlyWhenNoProfilesAndBlankActive() {
        assertTrue(NodeEnvFileSettings(emptyList(), "").isEmpty)
        assertTrue(NodeEnvFileSettings(listOf("  "), "   ").isEmpty)
        assertFalse(NodeEnvFileSettings(listOf("a.env"), "").isEmpty)
        assertFalse(NodeEnvFileSettings(emptyList(), "a.env").isEmpty)
    }

    @Test
    fun xmlRoundTripPreservesActiveAndProfiles() {
        val original = NodeEnvFileSettings(listOf("dev.env", "prod.env"), "prod.env")
        val element = Element("extension")
        original.writeTo(element)

        val restored = NodeEnvFileSettings.readFrom(element)
        assertEquals(listOf("dev.env", "prod.env"), restored.profiles)
        assertEquals("prod.env", restored.active)
    }

    @Test
    fun emptySettingsWriteNothing() {
        val element = Element("extension")
        NodeEnvFileSettings.EMPTY.writeTo(element)
        assertNull("no active attribute", element.getAttributeValue("active"))
        assertTrue("no file children", element.getChildren("file").isEmpty())
    }

    @Test
    fun blankActiveIsNotWrittenButProfilesAre() {
        val element = Element("extension")
        NodeEnvFileSettings(listOf("only.env"), "").writeTo(element)
        assertNull(element.getAttributeValue("active"))
        assertEquals(1, element.getChildren("file").size)

        val restored = NodeEnvFileSettings.readFrom(element)
        assertEquals("", restored.active)
        assertEquals(listOf("only.env"), restored.profiles)
    }

    @Test
    fun withActiveKeepsProfilesChangesActive() {
        val base = NodeEnvFileSettings(listOf("dev.env", "prod.env"), "dev.env")
        val switched = base.withActive("prod.env")
        assertEquals(base.profiles, switched.profiles)
        assertEquals("prod.env", switched.active)
    }
}
