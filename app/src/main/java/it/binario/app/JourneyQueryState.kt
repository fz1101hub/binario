package it.binario.app

import it.binario.app.data.JourneyQuery
import java.time.LocalDate
import java.time.LocalTime

/** Owns the temporal context of the currently selected route query. */
internal class JourneyQueryState(
    private val currentQuery: () -> JourneyQuery = JourneyQuery::now
) {
    var value: JourneyQuery = currentQuery()
        private set

    fun selectPlanned(serviceDate: LocalDate, departureTime: LocalTime) {
        value = JourneyQuery(serviceDate, departureTime)
    }

    /** A newly activated/saved route starts from the current railway time, not an old planner search. */
    fun resetForRouteActivation() {
        value = currentQuery()
    }
}
