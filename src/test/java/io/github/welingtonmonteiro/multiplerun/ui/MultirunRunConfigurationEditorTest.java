package io.github.welingtonmonteiro.multiplerun.ui;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class MultirunRunConfigurationEditorTest {

    @Test
    public void addEnvProfilesAppendsNewPathsPreservingOrder() {
        assertEquals(Arrays.asList("/p/com.env", "/p/def.env", "/p/qa.env"),
                     MultirunRunConfigurationEditor.addEnvProfiles(
                             Collections.singletonList("/p/com.env"),
                             Arrays.asList("/p/def.env", "/p/qa.env")));
    }

    @Test
    public void addEnvProfilesSkipsDuplicatesAndBlanks() {
        assertEquals(Arrays.asList("/p/com.env", "/p/def.env"),
                     MultirunRunConfigurationEditor.addEnvProfiles(
                             Arrays.asList("/p/com.env", "/p/def.env"),
                             Arrays.asList("/p/com.env", "", "/p/def.env")));
    }

    @Test
    public void addEnvProfilesFromAnEmptyListKeepsEveryPicked() {
        assertEquals(Arrays.asList("/p/a.env", "/p/b.env"),
                     MultirunRunConfigurationEditor.addEnvProfiles(
                             Collections.emptyList(), Arrays.asList("/p/a.env", "/p/b.env")));
    }
}
