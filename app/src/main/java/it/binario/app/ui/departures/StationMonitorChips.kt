package it.binario.app.ui.departures

import it.binario.app.StationMonitor
import it.binario.app.StationMonitorMode

/**
 * The mode chips of a station's local monitoring. On a monitored station they show and edit the saved mode; otherwise
 * they choose the mode to start with. Before, they always started from «Entrambi»: on a station monitored for
 * departures only they contradicted what is stored, and «Smetti di monitorare» then switched the monitor to both
 * directions instead of stopping it (the store toggles off only the same mode) (audit2 android-ui improvement).
 */
internal object StationMonitorChips {
    fun savedMode(monitors: List<StationMonitor>, stationId: String): StationMonitorMode? =
        monitors.firstOrNull { it.stationId == stationId }?.mode

    /** The selected chip: the saved mode of a monitored station, otherwise the mode being chosen. */
    fun shown(monitors: List<StationMonitor>, stationId: String, chosen: StationMonitorMode): StationMonitorMode =
        savedMode(monitors, stationId) ?: chosen

    /** Whether tapping [mode] changes the saved monitor: a monitored station and another mode (never a stop). */
    fun changesSaved(monitors: List<StationMonitor>, stationId: String, mode: StationMonitorMode): Boolean =
        savedMode(monitors, stationId)?.let { it != mode } ?: false
}
