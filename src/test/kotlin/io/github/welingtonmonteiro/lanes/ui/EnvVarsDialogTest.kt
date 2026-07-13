package io.github.welingtonmonteiro.lanes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import java.util.AbstractMap

class EnvVarsDialogTest {

    private fun sample(): List<Map.Entry<String, String>> {
        return listOf(
            AbstractMap.SimpleImmutableEntry("DATABASE_URL", "postgres://localhost/app"),
            AbstractMap.SimpleImmutableEntry("API_KEY", "secret"),
            AbstractMap.SimpleImmutableEntry("NODE_ENV", "development"))
    }

    @Test
    fun blankFilterReturnsEveryEntryInOrder() {
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), ""))
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), "   "))
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), null))
    }

    @Test
    fun filterMatchesByNameCaseInsensitiveSubstring() {
        val result = EnvVarsDialog.filterByName(sample(), "api")
        assertEquals(1, result.size)
        assertEquals("API_KEY", result[0].key)
    }

    @Test
    fun filterMatchesSeveralAndKeepsOrder() {
        // every name contains "_" -> all three come back, in the original order
        val result = EnvVarsDialog.filterByName(sample(), "_")
        assertEquals(3, result.size)
        assertEquals("DATABASE_URL", result[0].key)
        assertEquals("API_KEY", result[1].key)
        assertEquals("NODE_ENV", result[2].key)
    }

    @Test
    fun filterMatchesOnNameNotValue() {
        // "secret" is a value, not a name -> no match
        assertTrue(EnvVarsDialog.filterByName(sample(), "secret").isEmpty())
    }

    @Test
    fun maskValueHidesNonEmptyValuesButKeepsEmptyEmpty() {
        assertEquals("", EnvVarsDialog.maskValue(""))
        assertEquals("", EnvVarsDialog.maskValue(null))
        assertFalse(EnvVarsDialog.maskValue("secret").isEmpty())
        assertFalse(EnvVarsDialog.maskValue("secret").contains("secret"))
    }
}
