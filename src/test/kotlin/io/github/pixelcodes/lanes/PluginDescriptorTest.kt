package io.github.pixelcodes.lanes

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules the JetBrains Marketplace enforces on `plugin.xml` when a build is uploaded.
 *
 * These are checked server side, at upload time, so breaking one costs a released version: it
 * happened twice in a row (1.0.9 and 1.0.10 were both rejected with "The plugin description must
 * start with Latin characters and have at least 40 characters" after an emoji was moved to the
 * front of the description). Catching it here costs milliseconds instead.
 */
class PluginDescriptorTest {

    /** How many leading characters of the rendered description the Marketplace inspects. */
    private val latinPrefixLength = 40

    private fun pluginXml(): String {
        val stream = javaClass.getResourceAsStream("/META-INF/plugin.xml")
        assertNotNull("plugin.xml must be on the test classpath", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** The text a reader actually sees: CDATA payload with HTML comments and tags stripped. */
    private fun renderedText(element: String): String {
        val body = Regex("<$element>(.*?)</$element>", RegexOption.DOT_MATCHES_ALL)
            .find(pluginXml())?.groupValues?.get(1)
        assertNotNull("plugin.xml must declare <$element>", body)
        val cdata = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
            .find(body!!)?.groupValues?.get(1) ?: body
        val withoutComments = cdata.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val withoutTags = withoutComments.replace(Regex("<[^>]+>"), "")
        return withoutTags.replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun descriptionStartsWithPlainLatinText() {
        val start = renderedText("description").take(latinPrefixLength)

        val offending = start.withIndex().filter { (_, c) -> c.code > 127 }
        assertTrue(
            "the first $latinPrefixLength characters of the description must be plain Latin - " +
                "Marketplace rejects the upload otherwise. Offending: $offending in \"$start\"",
            offending.isEmpty())
    }

    @Test
    fun descriptionIsLongEnough() {
        // the same Marketplace rule also demands at least 40 characters of real text
        assertTrue("the description must have at least $latinPrefixLength characters",
                   renderedText("description").length >= latinPrefixLength)
    }

    @Test
    fun descriptionHasNoCommentLeftInsideTheCdata() {
        // text inside CDATA is not XML-parsed, so an HTML comment there is literal markup to some
        // readers - keep the dev notes outside the block (they live above <description>)
        val body = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
            .find(pluginXml())?.groupValues?.get(1)
        val cdata = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
            .find(body!!)?.groupValues?.get(1) ?: ""

        assertTrue("move HTML comments out of the description's CDATA block",
                   !cdata.contains("<!--"))
    }

    @Test
    fun changeNotesStartWithPlainLatinText() {
        // the same validator applies to the change notes shown on the plugin page
        val start = renderedText("change-notes").take(latinPrefixLength)

        assertTrue("the change notes must start with plain Latin text, got \"$start\"",
                   start.none { it.code > 127 })
    }
}
