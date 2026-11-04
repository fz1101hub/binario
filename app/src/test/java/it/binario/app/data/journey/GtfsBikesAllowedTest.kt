package it.binario.app.data.journey

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.gtfs.BIKES_NO
import it.binario.app.data.gtfs.BIKES_UNKNOWN
import it.binario.app.data.gtfs.BIKES_YES
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.bikesAcrossTrips
import it.binario.app.data.gtfs.bikesOfCopies
import it.binario.app.data.gtfs.bikesOnBoard
import it.binario.app.data.gtfs.bikesValue
import it.binario.app.data.gtfs.packBikesValue
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.delta
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.gamma
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.provider.ProviderResult
import it.binario.app.journeys.JourneyBikes
import it.binario.app.links.OfficialLinks
import it.binario.app.links.OperatorLinkKind
import java.io.ByteArrayInputStream
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GTFS bikes_allowed from trips.txt to the direct trains, the journey legs and the operator's own conditions page. */
class GtfsBikesAllowedTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> =
        (result as? ProviderResult.Success)?.value ?: throw AssertionError("Expected a result, got $result")

    @Test fun `only 1 and 2 are published values, anything else is unknown`() {
        assertEquals(BIKES_YES, bikesValue("1"))
        assertEquals(BIKES_YES, bikesValue(" 1 "))
        assertEquals(BIKES_NO, bikesValue("2"))
        listOf("0", "", " ", "3", "yes", null).forEach { assertEquals("'$it'", BIKES_UNKNOWN, bikesValue(it)) }
        assertEquals(true, bikesOnBoard(BIKES_YES))
        assertEquals(false, bikesOnBoard(BIKES_NO))
        assertNull(bikesOnBoard(BIKES_UNKNOWN))
    }

    @Test fun `a vehicle running on as other trips allows bikes only when every trip does`() {
        assertEquals(BIKES_YES, bikesAcrossTrips(BIKES_YES, BIKES_YES))
        assertEquals(BIKES_NO, bikesAcrossTrips(BIKES_YES, BIKES_NO))
        assertEquals(BIKES_NO, bikesAcrossTrips(BIKES_UNKNOWN, BIKES_NO))
        assertEquals(BIKES_UNKNOWN, bikesAcrossTrips(BIKES_YES, BIKES_UNKNOWN))
        // Two packs' copies of one run: the one that publishes it says it; two different values say nothing.
        assertEquals(BIKES_YES, bikesOfCopies(BIKES_UNKNOWN, BIKES_YES))
        assertEquals(BIKES_NO, bikesOfCopies(BIKES_NO, BIKES_UNKNOWN))
        assertEquals(BIKES_YES, bikesOfCopies(BIKES_YES, BIKES_YES))
        assertEquals(BIKES_UNKNOWN, bikesOfCopies(BIKES_YES, BIKES_NO))
    }

    @Test fun `direct trains carry the published value, and nothing without it or without the column`() = runBlocking {
        val trips = listOf(
            Trip("Y", "100", listOf("A" to "08:00", "D" to "08:40"), bikes = "1"),
            Trip("N", "200", listOf("A" to "09:00", "D" to "09:40"), bikes = "2"),
            Trip("E", "300", listOf("A" to "10:00", "D" to "10:40"), bikes = ""),
            Trip("Z", "400", listOf("A" to "11:00", "D" to "11:40"), bikes = "0")
        )
        val route = SavedRoute("test", "Alfa – Delta", alfa, delta)
        suspend fun services(bytes: ByteArray) = (
            EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(bytes) }, packs = listOf(pack()), clock = { 1_000L })
                .loadRoute(route, Direction.OUTBOUND, JourneyQuery(monday, LocalTime.of(7, 0))) as DataResult.Success
            ).snapshot.services.associate { it.number to it.bikesAllowed }
        assertEquals(mapOf("100" to true, "200" to false, "300" to null, "400" to null), services(feed(trips)))
        // Trenord publishes no bikes_allowed column at all.
        assertEquals(mapOf("100" to null, "200" to null, "300" to null, "400" to null), services(feed(trips, bikesColumn = false)))
    }

    @Test fun `the Italo pack's value is not shown, as Italo admits only folding bikes in a bag`() = runBlocking {
        val trips = listOf(Trip("I", "9901", listOf("A" to "08:00", "D" to "08:40"), bikes = "1"), Trip("J", "9903", listOf("A" to "09:00", "D" to "09:40"), bikes = "2"))
        val italo = pack("italo-gtfs")
        val route = SavedRoute("test", "Alfa – Delta", alfa, delta)
        val direct = (
            EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(feed(trips)) }, packs = listOf(italo), clock = { 1_000L })
                .loadRoute(route, Direction.OUTBOUND, JourneyQuery(monday, LocalTime.of(7, 0))) as DataResult.Success
            ).snapshot.services
        assertEquals(listOf<Boolean?>(null, null), direct.map { it.bikesAllowed })
        val legs = options(provider(mapOf(italo to feed(trips))).searchJourneys(request(alfa, delta, time = "07:50"))).map { it.legs.single() }
        assertEquals(listOf("9901", "9903"), legs.map { it.publicNumber })
        assertEquals(listOf<Boolean?>(null, null), legs.map { it.bikesAllowed })
        // The override names the pack: the same values in another pack are shown as published.
        assertEquals(BIKES_UNKNOWN, packBikesValue("italo-gtfs", BIKES_YES))
        assertEquals(BIKES_UNKNOWN, packBikesValue("italo-gtfs", BIKES_NO))
        assertEquals(BIKES_YES, packBikesValue("alto-adige-sta-gtfs", BIKES_YES))
        assertEquals(BIKES_NO, packBikesValue("trenitalia-gtfs", BIKES_NO))
        // No Italo bikes page is known to Binario: the Italo detail offers no conditions row.
        assertNull(OfficialLinks.operatorOf(RailOperator.ITALO, "Italo")?.url(OperatorLinkKind.BIKES))
    }

    @Test fun `journey legs carry it, through the trips the vehicle runs on as`() = runBlocking {
        val provider = provider(
            mapOf(
                pack() to feed(
                    listOf(
                        Trip("Y", "100", listOf("A" to "08:00", "D" to "08:40"), bikes = "1"),
                        // One vehicle: allowed on the first trip, not on the next one it runs on as.
                        Trip("K1", "500", listOf("A" to "09:00", "C" to "09:30"), block = "K", bikes = "1"),
                        Trip("K2", "600", listOf("C" to "09:32", "D" to "10:00"), block = "K", bikes = "2")
                    )
                )
            )
        )
        val direct = options(provider.searchJourneys(request(alfa, delta, time = "07:50"))).first()
        assertEquals("100", direct.legs.single().publicNumber)
        assertEquals(true, direct.legs.single().bikesAllowed)
        val stayOnBoard = options(provider.searchJourneys(request(alfa, delta, time = "08:50"))).first { it.legs.first().publicNumber == "500" }
        assertEquals(false, stayOnBoard.legs.single().bikesAllowed)
        val toGamma = options(provider.searchJourneys(request(alfa, gamma, time = "08:50"))).first()
        assertEquals(true, toGamma.legs.single().bikesAllowed)
    }

    @Test fun `a journey allows bikes only when every vehicle does, and says nothing when one is unknown`() {
        assertEquals(true, JourneyBikes.of(listOf(true, true)))
        assertEquals(false, JourneyBikes.of(listOf(true, false)))
        assertEquals(false, JourneyBikes.of(listOf(null, false)))
        assertNull(JourneyBikes.of(listOf(true, null)))
        assertNull(JourneyBikes.of(emptyList()))
    }

    @Test fun `without a published value the detail links the operator's own bike conditions, when it has a page`() {
        assertEquals(OfficialLinks.TRENITALIA, OfficialLinks.operatorOf(RailOperator.TRENITALIA_REGIONALE, "Trenitalia"))
        assertEquals(OfficialLinks.TRENITALIA, OfficialLinks.operatorOf(RailOperator.TRENITALIA_AV, null))
        assertEquals(OfficialLinks.TRENORD, OfficialLinks.operatorOf(RailOperator.TRENITALIA_REGIONALE, "TRENORD"))
        assertEquals(OfficialLinks.TRENORD, OfficialLinks.operatorOf(RailOperator.TRENORD, null))
        assertEquals(OfficialLinks.ITALO, OfficialLinks.operatorOf(RailOperator.ITALO, null))
        // The regional constant without a name, Trenitalia Tper and other companies: no page to guess.
        assertNull(OfficialLinks.operatorOf(RailOperator.TRENITALIA_REGIONALE, null))
        assertNull(OfficialLinks.operatorOf(RailOperator.TRENITALIA_REGIONALE, "Trenitalia Tper"))
        assertNull(OfficialLinks.operatorOf(RailOperator.OTHER, "SAD"))
        assertEquals("https://www.trenitalia.com/it/servizi/bici-al-seguito.html", OfficialLinks.TRENITALIA.url(OperatorLinkKind.BIKES))
        assertEquals("https://www.trenord.it/en/tickets/concessions-and-extras/bike-transportation/", OfficialLinks.TRENORD.url(OperatorLinkKind.BIKES))
        // Italo lists no bike page: no link.
        assertNull(OfficialLinks.ITALO.url(OperatorLinkKind.BIKES))
    }
}
