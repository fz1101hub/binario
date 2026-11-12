package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.delta
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.gamma
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.journey.JourneyFixtures.zeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.repository.TrainNumberQuery
import java.io.ByteArrayInputStream
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pack that publishes combined itineraries as trips ([EmbeddedGtfsPack.itineraryCopies], the Italo pack): the same
 * physical run under several trip ids and numbers is one train, under its own number, in the journeys, in the route
 * search and in the train-number search.
 */
class EmbeddedGtfsItineraryCopiesTest {
    private val flagged = JourneyFixtures.pack("italo-gtfs").copy(itineraryCopies = true)
    private val plain = JourneyFixtures.pack("italo-gtfs")

    /**
     * Train 8987 Alfa → Epsilon, 9939 Zeta → Alfa and 8925 Beta → Zeta, and two combined itineraries as the pack
     * publishes them: "9939 then 8987" (its second leg is 8987 as far as Delta, under the number 9939) and "8987 then
     * 8925" (a part of 8987 under its own number, then 8925 under the number 8987). The copies come first in the file.
     */
    private val trips = listOf(
        Trip("9939-8987_rail2", "9939", listOf("A" to "14:35", "B" to "15:11/15:13", "C" to "15:47/15:49", "D" to "16:15")),
        Trip("8987-8925_rail1", "8987", listOf("C" to "15:49", "D" to "16:15")),
        Trip("8987-8925_rail2", "8987", listOf("D" to "16:40", "Z" to "18:00")),
        Trip("9939", "9939", listOf("Z" to "12:20", "A" to "13:30")),
        // The train itself, published without its dwell at Gamma (arrival and departure at 15:47).
        Trip("8987", "8987", listOf("A" to "14:35", "B" to "15:11/15:13", "C" to "15:47", "D" to "16:15/16:17", "E" to "17:03")),
        Trip("8925", "8925", listOf("B" to "16:00", "D" to "16:30/16:40", "Z" to "18:00"))
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.publicNumber} ${leg.runId?.value} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    private fun adapter(pack: EmbeddedGtfsPack, trips: List<Trip> = this.trips): EmbeddedGtfsRouteAdapter {
        val bytes = feed(trips)
        return EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(bytes) }, packs = listOf(pack), clock = { 1_000L })
    }

    private fun numberSearch(pack: EmbeddedGtfsPack, number: String, trips: List<Trip> = this.trips): List<String> = runBlocking {
        adapter(pack, trips).searchByNumber(TrainNumberQuery(number, monday)).map { match ->
            "${match.service.number} ${match.service.origin} ${match.service.scheduledDeparture} → ${match.service.destination}"
        }
    }

    private fun routeSearch(pack: EmbeddedGtfsPack, from: Station, to: Station, time: String): List<String> = runBlocking {
        val route = SavedRoute("test", "${from.name}–${to.name}", from, to)
        val result = adapter(pack).loadRoute(route, Direction.OUTBOUND, JourneyQuery(monday, LocalTime.parse(time)))
        (result as DataResult.Success).snapshot.services.map { "${it.number} ${it.scheduledDeparture} ${it.id}" }
    }

    @Test fun `a journey rides the train under its own number, once`() = runBlocking {
        val provider = JourneyFixtures.provider(mapOf(flagged to feed(trips)))
        assertEquals(
            listOf("8987 italo-gtfs:8987:$monday Alfa 14:35-16:15 Delta"),
            options(provider.searchJourneys(request(alfa, delta, time = "14:00"))).map { it.summary() }
        )
        // The itinerary's second train is 8925, not a second 8987.
        assertEquals(
            listOf("8925 italo-gtfs:8925:$monday Delta 16:40-18:00 Zeta"),
            options(provider.searchJourneys(request(delta, zeta, time = "16:00", maxTransfers = 0))).map { it.summary() }
        )
        // Whatever the order of the trips in the file.
        val reversed = JourneyFixtures.provider(mapOf(flagged to feed(trips.reversed())))
        assertEquals(
            listOf("8987 italo-gtfs:8987:$monday Alfa 14:35-16:15 Delta"),
            options(reversed.searchJourneys(request(alfa, delta, time = "14:00"))).map { it.summary() }
        )
    }

    @Test fun `the dwell a copy still publishes is the train's departure`() = runBlocking {
        val provider = JourneyFixtures.provider(mapOf(flagged to feed(trips)))
        // 8987's own trip says 15:47 at Gamma, its arrival; the itinerary boarding it there says 15:49.
        assertEquals(
            listOf("8987 italo-gtfs:8987:$monday Gamma 15:49-16:15 Delta"),
            options(provider.searchJourneys(request(gamma, delta, time = "15:00"))).map { it.summary() }
        )
    }

    @Test fun `the route search lists each train once, under its own number`() {
        assertEquals(listOf("8987 14:35 italo-gtfs:8987:$monday"), routeSearch(flagged, alfa, delta, "14:00"))
        assertEquals(listOf("8987 15:49 italo-gtfs:8987:$monday"), routeSearch(flagged, gamma, delta, "15:00"))
        assertEquals(
            listOf("8987 14:35 italo-gtfs:8987:$monday", "9939 14:35 italo-gtfs:9939-8987_rail2:$monday"),
            routeSearch(plain, alfa, delta, "14:00")
        )
    }

    @Test fun `a train number finds that train only`() {
        assertEquals(listOf("9939 Zeta 12:20 → Alfa"), numberSearch(flagged, "9939"))
        assertEquals(listOf("8987 Alfa 14:35 → Epsilon"), numberSearch(flagged, "8987"))
        assertEquals(listOf("8925 Beta 16:00 → Zeta"), numberSearch(flagged, "8925"))
        assertEquals(emptyList<String>(), numberSearch(flagged, "1234"))
        // Without the flag 9939 is also the 14:35 from Alfa, which is train 8987.
        assertEquals(listOf("9939 Alfa 14:35 → Delta", "9939 Zeta 12:20 → Alfa"), numberSearch(plain, "9939"))
    }

    @Test fun `a copy stays when the train itself does not run that day`() {
        // On Sunday only the itineraries run (service ALL), the trains' own trips do not (WEEKDAY).
        val weekdayTrains = trips.map { if ('_' in it.id) it else it.copy(service = "WEEKDAY") }
        val sunday = monday.plusDays(6)
        val matches = runBlocking {
            adapter(flagged, weekdayTrains).searchByNumber(TrainNumberQuery("9939", sunday)).map { it.service.origin + " → " + it.service.destination }
        }
        assertEquals(listOf("Alfa → Delta"), matches)
    }

    @Test fun `two trains coupled over a section both stay`() = runBlocking {
        // 100 and 200 leave Alfa together and split at Beta: each starts its own number, neither is a copy.
        val coupled = listOf(
            Trip("X", "100", listOf("A" to "08:00", "B" to "08:30/08:35", "C" to "09:00")),
            Trip("Y", "200", listOf("A" to "08:00", "B" to "08:30/08:40", "D" to "09:10")),
            // The same train published twice (two calendars of the same day) is one train.
            Trip("Y2", "200", listOf("A" to "08:00", "B" to "08:30/08:40", "D" to "09:10"))
        )
        assertEquals(listOf("100 Alfa 08:00 → Gamma"), numberSearch(flagged, "100", coupled))
        assertEquals(listOf("200 Alfa 08:00 → Delta"), numberSearch(flagged, "200", coupled))
        val provider = JourneyFixtures.provider(mapOf(flagged to feed(coupled)))
        assertEquals(
            listOf("200 italo-gtfs:Y:$monday Alfa 08:00-09:10 Delta"),
            options(provider.searchJourneys(request(alfa, delta))).map { it.summary() }
        )
        assertEquals(
            listOf("100 italo-gtfs:X:$monday Alfa 08:00-09:00 Gamma"),
            options(provider.searchJourneys(request(alfa, gamma))).map { it.summary() }
        )
    }
}
