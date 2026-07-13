package io.github.welingtonmonteiro.lanes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.welingtonmonteiro.lanes.RunConfigurationHelper.ReadyCondition

class LanesMonitorPanelTest {

    private fun rowWithStatus(status: String): LanesMonitorPanel.Row {
        return LanesMonitorPanel.Row(
            "app", null, "-", "-", null, null, null, "123", "-", "1m", status,
            "n/a", "n/a", "n/a", DoubleArray(0), emptyList())
    }

    private fun rowWith(lanesName: String, envFileName: String, vararg profiles: String): LanesMonitorPanel.Row {
        return LanesMonitorPanel.Row(
            "app", null, lanesName, envFileName, null, null, null, "123", "-", "1m", "running",
            "n/a", "n/a", "n/a", DoubleArray(0), profiles.toList())
    }

    @Test
    fun unhealthyRowsKeepsOnlyDownRows() {
        val down = rowWithStatus("down")
        val healthy = rowWithStatus("healthy")
        val none = rowWithStatus("-")
        val result = LanesMonitorPanel.unhealthyRows(listOf(down, healthy, none))
        assertEquals(1, result.size)
        assertSame(down, result[0])
    }

    @Test
    fun unhealthyRowsIsEmptyWhenAllHealthy() {
        assertTrue(LanesMonitorPanel.unhealthyRows(
            listOf(rowWithStatus("healthy"), rowWithStatus("-"))).isEmpty())
    }

    @Test
    fun parsePortsReadsCommaSeparatedNumbers() {
        assertEquals(listOf(3000, 8080), LanesMonitorPanel.parsePorts("3000, 8080"))
        assertEquals(listOf(5432), LanesMonitorPanel.parsePorts("5432"))
    }

    @Test
    fun parsePortsIgnoresPlaceholdersAndBlanks() {
        assertTrue(LanesMonitorPanel.parsePorts("-").isEmpty())
        assertTrue(LanesMonitorPanel.parsePorts("n/a").isEmpty())
        assertTrue(LanesMonitorPanel.parsePorts("").isEmpty())
        assertTrue(LanesMonitorPanel.parsePorts(null).isEmpty())
    }

    @Test
    fun parsePortsSkipsNonNumericTokens() {
        val ports = LanesMonitorPanel.parsePorts("3000, oops, 9229")
        assertEquals(listOf(3000, 9229), ports)
    }

    @Test
    fun urlForPortTargetsLocalhost() {
        assertEquals("http://localhost:3000", LanesMonitorPanel.urlForPort(3000))
    }

    @Test
    fun statusLabelIsRunningWithoutAReadinessCondition() {
        assertEquals("running", LanesMonitorPanel.statusLabel(ReadyCondition.Type.NONE, false))
        assertEquals("running", LanesMonitorPanel.statusLabel(ReadyCondition.Type.LOG, false))
    }

    @Test
    fun statusLabelMapsPortAndHttpChecksToHealthyOrDown() {
        assertEquals("healthy", LanesMonitorPanel.statusLabel(ReadyCondition.Type.PORT, true))
        assertEquals("down", LanesMonitorPanel.statusLabel(ReadyCondition.Type.PORT, false))
        assertEquals("healthy", LanesMonitorPanel.statusLabel(ReadyCondition.Type.HTTP, true))
        assertEquals("down", LanesMonitorPanel.statusLabel(ReadyCondition.Type.HTTP, false))
    }

    @Test
    fun visibleColumnsDropsHiddenAndKeepsOrder() {
        val all = listOf("Name", "Env", "PID", "Ports", "CPU %")
        val visible = LanesMonitorPanel.visibleColumns(all, setOf("PID", "CPU %"))
        assertEquals(listOf("Name", "Env", "Ports"), visible)
    }

    @Test
    fun visibleColumnsWithNothingHiddenReturnsEverything() {
        val all = listOf("Name", "Env", "PID")
        assertEquals(all, LanesMonitorPanel.visibleColumns(all, emptySet()))
    }

    @Test
    fun selectionIndicesMatchesEverySelectedRowNotJustOne() {
        // a refresh rebuilds the rows: the whole multi-selection must be restored, not a single row
        val rowKeys = listOf("a", "b", "c", "d")
        val indices = LanesMonitorPanel.selectionIndices(rowKeys, setOf("a", "c", "d"))
        assertEquals(listOf(0, 2, 3), indices)
    }

    @Test
    fun selectionIndicesSkipsKeysThatAreNoLongerPresent() {
        // an app that stopped between refreshes simply drops out of the restored selection
        val rowKeys = listOf("a", "b")
        val indices = LanesMonitorPanel.selectionIndices(rowKeys, setOf("b", "gone"))
        assertEquals(listOf(1), indices)
    }

    @Test
    fun selectionIndicesIsEmptyWhenNothingWasSelected() {
        assertTrue(LanesMonitorPanel.selectionIndices(listOf("a", "b"), emptySet()).isEmpty())
    }

    @Test
    fun switchableEnvProfilesUnionsOnlyGroupsWithMoreThanOne() {
        val rows = listOf(
            rowWith("A", "com.env", "/p/com.env", "/p/def.env"),   // 2 profiles -> offered
            rowWith("A", "com.env", "/p/com.env", "/p/def.env"),   // same group, duplicates collapse
            rowWith("B", "only.env", "/p/only.env"))                // 1 profile -> ignored
        assertEquals(listOf("/p/com.env", "/p/def.env"), LanesMonitorPanel.switchableEnvProfiles(rows))
    }

    @Test
    fun switchableEnvProfilesEmptyWhenNoGroupHasChoices() {
        assertTrue(LanesMonitorPanel.switchableEnvProfiles(
            listOf(rowWith("A", "only.env", "/p/only.env"))).isEmpty())
    }

    @Test
    fun groupsWithProfileReturnsOnlyGroupsThatOfferIt() {
        val rows = listOf(
            rowWith("A", "com.env", "/p/com.env", "/p/def.env"),
            rowWith("B", "com.env", "/p/com.env", "/p/qa.env"),
            rowWith("C", "only.env", "/p/only.env"))  // single profile, never a target
        assertEquals(linkedSetOf("A", "B"), LanesMonitorPanel.groupsWithProfile(rows, "/p/com.env"))
        assertEquals(linkedSetOf("A"), LanesMonitorPanel.groupsWithProfile(rows, "/p/def.env"))
    }
}
