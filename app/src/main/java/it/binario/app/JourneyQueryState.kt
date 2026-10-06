package it.binario.app

import it.binario.app.data.JourneyQuery
import java.time.LocalDate
import java.time.LocalTime

/** Owns the temporal context of the currently selected route query. */
internal class JourneyQueryState(
    private val currentQuery: () -> JourneyQuery = JourneyQuery::now
) {
    private var planned: JourneyQuery? = null

    /**
     * The planned date/time chosen by the user, or the railway time at the moment of reading:
     * a current query must not freeze at the time the screen was created.
     */
    val value: JourneyQuery get() = planned ?: currentQuery()

    /**
     * True when results describe the next departures from now. Only those may be published to
     * the widget and to Wear, which present them as the upcoming trains of the route.
     */
    val followsCurrentTime: Boolean get() = planned == null

    fun selectPlanned(serviceDate: LocalDate, departureTime: LocalTime) {
        planned = JourneyQuery(serviceDate, departureTime)
    }

    /** A newly activated/saved route starts from the current railway time, not an old planner search. */
    fun resetForRouteActivation() {
        planned = null
    }
}
