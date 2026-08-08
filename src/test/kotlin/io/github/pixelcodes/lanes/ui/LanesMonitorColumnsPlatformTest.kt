package io.github.pixelcodes.lanes.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Persistence of the monitor's hidden-column choice.
 *
 * It goes through the platform's `PropertiesComponent`, so it needs a real project. Worth pinning
 * because the column headers contain spaces and slashes ("Mem Usage / Limit"): storing them as one
 * joined string is the obvious implementation and a silent bug, which is why a list is used.
 */
class LanesMonitorColumnsPlatformTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            LanesMonitorPanel.saveHiddenColumns(project, emptySet())
        } finally {
            super.tearDown()
        }
    }

    fun testNothingIsHiddenByDefault() {
        assertTrue(LanesMonitorPanel.loadHiddenColumns(project).isEmpty())
    }

    fun testHiddenColumnsSurviveAReload() {
        LanesMonitorPanel.saveHiddenColumns(project, linkedSetOf("PID", "CPU %"))

        assertEquals(setOf("PID", "CPU %"), LanesMonitorPanel.loadHiddenColumns(project))
    }

    fun testColumnNamesWithSpacesAndSlashesRoundTrip() {
        // the case a joined string would corrupt
        val columns = linkedSetOf("Mem Usage / Limit", "Mem trend", "Mem %")

        LanesMonitorPanel.saveHiddenColumns(project, columns)

        assertEquals(columns, LanesMonitorPanel.loadHiddenColumns(project))
    }

    fun testShowingEveryColumnAgainClearsTheStoredValue() {
        LanesMonitorPanel.saveHiddenColumns(project, setOf("PID"))

        LanesMonitorPanel.saveHiddenColumns(project, emptySet())

        assertTrue(LanesMonitorPanel.loadHiddenColumns(project).isEmpty())
    }
}
