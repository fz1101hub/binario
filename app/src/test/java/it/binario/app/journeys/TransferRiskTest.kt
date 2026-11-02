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
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferRiskTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(time: String): Instant = LocalDateTime.parse("2026-11-02T$time").atZone(zone).toInstant()
    private val five = Duration.ofMinutes(5)

    @Test fun `on time, at risk, missed and unknown from expected arrival plus change time`() {
        val arrival = at("09:00")
        val departure = at("09:10")

        val unknown = TransferRiskCalculator.assess(arrival, null, departure, null)
        val onTime = TransferRiskCalculator.assess(arrival, JourneyLegLive(expectedArrival = at("09:04")), departure, null)
        val atRisk = TransferRiskCalculator.assess(arrival, JourneyLegLive(expectedArrival = at("09:07")), departure, null)
        val missed = TransferRiskCalculator.assess(arrival, JourneyLegLive(expectedArrival = at("09:12")), departure, null)
        val savedByLateDeparture = TransferRiskCalculator.assess(arrival, JourneyLegLive(expectedArrival = at("09:12")), departure,
            JourneyLegLive(expectedDeparture = at("09:20")))

        assertEquals(TransferRiskLevel.UNKNOWN, unknown.level)
        assertEquals(TransferRiskLevel.OK, onTime.level)
        assertEquals(1L, onTime.marginMinutes)
        assertEquals(TransferRiskLevel.AT_RISK, atRisk.level)
        assertEquals(-2L, atRisk.marginMinutes)
        assertEquals(TransferRiskLevel.MISSED, missed.level)
        assertEquals(TransferRiskLevel.OK, savedByLateDeparture.level)
    }

    @Test fun `a cancelled train loses the connection and the worst change marks the row`() {
        val cancelled = TransferRiskCalculator.assess(at("09:00"), JourneyLegLive(cancelled = true), at("09:30"), null)

        assertEquals(TransferRiskLevel.MISSED, cancelled.level)
        assertEquals(TransferRiskLevel.AT_RISK, TransferRiskLevel.worst(listOf(TransferRiskLevel.OK, TransferRiskLevel.AT_RISK, TransferRiskLevel.UNKNOWN)))
        assertEquals(TransferRiskLevel.UNKNOWN, TransferRiskLevel.worst(emptyList()))
    }

    @Test fun `the change time is the walk between the vehicles, else the default within the scheduled wait`() {
        assertEquals(Duration.ofMinutes(3), TransferRiskCalculator.defaultTransfer(Duration.ofMinutes(3)))
        assertEquals(five, TransferRiskCalculator.defaultTransfer(Duration.ofMinutes(20)))

        val legs = listOf(
            leg("2153", "Milano Centrale", "Monza", "08:30", "08:50"),
            JourneyLeg(LegMode.WALK, origin = StationId("monza"), destination = StationId("monza-bus"),
                scheduledDeparture = zoned("08:50"), scheduledArrival = zoned("09:02")),
            leg("5521", "Monza", "Lecco", "09:05", "09:40")
        )
        val live = mapOf(TransferRiskCalculator.legKey(legs[0]) to JourneyLegLive(expectedArrival = at("08:55")))

        val change = TransferRiskCalculator.assessChange(legs, 2, live)!!

        // 08:55 + the 12 planned minutes of walk = 09:07, after the 09:05 departure, but half the walk still fits: at risk.
        assertEquals(TransferRiskLevel.AT_RISK, change.level)
        val late = mapOf(TransferRiskCalculator.legKey(legs[0]) to JourneyLegLive(expectedArrival = at("09:01")))
        assertEquals(TransferRiskLevel.MISSED, TransferRiskCalculator.assessChange(legs, 2, late)!!.level)
        val onTime = mapOf(TransferRiskCalculator.legKey(legs[0]) to JourneyLegLive(expectedArrival = at("08:50")))
        assertEquals(TransferRiskLevel.OK, TransferRiskCalculator.assessChange(legs, 2, onTime)!!.level)
        assertNull(TransferRiskCalculator.assessChange(legs, 0, live))
    }

    @Test fun `the presenter puts the risk on the change and on the row only with live data`() {
        val first = leg("2153", "Milano Centrale", "Monza", "08:30", "08:50", run = "trenord:2153")
        val second = leg("5521", "Monza", "Lecco", "08:58", "09:40", run = "trenord:5521")
        val result = JourneySearchResult(JourneySearchStatus.FOUND, listOf(option(first, second)), emptyList(), false, Instant.EPOCH)

        val scheduled = JourneyResultsPresenter.present(result).rows.single()
        val live = JourneyResultsPresenter.present(result, live = mapOf("trenord:2153" to JourneyLegLive(expectedArrival = at("08:55")))).rows.single()

        assertEquals(TransferRiskLevel.UNKNOWN, scheduled.transferRisk)
        assertNull(scheduled.transfers.single().expectedArrivalTime)
        assertEquals(TransferRiskLevel.AT_RISK, live.transferRisk)
        assertEquals("08:55", live.transfers.single().expectedArrivalTime)
        assertEquals("08:58", live.transfers.single().expectedDepartureTime)
    }

    @Test fun `live times of a leg come from the matching run's verified stops`() {
        val leg = leg("2153", "Milano Centrale", "Monza", "08:30", "08:50")
        val run = service(
            "2153",
            stops = listOf(stop("MILANO CENTRALE", "08:30", "08:34", 4), stop("SESTO S. GIOVANNI", "08:41", "08:46", 5), stop("MONZA", "08:50", "08:57", 7)),
            delay = 7
        )
        val scheduledOnly = run.copy(operationalStatusVerified = false, stops = run.stops.map { it.copy(operationalStatusVerified = false) })

        val live = JourneyLiveCandidates.liveFor(leg, listOf(run))!!

        assertEquals(at("08:34"), live.expectedDeparture)
        assertEquals(at("08:57"), live.expectedArrival)
        assertNull(JourneyLiveCandidates.liveFor(leg, listOf(scheduledOnly)))
        assertEquals(JourneyLegLive(cancelled = true), JourneyLiveCandidates.liveFor(leg, listOf(run.copy(status = ServiceStatus.CANCELLED))))
    }

    @Test fun `only train legs of journeys with changes running around now are looked up`() {
        val now = at("08:00")
        val soon = leg("2153", "Milano Centrale", "Monza", "08:30", "08:50")
        val later = leg("5521", "Monza", "Lecco", "12:30", "13:10")
        val direct = leg("9999", "Milano Centrale", "Lecco", "08:10", "09:00")
        val options = listOf(option(soon, later), option(direct))

        val legs = JourneyLiveCandidates.legs(options, now)

        assertEquals(listOf("2153"), legs.map { it.publicNumber })
        assertEquals(listOf(JourneyLiveCandidates.Train("2153", java.time.LocalDate.of(2026, 11, 2))), JourneyLiveCandidates.trains(legs, zone))
    }

    @Test fun `a clock time near midnight is placed on the right day`() {
        assertEquals(at("23:55").plus(Duration.ofMinutes(15)), LiveStopTimes.nearest(at("23:55"), java.time.LocalTime.of(0, 10), zone))
        assertEquals(at("00:05").minus(Duration.ofMinutes(10)), LiveStopTimes.nearest(at("00:05"), java.time.LocalTime.of(23, 55), zone))
    }

    private fun zoned(time: String) = LocalDateTime.parse("2026-11-02T$time").atZone(zone)

    private fun leg(number: String, from: String, to: String, departs: String, arrives: String, run: String? = null) = JourneyLeg(
        mode = LegMode.TRAIN,
        runId = run?.let(::TrainRunId),
        origin = StationId(from.lowercase()),
        destination = StationId(to.lowercase()),
        scheduledDeparture = zoned(departs),
        scheduledArrival = zoned(arrives),
        publicNumber = number,
        originLabel = from,
        destinationLabel = to
    )

    private fun option(vararg legs: JourneyLeg) = SourcedJourneyOption(
        option = JourneyOption(
            id = JourneyId(legs.joinToString("-") { it.publicNumber.orEmpty() }), origin = legs.first().origin, destination = legs.last().destination,
            departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs.toList(),
            snapshot = SnapshotMeta(1, Instant.EPOCH, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
        ),
        sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
        sourceName = "Orario offline",
        scheduledOnly = true
    )

    private fun stop(station: String, scheduled: String, expected: String, delay: Int) =
        TrainStop(station, scheduled, expected, null, delay, completed = false, operationalStatusVerified = true)

    private fun service(number: String, stops: List<TrainStop>, delay: Int) = TrainService(
        id = "viaggiatreno:S01700:$number:1",
        number = number,
        operator = RailOperator.TRENITALIA_REGIONALE,
        trainCategory = TrainCategory.REGIONALE,
        origin = stops.first().station,
        destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled,
        expectedDeparture = stops.first().expected,
        platform = null,
        delayMinutes = delay,
        status = ServiceStatus.DELAYED,
        stops = stops
    )
}
