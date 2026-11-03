package it.binario.app.journeys

import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.data.urban.UrbanGtfsRealtimeAdapter
import it.binario.app.data.urban.UrbanRealtimeFeeds
import it.binario.app.data.urban.UrbanRealtimeFetcher
import it.binario.app.data.urban.UrbanRealtimeTest
import it.binario.app.data.live.MutablePublicSourceClock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrbanJourneyLiveLookupTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T07:58:00Z")
    private val rome = SourceId("roma-mobilita-gtfs")

    private fun lookup(fetcher: UrbanRealtimeFetcher): Pair<UrbanJourneyLiveLookup, () -> Int> {
        var calls = 0
        val adapter = UrbanGtfsRealtimeAdapter(UrbanRealtimeFeeds.ROMA, UrbanRealtimeFetcher { calls += 1; fetcher.fetch(it) }, MutablePublicSourceClock(now))
        return UrbanJourneyLiveLookup(mapOf(rome to adapter)) { now } to { calls }
    }

    private fun leg(mode: LegMode, run: String?, from: String, to: String, departure: ZonedDateTime, arrival: ZonedDateTime, number: String? = null) = JourneyLeg(
        mode = mode,
        runId = run?.let(::TrainRunId),
        origin = StationId(from),
        destination = StationId(to),
        scheduledDeparture = departure,
        scheduledArrival = arrival,
        publicNumber = number
    )

    private fun option(vararg legs: JourneyLeg) = SourcedJourneyOption(
        option = JourneyOption(
            id = JourneyId("j"),
            origin = legs.first().origin,
            destination = legs.last().destination,
            departure = legs.first().scheduledDeparture,
            arrival = legs.last().scheduledArrival,
            legs = legs.toList(),
            snapshot = SnapshotMeta(1, now, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
        ),
        sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
        sourceName = "Orario offline",
        scheduledOnly = true
    )

    private val nine = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone)
    private val tram = leg(LegMode.TRAM, "roma-mobilita-gtfs:1#720-2:2026-11-03", "gtfs:roma-mobilita-gtfs:70002", "gtfs:roma-mobilita-gtfs:70003", nine, nine.plusMinutes(3))
    private val metro = leg(LegMode.METRO, "roma-mobilita-gtfs:2#100-1:2026-11-03", "gtfs:roma-mobilita-gtfs:M1", "gtfs:roma-mobilita-gtfs:M2", nine.plusMinutes(10), nine.plusMinutes(30))
    private val train = leg(LegMode.TRAIN, "trenitalia-gtfs:123:2026-11-03", "S08409", "S06421", nine.plusHours(1), nine.plusHours(3), number = "9541")

    @Test
    fun `metro and tram legs get the expected times of their own run and stops`() = runBlocking {
        val (lookup, calls) = lookup { UrbanRealtimeTest.romeFeed().toByteArray() }
        val options = listOf(option(tram, metro, train))
        assertTrue(lookup.wants(options))
        val live = lookup.lookup(options)
        val tramLive = live.getValue(TransferRiskCalculator.legKey(tram))
        assertEquals(nine.plusMinutes(2).toInstant(), tramLive.expectedDeparture)
        // The alighting stop is skipped in the update: the leg is cancelled there.
        assertTrue(tramLive.cancelled)
        assertEquals("Roma Servizi per la Mobilità", tramLive.sourceLabel)
        assertEquals(Instant.ofEpochSecond(1_793_692_620), tramLive.observedAt)
        assertTrue(live.getValue(TransferRiskCalculator.legKey(metro)).cancelled)
        assertNull(live[TransferRiskCalculator.legKey(train)])
        assertEquals(1, calls())
    }

    @Test
    fun `legs far from now, of another city or with an unreadable run id do not ask`() = runBlocking {
        val (lookup, calls) = lookup { UrbanRealtimeTest.romeFeed().toByteArray() }
        val tomorrow = leg(LegMode.TRAM, "roma-mobilita-gtfs:1#720-2:2026-11-04", "gtfs:roma-mobilita-gtfs:70002", "gtfs:roma-mobilita-gtfs:70003", nine.plusDays(1), nine.plusDays(1).plusMinutes(3))
        val milan = leg(LegMode.METRO, "milano-atm-gtfs:x:2026-11-03", "gtfs:milano-atm-gtfs:1", "gtfs:milano-atm-gtfs:2", nine, nine.plusMinutes(5))
        val broken = leg(LegMode.TRAM, "roma-mobilita-gtfs", "gtfs:roma-mobilita-gtfs:70002", "gtfs:roma-mobilita-gtfs:70003", nine, nine.plusMinutes(3))
        val options = listOf(option(tomorrow), option(milan), option(broken))
        assertFalse(lookup.wants(options))
        assertTrue(lookup.lookup(options).isEmpty())
        assertEquals(0, calls())
    }

    @Test
    fun `an unavailable feed leaves the legs scheduled`() = runBlocking {
        val (lookup, _) = lookup { throw IOException("rete") }
        assertTrue(lookup.lookup(listOf(option(tram))).isEmpty())
    }

    @Test
    fun `the detail shows the live times and the source of a city leg`() {
        val live = mapOf(TransferRiskCalculator.legKey(metro) to JourneyLegLive(
            expectedDeparture = nine.plusMinutes(12).toInstant(), expectedArrival = nine.plusMinutes(33).toInstant(),
            sourceLabel = "Roma Servizi per la Mobilità", observedAt = now
        ))
        val row = JourneyResultsPresenter.row(option(metro), zone, live)
        val leg = row.legs.single()
        assertEquals("09:12", leg.expectedDepartureTime)
        assertEquals("09:33", leg.expectedArrivalTime)
        assertEquals(2, leg.departureDelayMinutes)
        assertEquals(3, leg.arrivalDelayMinutes)
        assertEquals("08:58", leg.liveObservedTime)
        val scheduled = JourneyResultsPresenter.row(option(metro), zone).legs.single()
        assertNull(scheduled.expectedDepartureTime)
        assertNull(scheduled.liveObservedTime)
    }
}
