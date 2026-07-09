package io.github.welingtonmonteiro.multiplerun.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.github.welingtonmonteiro.multiplerun.RunConfigurationHelper.ReadyCondition;

public class MultirunMonitorPanelTest {

    private static MultirunMonitorPanel.Row rowWithStatus(String status) {
        return new MultirunMonitorPanel.Row(
                "app", null, "-", "-", null, null, null, "123", "-", "1m", status,
                "n/a", "n/a", "n/a", new double[0]);
    }

    @Test
    public void unhealthyRowsKeepsOnlyDownRows() {
        final MultirunMonitorPanel.Row down = rowWithStatus("down");
        final MultirunMonitorPanel.Row healthy = rowWithStatus("healthy");
        final MultirunMonitorPanel.Row none = rowWithStatus("-");
        final List<MultirunMonitorPanel.Row> result =
                MultirunMonitorPanel.unhealthyRows(Arrays.asList(down, healthy, none));
        assertEquals(1, result.size());
        assertSame(down, result.get(0));
    }

    @Test
    public void unhealthyRowsIsEmptyWhenAllHealthy() {
        assertTrue(MultirunMonitorPanel.unhealthyRows(
                Arrays.asList(rowWithStatus("healthy"), rowWithStatus("-"))).isEmpty());
    }

    @Test
    public void parsePortsReadsCommaSeparatedNumbers() {
        assertEquals(Arrays.asList(3000, 8080), MultirunMonitorPanel.parsePorts("3000, 8080"));
        assertEquals(Arrays.asList(5432), MultirunMonitorPanel.parsePorts("5432"));
    }

    @Test
    public void parsePortsIgnoresPlaceholdersAndBlanks() {
        assertTrue(MultirunMonitorPanel.parsePorts("-").isEmpty());
        assertTrue(MultirunMonitorPanel.parsePorts("n/a").isEmpty());
        assertTrue(MultirunMonitorPanel.parsePorts("").isEmpty());
        assertTrue(MultirunMonitorPanel.parsePorts(null).isEmpty());
    }

    @Test
    public void parsePortsSkipsNonNumericTokens() {
        final List<Integer> ports = MultirunMonitorPanel.parsePorts("3000, oops, 9229");
        assertEquals(Arrays.asList(3000, 9229), ports);
    }

    @Test
    public void urlForPortTargetsLocalhost() {
        assertEquals("http://localhost:3000", MultirunMonitorPanel.urlForPort(3000));
    }

    @Test
    public void statusLabelIsRunningWithoutAReadinessCondition() {
        assertEquals("running", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.NONE, false));
        assertEquals("running", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.LOG, false));
    }

    @Test
    public void statusLabelMapsPortAndHttpChecksToHealthyOrDown() {
        assertEquals("healthy", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.PORT, true));
        assertEquals("down", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.PORT, false));
        assertEquals("healthy", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.HTTP, true));
        assertEquals("down", MultirunMonitorPanel.statusLabel(ReadyCondition.Type.HTTP, false));
    }

    @Test
    public void visibleColumnsDropsHiddenAndKeepsOrder() {
        final List<String> all = Arrays.asList("Name", "Env", "PID", "Ports", "CPU %");
        final List<String> visible = MultirunMonitorPanel.visibleColumns(
                all, new java.util.HashSet<>(Arrays.asList("PID", "CPU %")));
        assertEquals(Arrays.asList("Name", "Env", "Ports"), visible);
    }

    @Test
    public void visibleColumnsWithNothingHiddenReturnsEverything() {
        final List<String> all = Arrays.asList("Name", "Env", "PID");
        assertEquals(all, MultirunMonitorPanel.visibleColumns(all, java.util.Collections.emptySet()));
    }

    @Test
    public void selectionIndicesMatchesEverySelectedRowNotJustOne() {
        // a refresh rebuilds the rows: the whole multi-selection must be restored, not a single row
        final List<String> rowKeys = Arrays.asList("a", "b", "c", "d");
        final List<Integer> indices = MultirunMonitorPanel.selectionIndices(
                rowKeys, new java.util.HashSet<>(Arrays.asList("a", "c", "d")));
        assertEquals(Arrays.asList(0, 2, 3), indices);
    }

    @Test
    public void selectionIndicesSkipsKeysThatAreNoLongerPresent() {
        // an app that stopped between refreshes simply drops out of the restored selection
        final List<String> rowKeys = Arrays.asList("a", "b");
        final List<Integer> indices = MultirunMonitorPanel.selectionIndices(
                rowKeys, new java.util.HashSet<>(Arrays.asList("b", "gone")));
        assertEquals(Arrays.asList(1), indices);
    }

    @Test
    public void selectionIndicesIsEmptyWhenNothingWasSelected() {
        assertTrue(MultirunMonitorPanel.selectionIndices(
                Arrays.asList("a", "b"), java.util.Collections.<String>emptySet()).isEmpty());
    }
}
