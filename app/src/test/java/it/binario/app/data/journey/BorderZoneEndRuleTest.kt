package it.binario.app.data.journey

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.GtfsBorderZone
import it.binario.app.data.gtfs.directConnections
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.beta
import it.binario.app.data.journey.JourneyFixtures.delta
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.gamma
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.ByteArrayInputStream
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §1.1 rule 5 and §3.2: while only the border subset of a foreign pack is read, the pack covers a search end
 * only at its zone stations; its trains still show every stop, and a change elsewhere inside a covered journey stays.
 */
class BorderZoneEndRuleTest {
    /** A cross-border line: Alfa and Beta in the zone, Gamma beyond it. */
    private val line = feed(listOf(
        Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30", "C" to "09:30")),
        Trip("T2", "101", listOf("C" to "10:00", "B" to "11:00", "A" to "11:30"))
    ))
    private val whole = pack("ch-otd-gtfs")
    private val border = whole.copy(borderZone = GtfsBorderZone(setOf("A", "B"), servedStationIds = setOf("A", "B", "C")))

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun notCovered(result: ProviderResult<List<JourneyOption>>) =
        result is ProviderResult.Failure && result.error.kind == ProviderErrorKind.NOT_SUPPORTED

    @Test
    fun `the whole pack covers every station it serves`() = runBlocking {
        val provider = provider(mapOf(whole to line))

        assertEquals("100", options(provider.searchJourneys(request(alfa, gamma))).single().legs.single().publicNumber)
        assertTrue(whole.coversEnd(gamma.id))
        assertTrue(!border.coversEnd(gamma.id) && border.coversEnd(beta.id))
    }

    @Test
    fun `a station the pins do not list as served stays covered by the subset`() {
        // Delta is no station of the full pack for the tools' matcher: the subset keeps the trains it has there.
        assertTrue(border.coversEnd(delta.id) && border.coversEnd(alfa.id))
        assertTrue(!border.coversEnd(gamma.id) && !border.coversEnd(null))
        assertTrue(GtfsBorderZone(emptySet(), setOf("C")).contains("C").not())
    }

    @Test
    fun `the border subset covers zone ends only`() = runBlocking {
        val provider = provider(mapOf(border to line))

        val zone = options(provider.searchJourneys(request(alfa, beta))).single()
        assertEquals("100", zone.legs.single().publicNumber)
        assertTrue(notCovered(provider.searchJourneys(request(alfa, gamma))))
        assertTrue(notCovered(provider.searchJourneys(request(gamma, alfa, time = "09:45"))))
    }

    @Test
    fun `a change at a station beyond the zone stays possible inside a covered journey`() = runBlocking {
        val other = pack("lombardia-trenord-gtfs")
        val onward = feed(listOf(Trip("T9", "900", listOf("C" to "09:45", "D" to "10:15"))))
        val provider = provider(mapOf(border to line, other to onward))

        val journey = options(provider.searchJourneys(request(alfa, delta))).first()

        assertEquals(listOf("100", "900"), journey.legs.filter { it.publicNumber != null }.map { it.publicNumber })
    }

    @Test
    fun `boards and last-of-day marks are not built from the subset beyond the zone`() = runBlocking {
        val provider = provider(mapOf(border to line))

        assertTrue(provider.staticDayDepartures(StationId(gamma.id), null, null, null, monday).lastByTerminus.isEmpty())
        assertTrue(provider.staticDayDepartures(StationId(alfa.id), null, StationId(gamma.id), null, monday).lastByTerminus.isEmpty())
        assertEquals(listOf("Gamma"), provider.staticDayDepartures(StationId(alfa.id), null, null, null, monday).lastByTerminus.map { it.terminus })
        assertTrue(provider.directConnections(StationId(gamma.id), null, monday).isEmpty)
        // From a zone station the trains keep their real stops, Gamma included.
        assertEquals(setOf("Beta", "Gamma"), provider.directConnections(StationId(alfa.id), null, monday).rail.map { it.name }.toSet())
    }

    @Test
    fun `the route search needs both ends in the zone`() = runBlocking {
        fun adapter(pack: EmbeddedGtfsPack) = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(line) }, packs = listOf(pack))
        fun route(to: Station) = SavedRoute("r", "r", alfa, to)
        val query = JourneyQuery(monday, LocalTime.of(7, 30))

        val zone = adapter(border).loadRoute(route(beta), Direction.OUTBOUND, query)
        val beyond = adapter(border).loadRoute(route(gamma), Direction.OUTBOUND, query)
        val whole = adapter(whole).loadRoute(route(gamma), Direction.OUTBOUND, query)

        assertEquals(listOf("100"), (zone as DataResult.Success).snapshot.services.map { it.number })
        assertTrue(beyond is DataResult.Failure && beyond.reason == RouteFailureReason.RouteNotCovered)
        assertEquals(listOf("100"), (whole as DataResult.Success).snapshot.services.map { it.number })
    }
}
