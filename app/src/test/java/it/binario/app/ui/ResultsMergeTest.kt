package it.binario.app.ui

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.repository.JourneySearchQuery
import it.binario.app.journeys.JourneyChangesLabel
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.journeys.JourneyTransferRow
import it.binario.app.ui.planner.JourneyRequest
import it.binario.app.ui.planner.ResultEntry
import it.binario.app.ui.planner.ResultsMerge
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

class ResultsMergeTest {
    private val day = LocalDate.of(2026, 11, 21)
    private val zone = ZoneId.of("Europe/Rome")

    private fun train(number: String, departure: String, arrival: String, transfers: Int = 0) = TrainService(
        "train:$number", number, RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "Roma Termini", "Torino Porta Nuova",
        departure, departure, null, 0, ServiceStatus.ON_TIME,
        listOf(TrainStop("Roma Termini", departure, departure, null, 0, false), TrainStop("Torino Porta Nuova", arrival, arrival, null, 0, false)),
        transferCount = transfers, operationalStatusVerified = false,
        scheduledDepartureDateTime = day.atTime(LocalTime.parse(departure))
    )

    private fun at(time: String, plusDays: Long = 0): ZonedDateTime = day.plusDays(plusDays).atTime(LocalTime.parse(time)).atZone(zone)

    private fun leg(number: String?, from: String, to: String, departure: ZonedDateTime, arrival: ZonedDateTime) = JourneyLegRow(
        LegMode.TRAIN, "RE", number, "Trenord", TrainCategory.REGIONALE, ServiceKind.REGIONAL, from, to, "id-$from", "id-$to",
        departure, arrival, departure.toLocalTime().toString(), arrival.toLocalTime().toString(),
        Duration.between(departure, arrival).toMinutes(), "run-$number"
    )

    private fun journey(id: String, vararg legs: JourneyLegRow): JourneyOptionRow {
        val departure = legs.first().departure
        val arrival = legs.last().arrival
        val minutes = Duration.between(departure, arrival).toMinutes()
        val changes = legs.size - 1
        return JourneyOptionRow(
            id, departure, arrival, departure.toLocalTime().toString(), arrival.toLocalTime().toString(), 0, minutes,
            minutes / 60, minutes % 60, changes, JourneyChangesLabel.of(changes), legs.toList(),
            legs.toList().zipWithNext { a, b -> JourneyTransferRow(a.toLabel, a.toStationId, a.arrivalTime, b.departureTime, Duration.between(a.arrival, b.departure).toMinutes()) },
            "embedded-gtfs-journeys", JourneySourceLabel.OFFLINE_TIMETABLE, "Offline", true
        )
    }

    private val fr9558 = train("9558", "17:40", "22:05")
    private val ic597 = train("597", "18:20", "22:50")
    private val oneChange = journey("j1", leg("2153", "Roma Termini", "Milano Centrale", at("18:00"), at("21:10")), leg("2041", "Milano Centrale", "Torino Porta Nuova", at("21:20"), at("22:35")))
    private val twoChanges = journey(
        "j2",
        leg("2310", "Roma Termini", "Firenze S. M. Novella", at("18:35"), at("20:20")),
        leg("6420", "Firenze S. M. Novella", "Bologna Centrale", at("20:35"), at("21:55")),
        leg("35", "Bologna Centrale", "Torino Porta Nuova", at("22:10"), at("00:40", plusDays = 1))
    )

    @Test
    fun `direct trains and journeys with changes share one list by departure`() {
        val merged = ResultsMerge.entries(listOf(ic597, fr9558), listOf(twoChanges, oneChange), day)
        assertEquals(listOf("train-train:9558", "journey-j1", "train-train:597", "journey-j2"), merged.map { it.key })
        assertEquals(listOf(0, 1, 0, 2), merged.map { it.changes })
    }

    @Test
    fun `a direct journey that is the same train as a direct-train result is shown once as the train`() {
        val sameTrain = journey("j0", leg("9558", "Roma Termini", "Torino Porta Nuova", at("17:40"), at("22:05")))
        val otherDirect = journey("j3", leg("9560", "Roma Termini", "Torino Porta Nuova", at("17:40"), at("22:15")))
        val merged = ResultsMerge.entries(listOf(fr9558), listOf(sameTrain, otherDirect, oneChange), day)
        assertEquals(listOf("train-train:9558", "journey-j3", "journey-j1"), merged.map { it.key })
        assertTrue(ResultsMerge.sameTrain(merged.first() as ResultEntry.Train, sameTrain))
        // Without a number on one side the arrival must match too.
        val unnumbered = journey("j4", leg(null, "Roma Termini", "Torino Porta Nuova", at("17:40"), at("22:05")))
        assertTrue(ResultsMerge.sameTrain(merged.first() as ResultEntry.Train, unnumbered))
        val unnumberedLater = journey("j5", leg(null, "Roma Termini", "Torino Porta Nuova", at("17:40"), at("23:00")))
        assertFalse(ResultsMerge.sameTrain(merged.first() as ResultEntry.Train, unnumberedLater))
        // A journey with changes is never the same as a direct train.
        assertFalse(ResultsMerge.sameTrain(merged.first() as ResultEntry.Train, oneChange))
    }

