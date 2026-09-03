package it.binario.app.data.live

import it.binario.app.data.Direction
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
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
