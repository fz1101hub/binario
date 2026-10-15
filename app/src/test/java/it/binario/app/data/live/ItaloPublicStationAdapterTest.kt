package it.binario.app.data.live

import it.binario.app.data.Direction
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import it.binario.app.data.DataResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ItaloPublicStationAdapterTest {
    private val route = SavedRoute(
        id = "italo-route",
        name = "Roma–Napoli",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )

    @Test
    fun `il parser trasforma una riga pubblica Italo in corsa live`() {
        val html = """
            <article data-train-number="9923" data-destination="Napoli Centrale" data-scheduled-time="10:40" data-platform="5">
              Italo 9923 per Napoli Centrale partenza ore 10:40 binario 5 ritardo 12 minuti
            </article>
        """.trimIndent()

        val services = ItaloPublicStationAdapter().parseStationBoard(html, "Roma Termini", route, Direction.OUTBOUND)

        assertEquals(1, services.size)
        assertEquals("9923", services.single().number)
        assertEquals(RailOperator.ITALO, services.single().operator)
        assertEquals("Napoli Centrale", services.single().destination)
        assertEquals("10:52", services.single().expectedDeparture)
        assertEquals("5", services.single().platform)
        assertEquals(ServiceStatus.DELAYED, services.single().status)
    }

    @Test
    fun `il parser riconosce una soppressione senza binario`() {
        val html = """
            <li data-train="9901" data-destination="Milano Centrale" data-scheduled-time="12:10">
              Italo 9901 per Milano Centrale ore 12:10 servizio soppresso
            </li>
        """.trimIndent()

        val services = ItaloPublicStationAdapter().parseStationBoard(html, "Roma Termini", route, Direction.OUTBOUND)

        assertEquals(ServiceStatus.CANCELLED, services.single().status)
        assertNull(services.single().platform)
    }

    @Test
    fun `espone Italo nel contratto StationBoardProvider`() = runBlocking {
        val html = """
            <article data-train-number="9923" data-destination="Napoli Centrale" data-scheduled-time="10:40" data-platform="5">
              Italo 9923 per Napoli Centrale partenza ore 10:40 binario 5 ritardo 12 minuti
            </article>
        """.trimIndent()
        val adapter = ItaloPublicStationAdapter(
            fetcher = ItaloPublicStationFetcher { html },
            clock = Clock.fixed(Instant.parse("2026-08-15T08:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("roma-termini"),
                stationName = "Roma Termini",
                from = Instant.parse("2026-08-15T07:00:00Z"),
                to = Instant.parse("2026-08-15T13:00:00Z"),
                kind = BoardKind.DEPARTURES
            )
        )

        assertTrue(result is ProviderResult.Success)
        val entry = (result as ProviderResult.Success).value.entries.single()
        assertEquals("9923", entry.publicNumber)
        assertEquals("5", entry.platform?.value)
        assertEquals(RunStatus.DELAYED, entry.status?.value)
    }

    @Test
    fun `assenza di stato esplicito Italo non equivale a in orario`() {
        val html = """<article data-train-number="9923" data-destination="Napoli Centrale" data-scheduled-time="10:40">Italo 9923 per Napoli Centrale ore 10:40</article>"""
        val service = ItaloPublicStationAdapter().parseStationBoard(html, "Roma Termini", route, Direction.OUTBOUND).single()

        assertTrue(!service.operationalStatusVerified)
    }

    @Test
    fun `la cache evita una seconda richiesta sullo stesso tabellone`() = runBlocking {
        var calls = 0
        val html = """<article data-train-number="9923" data-destination="Napoli Centrale" data-scheduled-time="10:40">Italo 9923 per Napoli Centrale ore 10:40</article>"""
        val adapter = ItaloPublicStationAdapter(
            fetcher = ItaloPublicStationFetcher { calls += 1; html },
            clock = Clock.fixed(Instant.parse("2026-08-15T08:00:00Z"), ZoneOffset.UTC)
        )

        adapter.loadRoute(route, Direction.OUTBOUND)
        adapter.loadRoute(route, Direction.OUTBOUND)

        assertEquals(1, calls)
    }

    @Test
    fun `un tabellone senza righe Italo produce un fallimento esplicito`() = runBlocking {
        val adapter = ItaloPublicStationAdapter(fetcher = ItaloPublicStationFetcher { "<main>Non ci sono treni in circolazione</main>" })

        val result = adapter.loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is DataResult.Failure)
    }

    @Test
    fun `la mappa include solo slug verificati`() {
        assertEquals("roma-termini", ItaloStationSlugs.slug("roma-termini"))
        assertEquals("firenze-santa-maria-novella", ItaloStationSlugs.slug("firenze-smn"))
        assertNull(ItaloStationSlugs.slug("bologna-centrale"))
        assertTrue(ItaloStationSlugs.slug("napoli-centrale") != null)
    }
}
