package it.binario.app.maps

import it.binario.app.NearbyStation
import it.binario.app.StationCoordinates
import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.Station

/**
 * Finds catalogue coordinates for the names maps have to place.
 *
 * A name is resolved first exactly as the rest of the app resolves typed or stored station names
 * ([exactLookup], i.e. `TrainViewModel.stationFromName`), then, for operator spellings such as "MILANO C.LE",
 * with the strict whole-name [RailStopNameMatcher] used by the timetable adapters. A name that names more than
 * one station, or a catalogue station without coordinates, stays unresolved: the map leaves the stop out
 * rather than guessing.
 *
 * Construction is free: the filtered catalogue and the token index are built lazily on first use, which the map
 * cards run off the main thread. Thread-safe once built.
 */
internal class OpenMapStationIndex(
    stations: List<Station>,
    private val exactLookup: (String) -> Station?
) {
    private val catalogueIds: Set<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { stations.mapTo(HashSet(), Station::id) }
    private val located: List<Station> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { stations.filter { it.mapPoint() != null } }
    private val locatedById: Map<String, Station> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { located.associateBy(Station::id) }

    private val byToken: Map<String, List<Station>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val index = HashMap<String, MutableList<Station>>()
        located.forEach { station ->
            (listOf(station.name) + station.aliases).asSequence()
                .flatMap { RailStopNameMatcher.tokens(it).asSequence() }
                .filter { it.length >= MIN_INDEXED_TOKEN }
                .distinct()
                .forEach { token -> index.getOrPut(token) { mutableListOf() }.add(station) }
        }
        index
    }

    /** The catalogue station with coordinates named by [name], or null when there is none or more than one. */
    fun locate(name: String): Station? {
        if (name.isBlank()) return null
        // The app's own answer wins, even when that station has no coordinates: a homonym elsewhere is no substitute.
        exactLookup(name)?.takeIf { it.id in catalogueIds }?.let { exact -> return coordinatesOf(exact) }
        // RailStopNameMatcher needs at least one shared word of three letters or more, so the index
        // narrows the candidates without changing which stations match.
        val candidates = RailStopNameMatcher.tokens(name).asSequence()
            .filter { it.length >= MIN_INDEXED_TOKEN }
            .flatMap { byToken[it].orEmpty().asSequence() }
            .distinctBy(Station::id)
            .filter { RailStopNameMatcher.matches(it, name) }
            .toList()
        return candidates.distinctBy { it.railwayCode ?: it.id }.singleOrNull()
    }

    /** [station] itself when it has coordinates, otherwise the catalogue entry with the same id. */
    fun coordinatesOf(station: Station): Station? =
        station.takeIf { it.mapPoint() != null } ?: locatedById[station.id]

    /** The nearest other catalogue stations, one per railway code, closest first. */
    fun neighbours(station: Station, limit: Int): List<NearbyStation> {
        val centre = coordinatesOf(station)?.mapPoint() ?: return emptyList()
        if (limit <= 0) return emptyList()
        val ownCode = station.railwayCode
        return StationCoordinates.nearby(located, centre.latitude, centre.longitude).asSequence()
            .filter { it.station.id != station.id && (ownCode == null || it.station.railwayCode != ownCode) }
            .distinctBy { it.station.railwayCode ?: it.station.id }
            .take(limit)
            .toList()
    }

    private companion object {
        const val MIN_INDEXED_TOKEN = 3
    }
}
