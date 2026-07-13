package io.github.pixelcodes.lanes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LanesRunConfigurationEditorTest {

    @Test
    fun addEnvProfilesAppendsNewPathsPreservingOrder() {
        assertEquals(listOf("/p/com.env", "/p/def.env", "/p/qa.env"),
                     LanesRunConfigurationEditor.addEnvProfiles(
                         listOf("/p/com.env"),
                         listOf("/p/def.env", "/p/qa.env")))
    }

    @Test
    fun addEnvProfilesSkipsDuplicatesAndBlanks() {
        assertEquals(listOf("/p/com.env", "/p/def.env"),
                     LanesRunConfigurationEditor.addEnvProfiles(
                         listOf("/p/com.env", "/p/def.env"),
                         listOf("/p/com.env", "", "/p/def.env")))
    }

    @Test
    fun addEnvProfilesFromAnEmptyListKeepsEveryPicked() {
        assertEquals(listOf("/p/a.env", "/p/b.env"),
                     LanesRunConfigurationEditor.addEnvProfiles(
                         emptyList(), listOf("/p/a.env", "/p/b.env")))
    }
}
