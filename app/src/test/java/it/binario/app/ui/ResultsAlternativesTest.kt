package it.binario.app.ui

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import it.binario.app.journeys.JourneyChangesLabel
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.ui.planner.ResultEntry
import it.binario.app.ui.planner.ResultsAlternatives
import it.binario.app.ui.planner.ResultsMerge
import it.binario.app.ui.planner.ResultsReliability
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Alternativa più puntuale» over the rows of the route results: direct trains only, still to be taken. */
class ResultsAlternativesTest {
    private val day = LocalDate.of(2026, 11, 21)
    private val zone = ZoneId.of("Europe/Rome")
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700")
    private val pavia = Station("curated:S01860", "Pavia", "Pavia", railwayCode = "S01860")
    private val stations = NetworkReliabilityStations(listOf(milano, pavia))

    /** A Trenord regional Milano Centrale → Pavia, on time (15 minutes) [onTime] of 30 times at Pavia. */
    private fun regional(number: String, onTime: Int) = train(
        number, "S01700", "RE", 30, 0,
        stop("S01700", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
        stop("S01860", 30, 9.0, listOf(onTime / 3, onTime / 2, onTime - 2, onTime - 1, onTime, 30, 30, 30))
    )

    private val stats = ReliabilityFixtures.stats(
        regional("2153", 15),
        regional("2157", 28),
        regional("2161", 27),
        regional("2165", 29),
        regional("2169", 14)
    )

    private fun service(number: String, departure: String, arrival: String) = TrainService(
        "train:$number", number, RailOperator.TRENORD, TrainCategory.TRENORD_REGIO, "Milano Centrale", "Pavia",
        departure, departure, null, 0, ServiceStatus.ON_TIME,
        listOf(TrainStop("Milano Centrale", departure, departure, null, 0, false), TrainStop("Pavia", arrival, arrival, null, 0, false)),
        operationalStatusVerified = false,
        scheduledDepartureDateTime = day.atTime(LocalTime.parse(departure))
    )

    private fun at(time: String): LocalDateTime = day.atTime(LocalTime.parse(time))

    private fun zoned(time: String): ZonedDateTime = at(time).atZone(zone)

    /** Trenord publishes its numbers after the line ("RE_11 - 2153"): the statistics know them as 2153. */
    private val late = service("RE_11 - 2153", "08:00", "08:35")
    private val punctual = service("RE_11 - 2157", "08:12", "08:47")

    private fun alternatives(services: List<TrainService>, now: String, journeys: List<JourneyOptionRow> = emptyList()): Map<String, String> {
        val entries = ResultsMerge.entries(services, journeys, day)
        val reliability = ResultsReliability.assess(entries, stats, milano, pavia, stations)
        return ResultsAlternatives.find(entries, reliability, at(now)).mapValues { it.value.target.service.number }
    }

    @Test fun `a Trenord regional often late at Pavia gets the next one, by its published number`() {
        val entries = ResultsMerge.entries(listOf(late, punctual), emptyList(), day)
        val reliability = ResultsReliability.assess(entries, stats, milano, pavia, stations)
        val found = ResultsAlternatives.find(entries, reliability, at("07:30"))
        assertEquals(setOf("train-train:RE_11 - 2153"), found.keys)
        val alternative = found.getValue("train-train:RE_11 - 2153")
        assertEquals(punctual, alternative.target.service)
        assertEquals(28, alternative.stop.onTime)
        assertEquals(30, alternative.stop.samples)
        assertEquals(15, alternative.stop.onTimeLimitMinutes)
        // Without the statistics of the rows (absent, disabled, demonstration data): nothing.
        assertTrue(ResultsAlternatives.find(entries, emptyMap(), at("07:30")).isEmpty())
    }

    @Test fun `the closest train still to depart is suggested, never one already gone or cancelled`() {
        val services = listOf(late, punctual, service("2161", "07:45", "08:20"), service("2165", "08:25", "09:00"))
        assertEquals(mapOf("train-train:RE_11 - 2153" to "RE_11 - 2157"), alternatives(services, "07:30"))
        // At 07:46 the 07:45 has left; the 08:12 is still the closest.
        assertEquals(mapOf("train-train:RE_11 - 2153" to "RE_11 - 2157"), alternatives(services, "07:46"))
        // The 08:12 cancelled: the 08:25 instead.
        val cancelled = punctual.copy(status = ServiceStatus.CANCELLED, operationalStatusVerified = true)
        assertEquals(mapOf("train-train:RE_11 - 2153" to "2165"), alternatives(listOf(late, cancelled, services[3]), "07:30"))
        // Once the late train itself has left, it gets nothing.
        assertTrue(alternatives(services, "08:01").isEmpty())
    }

    @Test fun `a train is gone a minute after its departure, counting a verified delay`() {
        val departure = at("08:12")
        assertTrue(ResultsAlternatives.stillToDepart(punctual, departure, at("08:12")))
        assertFalse(ResultsAlternatives.stillToDepart(punctual, departure, at("08:13")))
        // Live and 10 minutes late, not yet seen at the boarding station: it leaves at 08:22.
        val delayed = punctual.copy(delayMinutes = 10, status = ServiceStatus.DELAYED, operationalStatusVerified = true)
        assertTrue(ResultsAlternatives.stillToDepart(delayed, departure, at("08:20")))
        assertFalse(ResultsAlternatives.stillToDepart(delayed, departure, at("08:23")))
        // Seen passing the boarding station: gone, whatever the clock says.
        val passed = delayed.copy(stops = delayed.stops.mapIndexed { index, stop -> if (index == 0) stop.copy(completed = true) else stop })
        assertFalse(ResultsAlternatives.stillToDepart(passed, departure, at("08:00")))
        // A delay of a timetable row (not verified) or late minutes unpublished do not move the departure.
        assertFalse(ResultsAlternatives.stillToDepart(punctual.copy(delayMinutes = 10), departure, at("08:13")))
        assertFalse(ResultsAlternatives.stillToDepart(punctual.copy(delayMinutesUnpublished = true), departure, at("08:13")))
        assertFalse(ResultsAlternatives.stillToDepart(punctual.copy(status = ServiceStatus.CANCELLED), departure, at("07:00")))
        assertFalse(ResultsAlternatives.stillToDepart(punctual, null, at("07:00")))
    }

    @Test fun `journeys with changes take no part, and the window is 30 minutes`() {
        val leg = JourneyLegRow(
            LegMode.TRAIN, "RE", "2165", "Trenord", TrainCategory.TRENORD_REGIO, ServiceKind.REGIONAL, milano.name, pavia.name, milano.id, pavia.id,
            zoned("08:05"), zoned("08:40"), "08:05", "08:40", 35, "run-2165"
        )
        val minutes = Duration.between(leg.departure, leg.arrival).toMinutes()
        val journey = JourneyOptionRow(
            "j1", leg.departure, leg.arrival, leg.departureTime, leg.arrivalTime, 0, minutes, minutes / 60, minutes % 60, 0,
            JourneyChangesLabel.of(0), listOf(leg), emptyList(), "embedded-gtfs-journeys", JourneySourceLabel.OFFLINE_TIMETABLE, "Offline", true
        )
        val entries = ResultsMerge.entries(listOf(late), listOf(journey), day)
        assertTrue(entries.any { it is ResultEntry.Journey })
        assertTrue(ResultsAlternatives.find(entries, ResultsReliability.assess(entries, stats, milano, pavia, stations), at("07:30")).isEmpty())
        // The 08:31 is 31 minutes after the 08:00: too far.
        assertTrue(alternatives(listOf(late, service("2165", "08:31", "09:06")), "07:30").isEmpty())
        assertEquals(mapOf("train-train:RE_11 - 2153" to "2165"), alternatives(listOf(late, service("2165", "08:30", "09:05")), "07:30"))
        // Two trains as late as each other: no suggestion.
        assertTrue(alternatives(listOf(late, service("2169", "08:10", "08:45")), "07:30").isEmpty())
    }
}
