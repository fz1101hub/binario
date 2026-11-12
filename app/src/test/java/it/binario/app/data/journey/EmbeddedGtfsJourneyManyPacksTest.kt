package it.binario.app.data.journey

import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.epsilon
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.provider.ProviderResult
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four packs serve both ends (Milano Centrale – Verona Porta Nuova is in the Swiss, Trenord, Italo and Trenitalia
 * packs): the one ranked last, the Swiss pack, must still take part.
 */
class EmbeddedGtfsJourneyManyPacksTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.publicNumber} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    /** In the planned order of the packs: Trenitalia and Italo (national), Trenord (Italian regional), then Switzerland. */
    private fun fourPacks(swiss: List<Trip>, arrivals: List<String> = listOf("10:30", "10:40", "10:50")) = provider(
        linkedMapOf(
            pack("trenitalia-gtfs") to feed(listOf(Trip("T1", "9747", listOf("A" to "08:00", "E" to arrivals[0])))),
            pack("italo-gtfs") to feed(listOf(Trip("I1", "8977", listOf("A" to "08:10", "E" to arrivals[1])))),
            pack("lombardia-trenord-gtfs") to feed(listOf(Trip("L1", "2611", listOf("A" to "08:20", "E" to arrivals[2])))),
            pack("ch-otd-gtfs") to feed(swiss)
        )
    )

    @Test fun `the fourth pack serving both ends is searched like the first three`() = runBlocking {
        val provider = fourPacks(listOf(Trip("C1", "311", listOf("A" to "08:30", "E" to "09:30"))), arrivals = listOf("09:00", "09:10", "09:20"))
        val result = provider.searchJourneys(request(alfa, epsilon))
        assertTrue(result is ProviderResult.Success)
        assertEquals(
            listOf("9747 Alfa 08:00-09:00 Epsilon", "8977 Alfa 08:10-09:10 Epsilon", "2611 Alfa 08:20-09:20 Epsilon", "311 Alfa 08:30-09:30 Epsilon"),
            options(result).map { it.summary() }
        )
        assertEquals(
            setOf("ch-otd-gtfs", "lombardia-trenord-gtfs", "italo-gtfs", "trenitalia-gtfs"),
            options(result).flatMap { option -> option.snapshot.contributingSources.map { it.sourceId.value } }.toSet()
        )
        // The slower trains of the first three packs do not hide the fast one of the fourth.
        val fast = fourPacks(listOf(Trip("C1", "311", listOf("A" to "08:30", "E" to "09:30"))))
        assertEquals(listOf("311 Alfa 08:30-09:30 Epsilon"), options(fast.searchJourneys(request(alfa, epsilon))).map { it.summary() })
    }

    @Test fun `a journey with a change inside the fourth pack is found`() = runBlocking {
        // No direct train in the fourth pack: its two trains with a change at Beta arrive first.
        val provider = fourPacks(
            listOf(
                Trip("C2", "25", listOf("A" to "08:00", "B" to "08:35")),
                Trip("C3", "311", listOf("B" to "08:45", "E" to "09:58"))
            )
        )
        val best = options(provider.searchJourneys(request(alfa, epsilon))).minByOrNull { it.arrival }!!
        assertEquals("25 Alfa 08:00-08:35 Beta | 311 Beta 08:45-09:58 Epsilon", best.summary())
    }

    @Test fun `the map of trains in motion of a route reads every pack serving both ends`() = runBlocking {
        val provider = fourPacks(listOf(Trip("C1", "311", listOf("A" to "08:30", "E" to "09:30"))))
        val scope = ScheduledRunScope.Route(StationId(alfa.id), null, StationId(epsilon.id), null)
        val runs = provider.scheduledRailRuns(ScheduledRunRequest(LocalDateTime.of(monday, LocalTime.parse("09:00")), scope))
        assertEquals(listOf("2611", "311", "8977", "9747"), runs.mapNotNull { it.publicNumber }.sorted())
    }
}
