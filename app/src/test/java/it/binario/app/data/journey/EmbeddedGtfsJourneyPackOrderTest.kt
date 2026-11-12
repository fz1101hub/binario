package it.binario.app.data.journey

import it.binario.app.data.core.JourneyOption
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.epsilon
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which pack's copy of a train two packs publish is kept ([it.binario.app.data.sources.PlannedPackOrder]): the national
 * packs first, then the Italian regional and the foreign ones, except at an end that no national pack serves, where
 * the packs serving it come first.
 */
class EmbeddedGtfsJourneyPackOrderTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    /** The stops of a pack that does not reach Alfa (the stops a pack lists are the places it serves). */
    private val withoutAlfa = mapOf("B" to "Beta", "E" to "Epsilon")

    /** The pack and trip of every vehicle leg: "trenitalia-gtfs:T37". */
    private fun JourneyOption.trips(): List<String> = legs.map { it.runId!!.value.substringBeforeLast(':') }

    @Test fun `where a national pack serves both ends its copy of a shared train is kept`() = runBlocking {
        // As Milano Centrale - Verona Porta Nuova: the Swiss, Trenord, Italo and Trenitalia packs serve both ends. The
        // Swiss and the Trenitalia pack publish the same EuroCity, the Trenord and the Trenitalia pack the same regional.
        val provider = provider(
            linkedMapOf(
                pack("ch-otd-gtfs") to feed(listOf(Trip("C37", "EC 37", listOf("A" to "08:00", "E" to "09:00")))),
                pack("lombardia-trenord-gtfs") to feed(listOf(Trip("L2611", "2611", listOf("A" to "08:10", "E" to "09:10")))),
                pack("italo-gtfs") to feed(listOf(Trip("I8977", "8977", listOf("A" to "08:20", "E" to "09:20")))),
                pack("trenitalia-gtfs") to feed(
                    listOf(
                        Trip("T37", "37", listOf("A" to "08:00", "E" to "09:00")),
                        Trip("T2611", "2611", listOf("A" to "08:10", "E" to "09:10"))
                    )
                )
            )
        )
        val found = options(provider.searchJourneys(request(alfa, epsilon)))
        assertEquals(
            listOf(listOf("trenitalia-gtfs:T37"), listOf("trenitalia-gtfs:T2611"), listOf("italo-gtfs:I8977")),
            found.map { it.trips() }
        )
    }

    @Test fun `an end that only the South Tyrol pack serves puts that pack first`() = runBlocking {
        // As Merano - Verona: only the South Tyrol pack serves Merano (Alfa). Its 1826 from Bolzano (Beta) to Verona
        // (Epsilon) is also in the ÖBB and in the Trenitalia pack, which serve Bolzano and Verona.
        val provider = provider(
            linkedMapOf(
                pack("oebb-gtfs") to feed(listOf(Trip("O1826", "REX 1826", listOf("B" to "08:45", "E" to "10:15"))), withoutAlfa),
                pack("trenitalia-gtfs") to feed(listOf(Trip("T1826", "1826", listOf("B" to "08:45", "E" to "10:15"))), withoutAlfa),
                pack("alto-adige-sta-gtfs") to feed(
                    listOf(
                        Trip("S100", "100", listOf("A" to "08:00", "B" to "08:35")),
                        Trip("S1826", "1826", listOf("B" to "08:45", "E" to "10:15"))
                    )
                )
            )
        )
        val first = options(provider.searchJourneys(request(alfa, epsilon))).first()
        assertEquals(listOf("alto-adige-sta-gtfs:S100", "alto-adige-sta-gtfs:S1826"), first.trips())
    }

    @Test fun `an end that only the Swiss pack serves puts the Swiss pack first`() = runBlocking {
        // As Lugano - Venezia: only the Swiss pack serves Lugano (Alfa). Its EuroCity from Milano (Beta) to Venezia
        // (Epsilon) is also in the Trenitalia and in the Trenord pack.
        fun packs(trenordServesLugano: Boolean) = provider(
            linkedMapOf(
                pack("trenitalia-gtfs") to feed(listOf(Trip("T41", "41", listOf("B" to "09:10", "E" to "11:45"))), withoutAlfa),
                pack("lombardia-trenord-gtfs") to if (trenordServesLugano) {
                    feed(listOf(Trip("L41", "41", listOf("B" to "09:10", "E" to "11:45")), Trip("L10", "10", listOf("A" to "06:00", "B" to "06:55"))))
                } else {
                    feed(listOf(Trip("L41", "41", listOf("B" to "09:10", "E" to "11:45"))), withoutAlfa)
                },
                pack("ch-otd-gtfs") to feed(
                    listOf(
                        Trip("C25", "25", listOf("A" to "08:00", "B" to "08:55")),
                        Trip("C41", "EC 41", listOf("B" to "09:10", "E" to "11:45"))
                    )
                )
            )
        )
        val first = options(packs(trenordServesLugano = false).searchJourneys(request(alfa, epsilon))).first()
        assertEquals(listOf("ch-otd-gtfs:C25", "ch-otd-gtfs:C41"), first.trips())

        // When Trenord serves Lugano too, the foreign pack still comes first among the packs serving it.
        val withTrenord = options(packs(trenordServesLugano = true).searchJourneys(request(alfa, epsilon))).first()
        assertEquals(listOf("ch-otd-gtfs:C25", "ch-otd-gtfs:C41"), withTrenord.trips())
    }
}
