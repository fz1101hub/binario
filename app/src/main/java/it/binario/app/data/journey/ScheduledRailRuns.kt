package it.binario.app.data.journey

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.GtfsDayTimetable
import it.binario.app.data.provider.JourneyEndpoint
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One timed stop of a [ScheduledRailRun], as the embedded timetable publishes it. Minutes count from the midnight of
 * the run's service day, so a stop reached after midnight keeps 24:00+ values (1450 = 00:10 of the next day).
 * Coordinates are the catalogue station's, or the pack stop's own when the catalogue does not know the stop; both null
 * when neither has any.
 */
data class ScheduledRunStop(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val arrivalMinute: Int,
    val departureMinute: Int
)

/**
 * A train of the static timetable on one service day: its public number and its timed stops in order. Only the
 * scheduled times are here; a live delay, when the app has one, is applied by the caller.
 */
data class ScheduledRailRun(
    /** `<pack>:<trip_id>:<service date>`: one run of one pack. */
    val id: String,
    val serviceDate: LocalDate,
    /** The number as the timetable shows it ("2153", "9558"); null when the pack has none. */
    val publicNumber: String?,
    /** route_short_name as the app reads it ("REG", "FR", "RE11", "S5"), display only. */
    val lineLabel: String?,
    val category: TrainCategory,
    val operator: RailOperator,
    val operatorName: String?,
    /** At least two, in run order. */
    val stops: List<ScheduledRunStop>
) {
    val firstDepartureMinute: Int get() = stops.first().departureMinute
    val lastArrivalMinute: Int get() = stops.last().arrivalMinute
}

/** Which runs the live train map asks the static timetable for. */
sealed interface ScheduledRunScope {
    /** Runs that call at [origin] and later at [destination] (the route board's direction). */
    data class Route(
        val origin: StationId,
        val originEndpoint: JourneyEndpoint?,
        val destination: StationId,
        val destinationEndpoint: JourneyEndpoint?
    ) : ScheduledRunScope

    /** Runs with at least one stop inside the box (degrees, WGS84). */
    data class Area(val north: Double, val south: Double, val east: Double, val west: Double) : ScheduledRunScope {
        fun contains(latitude: Double, longitude: Double): Boolean = latitude in south..north && longitude in west..east
    }
}

/**
 * The runs that can be on the move around [at] (railway-local time): those whose scheduled span overlaps
 * `[at - lookBackMinutes, at + lookAheadMinutes]`. The look-back is the largest live delay that can still be applied.
 */
data class ScheduledRunRequest(
    val at: LocalDateTime,
    val scope: ScheduledRunScope,
    val lookBackMinutes: Int = DEFAULT_LOOK_BACK_MINUTES,
    val lookAheadMinutes: Int = DEFAULT_LOOK_AHEAD_MINUTES
) {
    init {
        require(lookBackMinutes in 0..MAX_LOOK_MINUTES && lookAheadMinutes in 0..MAX_LOOK_MINUTES) { "Finestra non valida" }
    }

    companion object {
        const val DEFAULT_LOOK_BACK_MINUTES = 180
        const val DEFAULT_LOOK_AHEAD_MINUTES = 30
        private const val MAX_LOOK_MINUTES = 12 * 60
    }
}

/** Builds [ScheduledRailRun]s from the connections of one cached service day; pure, so the selection is testable. */
internal object ScheduledRunCollector {
    /**
     * Appends to [into] the train runs of [day] whose span overlaps `[fromMinute, untilMinute]` (minutes of the day's
     * service date) and that [accept] keeps. A stop is identified by its index in [day]'s stop table: [nodeOf] gives
     * its transfer point (the catalogue station when it has one), [nameOf] its display name and [pointOf] its
     * coordinates. Coaches, replacement buses and city lines are not trains and are left out.
     */
    fun collect(
        day: GtfsDayTimetable,
        sourceId: String,
        fromMinute: Int,
        untilMinute: Int,
        nodeOf: (Int) -> String,
        nameOf: (Int) -> String,
        pointOf: (Int) -> DoubleArray?,
        accept: (nodes: List<String>, stops: List<ScheduledRunStop>) -> Boolean,
        into: MutableList<ScheduledRailRun>
    ) {
        for (trip in day.trips) {
            if (trip.mode != LegMode.TRAIN || trip.connectionCount == 0) continue
            val first = trip.firstConnection
            val last = first + trip.connectionCount - 1
            if (day.arrivalMinute[last] < fromMinute || day.departureMinute[first] > untilMinute) continue
            val nodes = ArrayList<String>(trip.connectionCount + 1)
            val stops = ArrayList<ScheduledRunStop>(trip.connectionCount + 1)
            fun add(stop: Int, arrival: Int, departure: Int) {
                val point = pointOf(stop)
                nodes += nodeOf(stop)
                stops += ScheduledRunStop(nameOf(stop), point?.getOrNull(0), point?.getOrNull(1), arrival, departure)
            }
            add(day.departureStop[first], day.departureMinute[first], day.departureMinute[first])
            for (connection in first..last) {
                val arrivalStop = day.arrivalStop[connection]
                val next = connection + 1
                // The next connection normally leaves from this arrival stop; the dwell lasts until its departure.
                val departure = if (next <= last && day.departureStop[next] == arrivalStop) day.departureMinute[next] else day.arrivalMinute[connection]
                add(arrivalStop, day.arrivalMinute[connection], departure)
                if (next <= last && day.departureStop[next] != arrivalStop) {
                    add(day.departureStop[next], day.departureMinute[next], day.departureMinute[next])
                }
            }
            if (!accept(nodes, stops)) continue
            into += ScheduledRailRun(
                id = "$sourceId:${trip.tripId}:${day.serviceDate}",
                serviceDate = day.serviceDate,
                publicNumber = EmbeddedGtfsJourneyProvider.displayNumber(trip.publicNumber),
                lineLabel = trip.lineLabel?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINE_LABEL && '(' !in it },
                category = trip.category,
                operator = trip.operator,
                operatorName = trip.operatorName,
                stops = stops
            )
        }
    }

    /** Route scope: some stop of [origin] comes before some later stop of [destination]. */
    fun servesInOrder(nodes: List<String>, origin: Set<String>, destination: Set<String>): Boolean {
        val boarding = nodes.indexOfFirst { it in origin }
        if (boarding < 0) return false
        for (index in boarding + 1 until nodes.size) if (nodes[index] in destination) return true
        return false
    }

    private const val MAX_LINE_LABEL = 8
}
