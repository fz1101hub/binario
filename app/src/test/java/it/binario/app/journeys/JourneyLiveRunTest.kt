package it.binario.app.journeys

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.ui.planner.ResultEntry
import it.binario.app.ui.planner.ResultsMerge
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run whose live data a journey leg shows, among the runs the number search returns (for a night train that search
 * also returns yesterday's run, still on its way today, and the static timetable's copy of today's run), and the live
 * risk of a change on the result cards.
 */
class JourneyLiveRunTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)

    private val leg = JourneyLeg(
        mode = LegMode.TRAIN, runId = TrainRunId("trenitalia-gtfs:T1963:$today"),
        origin = StationId("mi"), destination = StationId("na"),
        scheduledDeparture = today.atTime(18, 45).atZone(zone), scheduledArrival = today.plusDays(1).atTime(1, 30).atZone(zone),
        publicNumber = "1963", originLabel = "Milano Centrale", destinationLabel = "Napoli Centrale", serviceDate = today
    )

    private fun stop(station: String, scheduled: String, expected: String, delay: Int, verified: Boolean) =
        TrainStop(station, scheduled, expected, null, delay, completed = verified, operationalStatusVerified = verified)

    private fun run(id: String, start: LocalDateTime?, verified: Boolean, departure: String, arrival: String, delays: Pair<Int, Int>) = TrainService(
        id = id, number = "1963", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.INTERCITY,
        origin = "MILANO CENTRALE", destination = "PALERMO CENTRALE", scheduledDeparture = "18:45", expectedDeparture = departure,
        platform = null, delayMinutes = delays.second, status = ServiceStatus.DELAYED, operationalStatusVerified = verified,
        scheduledDepartureDateTime = start,
        stops = listOf(
            stop("MILANO CENTRALE", "18:45", departure, delays.first, verified),
            stop("NAPOLI CENTRALE", "01:30", arrival, delays.second, verified),
            stop("PALERMO CENTRALE", "16:55", "16:55", delays.second, verified)
        )
    )

    /** Yesterday's 18:45 run: left 7 minutes late, reached Napoli 28 minutes late, still on its way to Palermo. */
    private val yesterday = run("viaggiatreno:1963:y", today.minusDays(1).atTime(18, 45), true, "18:52", "01:58", 7 to 28)

    @Test fun `yesterday's run of a night train is never taken for today's`() {
        // Today's run has not left yet: no live data, rather than yesterday's delays.
        val todays = run("viaggiatreno:1963:t", today.atTime(18, 45), false, "18:45", "01:30", 0 to 0)
        assertNull(JourneyLiveCandidates.liveFor(leg, listOf(yesterday, todays), zone))
        assertNull(JourneyLiveCandidates.liveFor(leg, listOf(yesterday), zone))
    }

    @Test fun `today's verified run wins over the static copy of it`() {
        val staticCopy = run("embedded-gtfs:1963", today.atTime(18, 45), false, "18:45", "01:30", 0 to 0)
        val live = run("viaggiatreno:1963:t", today.atTime(18, 45), true, "18:50", "01:40", 5 to 10)
        val found = JourneyLiveCandidates.liveFor(leg, listOf(yesterday, staticCopy, live), zone)!!
        assertEquals(today.atTime(18, 50).atZone(zone).toInstant(), found.expectedDeparture)
        // 01:40 of the run that left today is tomorrow's.
        assertEquals(today.plusDays(1).atTime(1, 40).atZone(zone).toInstant(), found.expectedArrival)
    }

    @Test fun `a run without a date is still used, as before`() {
        val undated = run("viaggiatreno:1963:t", null, true, "18:50", "01:40", 5 to 10)
        assertEquals(today.atTime(18, 50).atZone(zone).toInstant(), JourneyLiveCandidates.liveFor(leg, listOf(undated), zone)?.expectedDeparture)
    }

    private fun train(number: String, from: String, to: String, departs: LocalDateTime, arrives: LocalDateTime) = JourneyLeg(
        mode = LegMode.TRAIN, runId = TrainRunId("trenord:$number"), origin = StationId(from.lowercase()), destination = StationId(to.lowercase()),
        scheduledDeparture = departs.atZone(zone), scheduledArrival = arrives.atZone(zone), publicNumber = number,
        originLabel = from, destinationLabel = to, serviceDate = today
    )

    private fun result(vararg legs: JourneyLeg) = JourneySearchResult(
        JourneySearchStatus.FOUND,
        listOf(
            SourcedJourneyOption(
                option = JourneyOption(
                    id = JourneyId(legs.joinToString("-") { it.publicNumber.orEmpty() }), origin = legs.first().origin, destination = legs.last().destination,
                    departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs.toList(),
                    snapshot = SnapshotMeta(1, Instant.EPOCH, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
                ),
                sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
                sourceName = "Orario offline",
                scheduledOnly = true
            )
        ),
        emptyList(), false, Instant.EPOCH
    )

    @Test fun `the result cards carry the live risk of a change, not only the detail`() {
        val first = train("2153", "Milano Centrale", "Monza", today.atTime(8, 30), today.atTime(8, 50))
        val second = train("5521", "Monza", "Lecco", today.atTime(8, 58), today.atTime(9, 40))
        val live = mapOf("trenord:2153" to JourneyLegLive(expectedArrival = today.atTime(8, 55).atZone(zone).toInstant()))
        val rows = ResultsMerge.journeys(result(first, second), null, live).rows
        assertEquals(TransferRiskLevel.AT_RISK, rows.single().transferRisk)
        val entry = ResultsMerge.entries(emptyList(), rows, today).single() as ResultEntry.Journey
        assertEquals(TransferRiskLevel.AT_RISK, entry.row.transferRisk)
        assertEquals("08:55", entry.row.legs.first().expectedArrivalTime)
        assertEquals(TransferRiskLevel.UNKNOWN, ResultsMerge.journeys(result(first, second), null, emptyMap()).rows.single().transferRisk)
    }

    @Test fun `a card names a change with hours of waiting`() {
        val first = train("2153", "Milano Centrale", "Monza", today.atTime(8, 30), today.atTime(8, 50))
        val short = ResultsMerge.journeys(result(first, train("5521", "Monza", "Lecco", today.atTime(8, 58), today.atTime(9, 40))), null, emptyMap()).rows.single()
        assertEquals(8L, short.longestWaitMinutes)
        assertFalse(short.hasLongWait)
        val long = ResultsMerge.journeys(result(first, train("5577", "Monza", "Lecco", today.atTime(13, 30), today.atTime(14, 10))), null, emptyMap()).rows.single()
        assertEquals(280L, long.longestWaitMinutes)
        assertTrue(long.hasLongWait)
    }
}
