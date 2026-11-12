package it.binario.app.ui.departures

import it.binario.app.StationMonitor
import it.binario.app.StationMonitorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationMonitorChipsTest {
    private val monitors = listOf(
        StationMonitor("S01700", "Milano Centrale", StationMonitorMode.DEPARTURES),
        StationMonitor("S08409", "Roma Termini", StationMonitorMode.ARRIVALS)
    )

    @Test
    fun `a monitored station shows its saved mode, whatever was chosen before`() {
        assertEquals(StationMonitorMode.DEPARTURES, StationMonitorChips.shown(monitors, "S01700", StationMonitorMode.BOTH))
        assertEquals(StationMonitorMode.ARRIVALS, StationMonitorChips.shown(monitors, "S08409", StationMonitorMode.DEPARTURES))
    }

    @Test
    fun `a station not monitored shows the mode being chosen`() {
        assertEquals(StationMonitorMode.BOTH, StationMonitorChips.shown(monitors, "S06421", StationMonitorMode.BOTH))
        assertEquals(StationMonitorMode.ARRIVALS, StationMonitorChips.shown(emptyList(), "S01700", StationMonitorMode.ARRIVALS))
    }

    @Test
    fun `only another chip on a monitored station changes the saved mode, so the same chip never stops monitoring`() {
        assertTrue(StationMonitorChips.changesSaved(monitors, "S01700", StationMonitorMode.BOTH))
        assertFalse(StationMonitorChips.changesSaved(monitors, "S01700", StationMonitorMode.DEPARTURES))
        assertFalse(StationMonitorChips.changesSaved(monitors, "S06421", StationMonitorMode.ARRIVALS))
    }
}
