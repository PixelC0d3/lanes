package io.github.welingtonmonteiro.multiplerun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jdom.Element;
import org.junit.Test;

public class MultirunPresetTest {

    @Test
    public void writeThenReadRoundTrip() {
        final Map<String, MultirunRunConfiguration.Preset> presets = new LinkedHashMap<>();
        final Set<String> disabled = new LinkedHashSet<>(Arrays.asList("web", "worker"));
        presets.put("backend only", new MultirunRunConfiguration.Preset("backend only", disabled, "/proj/.env.dev"));
        presets.put("full stack", new MultirunRunConfiguration.Preset("full stack", new LinkedHashSet<>(), ""));

        final Element root = new Element("configuration");
        MultirunRunConfiguration.writePresets(root, presets);
        final Map<String, MultirunRunConfiguration.Preset> read = MultirunRunConfiguration.readPresets(root);

        assertEquals(2, read.size());
        final MultirunRunConfiguration.Preset backend = read.get("backend only");
        assertNotNull(backend);
        assertEquals("/proj/.env.dev", backend.envFilePath);
        assertEquals(2, backend.disabledApps.size());
        assertTrue(backend.disabledApps.contains("web"));
        assertTrue(backend.disabledApps.contains("worker"));

        final MultirunRunConfiguration.Preset full = read.get("full stack");
        assertNotNull(full);
        assertEquals("", full.envFilePath);
        assertTrue(full.disabledApps.isEmpty());
    }

    @Test
    public void presetConstructorDefensivelyCopiesAndNormalizes() {
        final Set<String> disabled = new LinkedHashSet<>(Collections.singletonList("a"));
        final MultirunRunConfiguration.Preset preset = new MultirunRunConfiguration.Preset("x", disabled, null);
        disabled.add("b");
        // the preset keeps its own copy, unaffected by later mutation of the source set
        assertEquals(1, preset.disabledApps.size());
        // a null env file path is normalized to empty
        assertEquals("", preset.envFilePath);
    }

    @Test
    public void readIgnoresPresetsWithoutAName() {
        final Element root = new Element("configuration");
        root.addContent(new Element("preset")); // no name attribute
        assertTrue(MultirunRunConfiguration.readPresets(root).isEmpty());
    }
}
