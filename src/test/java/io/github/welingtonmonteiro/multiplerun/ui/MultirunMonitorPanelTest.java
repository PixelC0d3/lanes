package io.github.welingtonmonteiro.multiplerun.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

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
}
