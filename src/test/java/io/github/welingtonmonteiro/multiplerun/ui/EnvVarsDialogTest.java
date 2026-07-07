package io.github.welingtonmonteiro.multiplerun.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class EnvVarsDialogTest {

    private static Map.Entry<String, String> e(String k, String v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    private static List<Map.Entry<String, String>> sample() {
        final List<Map.Entry<String, String>> list = new ArrayList<>();
        list.add(e("DATABASE_URL", "postgres://localhost/app"));
        list.add(e("API_KEY", "secret"));
        list.add(e("NODE_ENV", "development"));
        return list;
    }

    @Test
    public void blankFilterReturnsEveryEntryInOrder() {
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), ""));
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), "   "));
        assertEquals(sample(), EnvVarsDialog.filterByName(sample(), null));
    }

    @Test
    public void filterMatchesByNameCaseInsensitiveSubstring() {
        final List<Map.Entry<String, String>> result = EnvVarsDialog.filterByName(sample(), "api");
        assertEquals(1, result.size());
        assertEquals("API_KEY", result.get(0).getKey());
    }

    @Test
    public void filterMatchesSeveralAndKeepsOrder() {
        // every name contains "_" -> all three come back, in the original order
        final List<Map.Entry<String, String>> result = EnvVarsDialog.filterByName(sample(), "_");
        assertEquals(3, result.size());
        assertEquals("DATABASE_URL", result.get(0).getKey());
        assertEquals("API_KEY", result.get(1).getKey());
        assertEquals("NODE_ENV", result.get(2).getKey());
    }

    @Test
    public void filterMatchesOnNameNotValue() {
        // "secret" is a value, not a name -> no match
        assertTrue(EnvVarsDialog.filterByName(sample(), "secret").isEmpty());
    }

    @Test
    public void maskValueHidesNonEmptyValuesButKeepsEmptyEmpty() {
        assertEquals("", EnvVarsDialog.maskValue(""));
        assertEquals("", EnvVarsDialog.maskValue(null));
        assertFalse(EnvVarsDialog.maskValue("secret").isEmpty());
        assertFalse(EnvVarsDialog.maskValue("secret").contains("secret"));
    }
}
