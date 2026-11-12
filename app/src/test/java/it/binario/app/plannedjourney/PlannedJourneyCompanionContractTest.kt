package it.binario.app.plannedjourney

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases of the companion's local-route contract, on the Android Agenda (EXPO_PORT_PLAN §3.5). */
class PlannedJourneyCompanionContractTest {
    private val rome = PlannedJourneyStation("S08409", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("S01700", "Milano Centrale", "MICT")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")
    private val italy = ZoneId.of("Europe/Rome")

    @Test fun `upcoming and archive split on the Italian departure, departed journeys in the archive newest first`() {
        val now = LocalDate.parse("2026-10-02").atTime(8, 0).atZone(italy).toInstant()
        val journeys = listOf(
            journey("a", "2026-10-01", "08:00"),
            journey("b", "2026-10-02", "08:15"),
            journey("c", "2026-10-09", "08:15")
        )
        assertEquals(listOf("b", "c"), PlannedJourneyAgenda.ordered(journeys, PlannedJourneyAgendaFilter.UPCOMING, now).map { it.journey.id })
        assertEquals(listOf("a"), PlannedJourneyAgenda.ordered(journeys, PlannedJourneyAgendaFilter.ARCHIVED, now).map { it.journey.id })
        val completed = PlannedJourneyLifecycle.complete(journeys[2], createdAt)
        assertEquals(
            listOf("c", "a"),
            PlannedJourneyAgenda.ordered(listOf(journeys[0], journeys[1], completed), PlannedJourneyAgendaFilter.ARCHIVED, now).map { it.journey.id }
        )
    }

    private fun journey(id: String, date: String, time: String) = PlannedJourney(
        id = id,
        itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment(
            "segment-$id", rome, milan, PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), italy)
        ))),
        createdAt = createdAt
    )
}