    @Test
    fun `a direct journey that walks to the same train first is shown once as the train`() {
        // Another pack's name for the departure station is a few minutes' walk away: the option leaves three minutes
        // before its train, which is the direct train of the list.
        val walk = leg(null, "Roma Termini", "Roma Termini Est", at("17:37"), at("17:40")).copy(mode = LegMode.WALK, lineLabel = null)
        val ride = leg("9558", "Roma Termini Est", "Torino Porta Nuova", at("17:40"), at("22:05"))
        val walking = journey("j6", walk, ride).copy(changes = 0, changesLabel = JourneyChangesLabel.Direct, transfers = emptyList(), walkMinutes = 3)
        val direct = ResultsMerge.entries(listOf(fr9558), emptyList(), day).single() as ResultEntry.Train
        assertTrue(ResultsMerge.sameTrain(direct, walking))
        assertEquals(listOf("train-train:9558"), ResultsMerge.entries(listOf(fr9558), listOf(walking), day).map { it.key })
        // A long walk to it is another way to travel, and another train at that minute is another train.
        assertFalse(ResultsMerge.sameTrain(direct, walking.copy(walkMinutes = 15)))
        assertFalse(ResultsMerge.sameTrain(direct, journey("j7", walk, leg("9560", "Roma Termini Est", "Torino Porta Nuova", at("17:40"), at("22:05")))
            .copy(changes = 0, changesLabel = JourneyChangesLabel.Direct, transfers = emptyList(), walkMinutes = 3)))
    }

    @Test
    fun `the four chips follow the presenter rules and count their options`() {
        val merged = ResultsMerge.entries(listOf(fr9558, ic597), listOf(oneChange, twoChanges), day)
        assertEquals(listOf("train-train:9558", "train-train:597"), ResultsMerge.apply(merged, JourneyResultsFilter.DIRECT).map { it.key })
        assertEquals(
            listOf("train-train:9558", "train-train:597", "journey-j1", "journey-j2"),
            ResultsMerge.apply(merged, JourneyResultsFilter.FEWEST_CHANGES).map { it.key }
        )
        // Durations: 265 (9558), 270 (597), 275 (one change), 365 (two changes, past midnight).
        assertEquals(
            listOf("train-train:9558", "train-train:597", "journey-j1", "journey-j2"),
            ResultsMerge.apply(merged, JourneyResultsFilter.FASTEST).map { it.key }
        )
        assertEquals(365L, merged.single { it.key == "journey-j2" }.durationMinutes)
        assertEquals(
            mapOf(
                JourneyResultsFilter.ALL to 4, JourneyResultsFilter.DIRECT to 2, JourneyResultsFilter.FEWEST_CHANGES to 4,
                JourneyResultsFilter.FASTEST to 4, JourneyResultsFilter.LEAST_WALKING to 4
            ),
            ResultsMerge.counts(merged)
        )
    }

    @Test
    fun `a direct train without a readable time goes last and has no duration`() {
        val broken = fr9558.copy(
            id = "train:1", number = "1", scheduledDeparture = "--", expectedDeparture = "--", scheduledDepartureDateTime = null,
            stops = fr9558.stops.map { it.copy(scheduled = "--", expected = "--") }
        )
        val merged = ResultsMerge.entries(listOf(broken, fr9558), listOf(oneChange), day)
        assertEquals("train-train:1", merged.last().key)
        assertEquals(null, merged.last().durationMinutes)
        assertEquals("train-train:1", ResultsMerge.apply(merged, JourneyResultsFilter.FASTEST).last().key)
    }

    private val roma = Station("curated:roma-termini", "Roma Termini", "Roma Termini")
    private val torino = Station("curated:torino-pn", "Torino Porta Nuova", "Torino Porta Nuova")
    private val milano = Station("curated:milano-c", "Milano Centrale", "Milano Centrale")
    private fun searched(from: Station, to: Station, departure: String) = JourneySearchQuery(from, to, LocalDateTime.parse(departure))

    @Test
    fun `a planned search is answered only by the journeys searched for the same stations and minute`() {
        val request = JourneyRequest(roma, torino, LocalDateTime.parse("2026-11-21T17:00"), followsCurrentTime = false)
        assertTrue(request.isAnsweredBy(searched(roma, torino, "2026-11-21T17:00")))
        assertFalse(request.isAnsweredBy(searched(roma, torino, "2026-11-21T17:05")))
        assertFalse(request.isAnsweredBy(searched(roma, milano, "2026-11-21T17:00")))
        assertFalse(request.isAnsweredBy(searched(torino, roma, "2026-11-21T17:00")))
        assertFalse(request.isAnsweredBy(null))
        assertFalse(request.needsSearch(searched(roma, torino, "2026-11-21T17:00")))
        assertTrue(request.needsSearch(searched(roma, torino, "2026-11-21T16:00")))
        assertTrue(request.needsSearch(null))
    }

    @Test
    fun `results that follow the current time keep their journeys as the clock moves and search again later`() {
        val at = { time: String -> JourneyRequest(roma, torino, LocalDateTime.parse("2026-11-21T$time"), followsCurrentTime = true) }
        val search = searched(roma, torino, "2026-11-21T17:00")
        // Same request a minute later: still answered, so the options do not vanish, and the key does not change.
        assertTrue(at("17:01").isAnsweredBy(search))
        assertEquals(at("17:00").key, at("17:42").key)
        assertFalse(at("17:10").needsSearch(search))
        // Coming back to the results more than 15 minutes later computes them again from the new time.
        assertTrue(at("17:16").isAnsweredBy(search))
        assertTrue(at("17:16").needsSearch(search))
        // A search for a later time, another day, or other stations never answers "now".
        assertFalse(at("16:59").isAnsweredBy(search))
        assertFalse(JourneyRequest(roma, torino, LocalDateTime.parse("2026-11-22T17:00"), followsCurrentTime = true).isAnsweredBy(search))
        assertFalse(JourneyRequest(roma, milano, LocalDateTime.parse("2026-11-21T17:01"), followsCurrentTime = true).isAnsweredBy(search))
        // Planned and current-time requests for the same stations ask for different searches.
        assertFalse(at("17:00").key == JourneyRequest(roma, torino, LocalDateTime.parse("2026-11-21T17:00"), followsCurrentTime = false).key)
    }
}
