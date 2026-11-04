package it.binario.app.data.gtfs

import it.binario.app.data.Station
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.urbanLineName
import it.binario.app.data.provider.JourneyEndpoint
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

// Read-only questions about one station asked of the embedded timetables, on the planner's own day arrays and stop
// identities (EmbeddedGtfsJourneyProvider.stationDays): "Collegamenti diretti" and the stations of a saved route.

/**
 * One pack's service day as seen from one station: the connections of the day, the transfer point of each of its
 * stops (a catalogue station id, or a point of the pack), the points of the origin in this pack and, when a
 * destination was asked, the destination's.
 */
internal class GtfsStationDay(
    val day: GtfsDayTimetable,
    /** A city pack (metro, tram, funicular): its stops are never railway stations. */
    val urban: Boolean,
    val nodeOfStop: Array<String>,
    /** The origin's points in this pack with the minutes of walking to them: 0 for the station itself. */
    val originNodes: Map<String, Int>,
    val destinationNodes: Set<String>,
    private val placeOf: (String, Int) -> Pair<StationId, String>,
    private val stationOf: (String) -> Station?
) {
    /** The id and the name the app shows for point [node], reached at [stop] of [day]. */
    fun place(node: String, stop: Int): Pair<StationId, String> = placeOf(node, stop)

    /** The catalogue station [node] is, if it is one. */
    fun station(node: String): Station? = stationOf(node)

    /**
     * The connection of [trip] that boards at the origin, or -1: the first at the station itself, else at the origin's
     * nearest stop (a metro line passing two stops near the station is boarded at the one next to it).
     */
    fun boardingConnection(trip: GtfsDayTrip): Int {
        var best = -1
        var bestWalk = Int.MAX_VALUE
        for (connection in trip.firstConnection until trip.firstConnection + trip.connectionCount) {
            if (!day.pickupAllowed(connection)) continue
            val walk = originNodes[nodeOfStop[day.departureStop[connection]]] ?: continue
            if (walk < bestWalk) {
                best = connection
                bestWalk = walk
                if (walk == 0) break
            }
        }
        return best
    }
}

/** A destination reachable from a station without changes on one service day ("Collegamenti diretti"). */
data class DirectDestination(
    /** Catalogue station id, or `gtfs:<pack>:<stop>` for a stop the catalogue does not know. */
    val stationId: String,
    val name: String,
    /** Reached by a metro, tram or funicular line. */
    val urban: Boolean,
    /** Runs of the day; a train two packs publish counts once. */
    val runs: Int,
    /** First and last departure from the station, minutes from the service day's midnight (24:00 and later after midnight). */
    val firstDepartureMinute: Int,
    val lastDepartureMinute: Int,
    /** Shortest scheduled time from the departure to the arrival there (the walk to a city stop not included). */
    val fastestMinutes: Int,
    /** The city lines that go there ("M2", "9"), at most [DirectConnectionsCollector.MAX_LINES]; empty for trains. */
    val lines: List<String>,
    /** The city stop most of these runs leave from when it is a short walk from the station; null when it is the station. */
    val boardingStop: String?
)

/** The direct connections of one station on one service day: trains first, then the city lines. */
data class DirectConnections(val date: LocalDate, val rail: List<DirectDestination>, val urban: List<DirectDestination>) {
    val isEmpty: Boolean get() = rail.isEmpty() && urban.isEmpty()
}

/**
 * Collects the destinations of the [GtfsStationDay]s of one station: for every train (and city run) that boards at the
 * station, every later stop where it sets down passengers, with the runs of the day, the first and last departure and
 * the fastest run. Coaches, replacement buses and walks are left out. The same train published by two packs (same
 * number, departure within [COPY_SKEW_MINUTES]) counts once.
 */
internal class DirectConnectionsCollector(private val date: LocalDate) {
    private class Destination(val id: String, val name: String, val urban: Boolean) {
        var runs = 0
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        var fastest = Int.MAX_VALUE
        val lines = LinkedHashSet<String>()
        val boardingStops = HashMap<String, Int>()
    }

    private val destinations = LinkedHashMap<String, Destination>()
    /** Departure minutes already counted per destination and train number. */
    private val counted = HashMap<String, MutableList<Int>>()

