package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.util.UUID

enum class StationMonitorMode(val label: String) {
    DEPARTURES("Partenze"), ARRIVALS("Arrivi"), BOTH("Arrivi e partenze")
}

data class StationMonitor(val stationId: String, val stationName: String, val mode: StationMonitorMode) {
    val accessibleSummary: String get() = "$stationName, monitoraggio ${mode.label.lowercase()}. Dati non disponibili in tempo reale."
}

data class ActiveTripStop(val station: String, val expected: String, val platform: String?)

data class ActiveTrip(
    val id: String,
    val trainNumber: String,
    val routeLabel: String,
    val stops: List<ActiveTripStop>,
    val currentStopIndex: Int = 0
) {
    val nextStop: ActiveTripStop? get() = stops.getOrNull(currentStopIndex)
    val isComplete: Boolean get() = currentStopIndex >= stops.size
    fun advance(): ActiveTrip = copy(currentStopIndex = (currentStopIndex + 1).coerceAtMost(stops.size))
}

class PersonalTravelStore(context: Context) {
    private val prefs = context.getSharedPreferences("binario_personal_travel_v1", Context.MODE_PRIVATE)

    fun stationMonitors(stations: List<Station> = emptyList()): List<StationMonitor> = prefs.getString(KEY_STATION_MONITORS, "").orEmpty()
        .split(";")
        .mapNotNull(::decodeMonitor)
        .filter { monitor -> stations.isEmpty() || stations.any { it.id == monitor.stationId } }

    fun toggleStationMonitor(station: Station, mode: StationMonitorMode, stations: List<Station>): List<StationMonitor> {
        val current = stationMonitors(stations)
        val existing = current.firstOrNull { it.stationId == station.id }
        val updated = if (existing == null) (listOf(StationMonitor(station.id, station.name, mode)) + current).take(12)
        else if (existing.mode == mode) current.filterNot { it.stationId == station.id }
        else current.map { if (it.stationId == station.id) it.copy(mode = mode) else it }
        persistMonitors(updated)
        return updated
    }

    fun removeStationMonitor(stationId: String, stations: List<Station>): List<StationMonitor> {
        val updated = stationMonitors(stations).filterNot { it.stationId == stationId }
        persistMonitors(updated)
        return updated
    }

    fun replaceStationMonitors(monitors: List<StationMonitor>, stations: List<Station>): List<StationMonitor> {
        val normalized = monitors.filter { monitor -> stations.any { it.id == monitor.stationId } }.distinctBy { it.stationId }.take(12)
        persistMonitors(normalized)
        return normalized
    }

    fun activeTrip(): ActiveTrip? = prefs.getString(KEY_ACTIVE_TRIP, null)?.let(::decodeTrip)

    fun startTrip(service: TrainService): ActiveTrip {
        val trip = ActiveTrip(
            id = UUID.randomUUID().toString(),
            trainNumber = service.number,
            routeLabel = "${service.origin} → ${service.destination}",
            stops = service.stops.filterNot(TrainStop::completed).map { ActiveTripStop(it.station, it.expected, it.platform) }
        )
        saveTrip(trip)
        return trip
    }

    fun advanceTrip(): ActiveTrip? = activeTrip()?.advance()?.also(::saveTrip)

    fun endTrip() { prefs.edit { remove(KEY_ACTIVE_TRIP) } }

    private fun persistMonitors(monitors: List<StationMonitor>) {
        prefs.edit { putString(KEY_STATION_MONITORS, monitors.joinToString(";") { encodeMonitor(it) }) }
    }

    private fun saveTrip(trip: ActiveTrip) { prefs.edit { putString(KEY_ACTIVE_TRIP, encodeTrip(trip)) } }

    private fun encodeMonitor(monitor: StationMonitor): String = listOf(monitor.stationId, monitor.stationName, monitor.mode.name).joinToString(",") { enc(it) }
    private fun decodeMonitor(value: String): StationMonitor? = runCatching {
        val p = value.split(",").map(::dec)
        if (p.size != 3) null else StationMonitor(p[0], p[1], StationMonitorMode.valueOf(p[2]))
    }.getOrNull()

    private fun encodeTrip(trip: ActiveTrip): String = listOf(
        trip.id, trip.trainNumber, trip.routeLabel, trip.currentStopIndex.toString(),
        trip.stops.joinToString("|") { listOf(it.station, it.expected, it.platform.orEmpty()).joinToString("~") { part -> enc(part) } }
    ).joinToString(",") { enc(it) }

    private fun decodeTrip(value: String): ActiveTrip? = runCatching {
        val p = value.split(",").map(::dec)
        if (p.size != 5) null else ActiveTrip(
            p[0], p[1], p[2],
            p[4].split("|").filter { it.isNotBlank() }.mapNotNull { encoded ->
                val s = encoded.split("~").map(::dec)
                if (s.size == 3) ActiveTripStop(s[0], s[1], s[2].ifBlank { null }) else null
            },
            p[3].toIntOrNull() ?: 0
        )
    }.getOrNull()

    private fun enc(value: String): String = PreferenceValueCodec.encode(value)
    private fun dec(value: String): String = PreferenceValueCodec.decode(value)
        ?: throw IllegalArgumentException("Valore preferenza Base64 non valido")

    private companion object {
        const val KEY_STATION_MONITORS = "station_monitors"
        const val KEY_ACTIVE_TRIP = "active_trip"
    }
}
