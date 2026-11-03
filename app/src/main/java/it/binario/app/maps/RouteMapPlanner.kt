package it.binario.app.maps

import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainStop

/** What a stop is on the route map. Several can apply to one stop (an origin can be the last confirmed stop). */
enum class RouteStopRole { ORIGIN, DESTINATION, LAST_CONFIRMED, NEXT, SKIPPED }

data class RouteMapStop(
    /** Position in the service's stop list. */
    val stopIndex: Int,
    val name: String,
    val scheduled: String,
    /** Expected time only when the source verified it and it differs from the timetable. */
    val expected: String?,
    /** Catalogue coordinates, or null when the stop could not be matched to a station with coordinates. */
    val point: GeoPoint?,
    val roles: Set<RouteStopRole>
) {
    /** The role that decides the marker: live progress first, then a skipped stop, then the route ends. */
    val primaryRole: RouteStopRole?
        get() = ROLE_PRECEDENCE.firstOrNull { it in roles }

    private companion object {
        val ROLE_PRECEDENCE = listOf(
            RouteStopRole.NEXT,
            RouteStopRole.LAST_CONFIRMED,
            RouteStopRole.SKIPPED,
            RouteStopRole.ORIGIN,
            RouteStopRole.DESTINATION
        )
    }
}

data class RouteMapModel(
    /** Every stop in route order, mapped or not: the card's text alternative lists them all. */
    val stops: List<RouteMapStop>,
    /** The schematic line: mapped stops the train calls at, in order. It is not the track geometry. */
    val line: List<GeoPoint>,
    val camera: OpenMapCamera?
) {
    val mappedStops: List<RouteMapStop> get() = stops.filter { it.point != null }
    val unmappedStops: List<RouteMapStop> get() = stops.filter { it.point == null }
}

/** Last stop with an observed passage and the first later stop still to be served; both null when unknown. */
data class RouteProgress(val lastConfirmedIndex: Int?, val nextIndex: Int?)

internal object RouteMapPlanner {
    private val placeholderTimes = setOf("", "—", "-", "--", "--:--")

    /**
     * Binario has no train positions: progress comes only from stops whose passage a source confirmed
     * (`completed`, set when an actual time was published). Without such a stop nothing is highlighted.
     */
    fun progress(stops: List<TrainStop>): RouteProgress {
        val lastConfirmed = stops.indices.lastOrNull { index ->
            stops[index].completed && stops[index].routeChange != StopRouteChange.SKIPPED
        } ?: return RouteProgress(null, null)
        val next = (lastConfirmed + 1 until stops.size).firstOrNull { index ->
            !stops[index].completed && stops[index].routeChange != StopRouteChange.SKIPPED
        }
        return RouteProgress(lastConfirmed, next)
    }

    /**
     * @param serviceVerified the service-level flag that stops without their own flag inherit.
     * @param locate resolves a published stop name to catalogue coordinates; null when there is no single match.
     */
    fun build(stops: List<TrainStop>, serviceVerified: Boolean, locate: (String) -> GeoPoint?): RouteMapModel {
        val progress = progress(stops)
        val located = HashMap<String, GeoPoint?>()
        val mapStops = stops.mapIndexed { index, stop ->
            val roles = buildSet {
                if (index == 0) add(RouteStopRole.ORIGIN)
                if (index == stops.lastIndex && index > 0) add(RouteStopRole.DESTINATION)
                if (index == progress.lastConfirmedIndex) add(RouteStopRole.LAST_CONFIRMED)
                if (index == progress.nextIndex) add(RouteStopRole.NEXT)
                if (stop.routeChange == StopRouteChange.SKIPPED) add(RouteStopRole.SKIPPED)
            }
            val verified = stop.operationalStatusVerified ?: serviceVerified
            RouteMapStop(
                stopIndex = index,
                name = stop.station,
                scheduled = stop.scheduled,
                expected = stop.expected.takeIf { verified && it != stop.scheduled && it.trim() !in placeholderTimes },
                point = located.getOrPut(stop.station) { locate(stop.station)?.takeIf { it.isValid } },
                roles = roles
            )
        }
        val line = mapStops.filter { RouteStopRole.SKIPPED !in it.roles }.mapNotNull { it.point }
            .fold(mutableListOf<GeoPoint>()) { points, point -> points.apply { if (lastOrNull() != point) add(point) } }
            .takeIf { it.size >= 2 }
            .orEmpty()
        return RouteMapModel(
            stops = mapStops,
            line = line,
            camera = OpenMapCameraPlanner.plan(mapStops.mapNotNull { it.point })
        )
    }
}