    fun add(view: GtfsStationDay) {
        val day = view.day
        val reached = HashSet<String>()
        for (trip in day.trips) {
            val urban = view.urban || trip.mode in URBAN_MODES
            if (!urban && trip.mode != LegMode.TRAIN && trip.mode != LegMode.UNKNOWN) continue
            if (urban && trip.mode !in URBAN_MODES) continue
            val board = view.boardingConnection(trip)
            if (board < 0) continue
            val boardMinute = day.departureMinute[board]
            val boardStop = day.departureStop[board]
            val boardNode = view.nodeOfStop[boardStop]
            val walked = (view.originNodes[boardNode] ?: 0) > 0
            val number = if (urban) null else EmbeddedGtfsJourneyProvider.runNumberKey(trip.publicNumber)
            val line = if (urban) urbanLineName(trip.mode, trip.lineLabel) else null
            reached.clear()
            for (connection in board until trip.firstConnection + trip.connectionCount) {
                val stop = day.arrivalStop[connection]
                val node = view.nodeOfStop[stop]
                if (!day.dropOffAllowed(connection) || node in view.originNodes || !reached.add(node)) continue
                val minutes = day.arrivalMinute[connection] - boardMinute
                if (minutes < 0) continue
                val key = "$urban|$node"
                if (number != null) {
                    val minutesCounted = counted.getOrPut("$key|$number") { ArrayList(1) }
                    if (minutesCounted.any { kotlin.math.abs(it - boardMinute) <= COPY_SKEW_MINUTES }) continue
                    minutesCounted += boardMinute
                }
                val destination = destinations.getOrPut(key) {
                    val (id, name) = view.place(node, stop)
                    Destination(id.value, name, urban)
                }
                destination.runs += 1
                destination.first = minOf(destination.first, boardMinute)
                destination.last = maxOf(destination.last, boardMinute)
                destination.fastest = minOf(destination.fastest, minutes)
                if (line != null && destination.lines.size < MAX_LINES) destination.lines += line
                if (walked) {
                    val stopName = view.place(boardNode, boardStop).second
                    destination.boardingStops[stopName] = (destination.boardingStops[stopName] ?: 0) + 1
                }
            }
        }
    }

    fun result(): DirectConnections {
        val all = destinations.values.map { destination ->
            DirectDestination(
                stationId = destination.id,
                name = destination.name,
                urban = destination.urban,
                runs = destination.runs,
                firstDepartureMinute = destination.first,
                lastDepartureMinute = destination.last,
                fastestMinutes = destination.fastest,
                lines = destination.lines.toList(),
                boardingStop = destination.boardingStops.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
            )
        }.sortedWith(compareByDescending<DirectDestination> { it.runs }.thenBy { it.name })
        return DirectConnections(date, all.filterNot(DirectDestination::urban), all.filter(DirectDestination::urban))
    }

    companion object {
        const val MAX_LINES = 4
        /** Two packs' copies of one train leave within this many minutes of each other (as in the journey planner). */
        const val COPY_SKEW_MINUTES = 5
        private val URBAN_MODES = setOf(LegMode.METRO, LegMode.TRAM, LegMode.FUNICULAR)
    }
}

/** City stops at most this far from a railway station are "at the station" for its direct connections. */
internal const val DIRECT_URBAN_WALK_METERS = 300

/**
 * The destinations reachable from [origin] without changes on the GTFS service day [date]: the trains of the railway
 * packs, then the metro, tram and funicular lines of the city packs leaving from the station itself (a city stop) or
 * from a stop within [DIRECT_URBAN_WALK_METERS]. Scheduled times only; empty when no pack serves the station.
 */
internal suspend fun EmbeddedGtfsJourneyProvider.directConnections(
    origin: StationId,
    originEndpoint: JourneyEndpoint?,
    date: LocalDate
): DirectConnections {
    val collector = DirectConnectionsCollector(date)
    return try {
        stationDays(origin, originEndpoint, null, null, date, DIRECT_URBAN_WALK_METERS, collector::add)
        collector.result()
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        DirectConnections(date, emptyList(), emptyList())
    }
}

/**
 * The stations the direct trains from [origin] to [destination] call at on [date], in the order of the first such train
 * and then of the others, origin and destination included: catalogue stations, or the pack's own stop as a station.
 * Empty when no train of the railway packs runs between them without a change.
 */
internal suspend fun EmbeddedGtfsJourneyProvider.directRunStations(
    origin: StationId,
    originEndpoint: JourneyEndpoint?,
    destination: StationId,
    destinationEndpoint: JourneyEndpoint?,
    date: LocalDate
): List<Station> {
    val stations = LinkedHashMap<String, Station>()
    try {
        stationDays(origin, originEndpoint, destination, destinationEndpoint, date, 0) { view ->
            if (view.urban) return@stationDays
            val day = view.day
            for (trip in day.trips) {
                if (trip.mode != LegMode.TRAIN && trip.mode != LegMode.UNKNOWN) continue
                val board = view.boardingConnection(trip)
                if (board < 0) continue
                val end = trip.firstConnection + trip.connectionCount
                val alight = (board until end).firstOrNull { day.dropOffAllowed(it) && view.nodeOfStop[day.arrivalStop[it]] in view.destinationNodes }
                    ?: continue
                for (connection in board..alight) {
                    val stops = if (connection == board) listOf(day.departureStop[connection], day.arrivalStop[connection]) else listOf(day.arrivalStop[connection])
                    for (stop in stops) {
                        val node = view.nodeOfStop[stop]
                        if (node in stations) continue
                        stations[node] = view.station(node) ?: view.place(node, stop).let { (id, name) -> Station(id = id.value, name = name, city = name) }
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        return emptyList()
    }
    return stations.values.toList()
}
