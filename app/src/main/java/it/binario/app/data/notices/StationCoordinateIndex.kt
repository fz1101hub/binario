package it.binario.app.data.notices

import it.binario.app.data.Station
import it.binario.app.data.live.TrenordLineNoticesAdapter

/**
 * Catalogue stations with coordinates, by normalised name and alias. Used to place a train's published station
 * names (which carry no coordinates) on the map for the Trenord area check and the destination weather.
 */
class StationCoordinateIndex(stations: List<Station>) {
    private val byName: Map<String, Station> = buildMap {
        stations.asSequence()
            .filter { it.latitude != null && it.longitude != null }
            .forEach { station ->
                (listOf(station.name) + station.aliases).forEach { name ->
                    val key = TrenordLineNoticesAdapter.normalizeStationName(name)
                    if (key.length >= 2 && key !in this) put(key, station)
                }
            }
    }

    fun find(name: String): Station? = byName[TrenordLineNoticesAdapter.normalizeStationName(name)]

    fun anyInTrenordArea(names: Collection<String>): Boolean = names.any { name ->
        val station = find(name) ?: return@any false
        TrenordLineMatcher.inTrenordArea(station.latitude ?: return@any false, station.longitude ?: return@any false)
    }
}
