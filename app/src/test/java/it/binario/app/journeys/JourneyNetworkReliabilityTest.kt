package it.binario.app.journeys

import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.GtfsJourneyPlannerSettings
import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.data.reliability.ReliabilityFixtures.FR_9558
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2041
import it.binario.app.data.repository.SourcedJourneyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyNetworkReliabilityTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val stats = ReliabilityFixtures.stats(FR_9558, REG_2041, ReliabilityFixtures.REG_2155_CANCELLED)
    private val code: (String) -> String? = { id -> id.removePrefix("curated:").takeIf { it.startsWith("S") } }

    private fun leg(number: String?, from: String, to: String, departs: String, arrives: String, mode: LegMode = LegMode.TRAIN) = JourneyLeg(
        mode = mode,
        origin = StationId("curated:$from"),
        destination = StationId("curated:$to"),
        scheduledDeparture = LocalDateTime.parse("2026-11-10T$departs").atZone(zone),
        scheduledArrival = LocalDateTime.parse("2026-11-10T$arrives").atZone(zone),
        publicNumber = number,
        lineLabel = if (mode == LegMode.TRAIN) "REG" else null,
        originLabel = NAMES[from] ?: from,
        destinationLabel = NAMES[to] ?: to,
        serviceKind = if (mode == LegMode.TRAIN) ServiceKind.REGIONAL else ServiceKind.UNKNOWN
    )

    private fun row(vararg legs: JourneyLeg): JourneyOptionRow = JourneyResultsPresenter.row(
        SourcedJourneyOption(
            option = JourneyOption(
                id = JourneyId("j"), origin = legs.first().origin, destination = legs.last().destination,
                departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs.toList(),
                snapshot = SnapshotMeta(1, Instant.parse("2026-11-10T06:00:00Z"), freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
            ),
            sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
            sourceName = "Orario offline",
            scheduledOnly = true
        ),
        zone
    )

    @Test fun `a change is measured on the arriving train's delays against the slack after the change time`() {
        // Roma 07:00 → Milano 10:00, Milano 10:17 → Torino 11:20: 17 minutes, 10 of them the large-hub change time.
        val journey = row(leg("9558", "S08409", "S01700", "07:00", "10:00"), leg("2041", "S01700", "S00219", "10:17", "11:20"))
        val reliability = requireNotNull(JourneyNetworkReliabilityAssessor.assess(journey, stats, code))
        assertEquals(listOf(0, 1), reliability.legs.map { it.legIndex })
        val change = reliability.connections.single()
        assertEquals(0, change.afterLegIndex)
        assertEquals("Milano Centrale", change.stationLabel)
        assertEquals(7, change.slackMinutes)
        // The 9558 at Milano Centrale: 22 within 5 minutes, 26 within 10 → 23.6 of 30 within 7.
        assertEquals(23, change.odds.successes)
        assertEquals(30, change.odds.samples)
        val frecciarossa = reliability.legs[0].stop
        val regional = reliability.legs[1].stop
        assertEquals(22, frecciarossa.onTime)
        assertEquals(29, regional.onTime)
        assertEquals(frecciarossa.score * regional.score * (23.6 / 30), reliability.score, 1e-9)
        // The weakest leg is the Frecciarossa (22 of 30 within 5 minutes, one cancellation).
        assertEquals(0, reliability.weakestLeg.legIndex)
        assertEquals(change, reliability.weakestConnection)
        assertEquals(3.0, reliability.meanDelayAtArrival, 1e-9)
    }

    @Test fun `the change time is the planner's own, and a walk between the trains replaces it`() {
        val settings = GtfsJourneyPlannerSettings()
        assertEquals(10, settings.railTransferMinutes("S01700"))
        assertEquals(5, settings.railTransferMinutes("S99999"))
        assertEquals(5, settings.railTransferMinutes(null))
        // A six-minute walk to another station (the planner's walk already holds its change time): 17 − 6 = 11.
        val walking = row(
            leg("9558", "S08409", "S01700", "07:00", "10:00"),
            leg(null, "S01700", "S01701", "10:00", "10:06", mode = LegMode.WALK),
            leg("2041", "S01701", "S00219", "10:17", "11:20")
        )
        val change = requireNotNull(JourneyNetworkReliabilityAssessor.assess(walking, stats, code)).connections.single()
        assertEquals(0, change.afterLegIndex)
        assertEquals(11, change.slackMinutes)
        // 26 within 10 minutes, 28 within 15: 26.4 of 30 within 11.
        assertEquals(26, change.odds.successes)
        val custom = requireNotNull(JourneyNetworkReliabilityAssessor.assess(
            row(leg("9558", "S08409", "S01700", "07:00", "10:00"), leg("2041", "S01700", "S00219", "10:17", "11:20")),
            stats, code, transferMinutes = { 2 }
        ))
        assertEquals(15, custom.connections.single().slackMinutes)
    }

    @Test fun `a leg on a train cancelled every day makes the journey's score zero`() {
        val journey = row(leg("9558", "S08409", "S01700", "07:00", "10:00"), leg("2155", "S01700", "S00219", "10:17", "11:20"))
        val reliability = requireNotNull(JourneyNetworkReliabilityAssessor.assess(journey, stats, code))
        assertEquals(0.0, reliability.score, 0.0)
        assertEquals(1, reliability.weakestLeg.legIndex)
        assertEquals(true, reliability.weakestLeg.stop.cancelledEveryDay)
        // The change after the Frecciarossa is still measured on its delays.
        assertEquals(23, reliability.connections.single().odds.successes)
    }

    @Test fun `a train leg without statistics leaves the journey unmeasured, a metro leg is not measured`() {
        assertNull(JourneyNetworkReliabilityAssessor.assess(
            row(leg("9558", "S08409", "S01700", "07:00", "10:00"), leg("4444", "S01700", "S00219", "10:17", "11:20")), stats, code
        ))
        // A station without a code (a stop of a city network) cannot be matched.
        assertNull(JourneyNetworkReliabilityAssessor.assess(row(leg("9558", "S08409", "milano-metro", "07:00", "10:00")), stats, code))
        val withMetro = requireNotNull(JourneyNetworkReliabilityAssessor.assess(
            row(leg("9558", "S08409", "S01700", "07:00", "10:00"), leg("M2", "S01700", "garibaldi", "10:15", "10:25", mode = LegMode.METRO)),
            stats, code
        ))
        assertEquals(1, withMetro.legs.size)
        // The change to the metro is still measured on the arriving train: 15 − 10 = 5 minutes, 22 of 30.
        assertEquals(22, withMetro.connections.single().odds.successes)
        assertNull(JourneyNetworkReliabilityAssessor.assess(row(leg("M2", "S01700", "garibaldi", "10:15", "10:25", mode = LegMode.METRO)), stats, code))
    }

    private companion object {
        val NAMES = mapOf("S08409" to "Roma Termini", "S01700" to "Milano Centrale", "S00219" to "Torino Porta Nuova")
    }
}
