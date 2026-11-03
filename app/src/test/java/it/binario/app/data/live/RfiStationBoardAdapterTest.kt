package it.binario.app.data.live

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RfiStationBoardAdapterTest {
    private val route = SavedRoute(
        id = "roma-napoli",
        name = "Roma–Napoli",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )

    @Test
    fun `il numero visibile del 23263 prevale su un aria label ancora a zero`() = runBlocking {
        val html = htmlFixture().replace("9401", "23263").replace("aria-label=\"12 minuti\">12 minuti", "aria-label=\"Nessuno\">6")
        val result = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)).loadRoute(route, Direction.OUTBOUND)
        val service = (result as it.binario.app.data.DataResult.Success).snapshot.services.single()
        assertEquals(6, service.officialDelayMinutes)
        assertEquals("10:06", service.expectedDeparture)
    }

    @Test
    fun `un aria label generico non nasconde il ritardo numerico visibile`() {
        val html = htmlFixture().replace("aria-label=\"12 minuti\"", "aria-label=\"Ritardo\"")
        val service = RfiStationBoardAdapter().parseDepartures(html, "Roma Termini", "Napoli Centrale", route, Direction.OUTBOUND).first()
        assertEquals(12, service.officialDelayMinutes)
        assertTrue(service.operationalStatusVerified)
    }

    @Test
    fun `il placeholder Nessuno tra virgolette del tabellone reale non prova zero minuti`() {
        val html = htmlFixture().replace("aria-label=\"12 minuti\">12 minuti", "aria-label='\"Nessuno\"'>")
        val service = RfiStationBoardAdapter().parseDepartures(html, "Appiano", "", route, Direction.OUTBOUND).first()
        assertEquals(null, service.officialDelayMinutes)
        assertTrue(!service.operationalStatusVerified)
        val numeric = html.replace("'\"Nessuno\"'", "'\"6\"'")
        assertEquals(6, RfiStationBoardAdapter().parseDepartures(numeric, "Appiano", "", route, Direction.OUTBOUND).first().officialDelayMinutes)
    }

    @Test
    fun `un numero nei messaggi non diventa ritardo e il ritardo mancante resta nullo`() {
        val html = org.jsoup.Jsoup.parse(htmlFixture()).also { doc ->
            doc.select("#RRitardo").remove()
            doc.select(".testoinfoaggiuntive").forEach { it.text("Il prossimo treno fra 10 minuti viaggia in orario") }
        }.outerHtml()
        val service = RfiStationBoardAdapter().parseDepartures(html, "Roma Termini", "Napoli Centrale", route, Direction.OUTBOUND).first()
        assertEquals(null, service.officialDelayMinutes)
        assertTrue(!service.operationalStatusVerified)
    }

    @Test
    fun `supporta arrivi con il parametro pubblico corretto`() = runBlocking {
        val adapter = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            assertTrue(url.endsWith("arrivals=True"))
            htmlFixture()
        }, clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC))
        val result = adapter.getStationBoard(StationBoardRequest(StationId("roma-termini"), "Roma Termini",
            from = Instant.parse("2026-08-14T07:00:00Z"), to = Instant.parse("2026-08-14T13:00:00Z"), kind = BoardKind.ARRIVALS))
        assertTrue(result is ProviderResult.Success)
        assertEquals(BoardKind.ARRIVALS, (result as ProviderResult.Success).value.kind)
    }

    @Test
    fun `zero esplicito nel campo ritardo e verificato senza confonderlo con campo mancante`() = runBlocking {
        suspend fun service(html: String) = (RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)).loadRoute(route, Direction.OUTBOUND) as it.binario.app.data.DataResult.Success).snapshot.services.single()
        val zero = htmlFixture().replace("12 minuti", "0 minuti")
        assertTrue(service(zero).operationalStatusVerified)
        assertEquals(0, service(zero).delayMinutes)
        val missing = org.jsoup.Jsoup.parse(zero).also { it.select("#RRitardo").remove() }.outerHtml()
        assertTrue(!service(missing).operationalStatusVerified)
    }

    @Test
    fun `tabellone live risolve una stazione fuori dalla mappa storica dal catalogo ufficiale`() = runBlocking {
        val requests = mutableListOf<String>()
        val adapter = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            requests += url
            if (url == RfiStationCatalog.URL) "<select id='ElencoLocalita'><option value='9876'>Nuova Stazione</option></select>"
            else htmlFixture()
        }, clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC))
        val result = adapter.getStationBoard(StationBoardRequest(StationId("curated:nuova"), "Nuova Stazione",
            from = Instant.parse("2026-08-14T07:00:00Z"), to = Instant.parse("2026-08-14T13:00:00Z"), kind = BoardKind.DEPARTURES))
        assertTrue(result is ProviderResult.Success)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("placeId=9876"))
        assertEquals(2, (result as ProviderResult.Success).value.entries.size)
    }

    @Test
    fun `trasforma il tabellone RFI in dati live filtrati sulla destinazione`() = runBlocking {
        val adapter = RfiStationBoardAdapter(
            fetcher = RfiMonitorFetcher { htmlFixture() },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is it.binario.app.data.DataResult.Success)
        val snapshot = (result as it.binario.app.data.DataResult.Success).snapshot
        assertEquals("RFI — Monitor Arrivi/Partenze live", snapshot.providerName)
        assertEquals(1, snapshot.services.size)
        assertEquals("9401", snapshot.services.single().number)
        assertEquals("Napoli Centrale", snapshot.services.single().destination)
        assertEquals("10:12", snapshot.services.single().expectedDeparture)
        assertEquals("7", snapshot.services.single().platform)
        assertEquals(12, snapshot.services.single().delayMinutes)
        assertEquals(ServiceStatus.DELAYED, snapshot.services.single().status)
    }

    @Test
    fun `espone il tabellone RFI nel contratto canonico`() = runBlocking {
        val adapter = RfiStationBoardAdapter(
            fetcher = RfiMonitorFetcher { htmlFixture() },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("roma-termini"),
                stationName = "Roma Termini",
                from = Instant.parse("2026-08-14T07:00:00Z"),
                to = Instant.parse("2026-08-14T13:00:00Z"),
                kind = BoardKind.DEPARTURES
            )
        )

        assertTrue(result is ProviderResult.Success)
        val entry = (result as ProviderResult.Success).value.entries.first { it.publicNumber == "9401" }
        assertEquals("Napoli Centrale", entry.destinationOrOriginLabel)
        assertEquals("7", entry.platform?.value)
        assertEquals(RunStatus.DELAYED, entry.status?.value)
    }

    @Test
    fun `propaga una deviazione RFI al modello realtime`() = runBlocking {
        val divertedHtml = htmlFixture().replace(
            "Regolare instradamento",
            "Treno deviato via Caserta per guasto alla linea"
        )
        val adapter = RfiStationBoardAdapter(
            fetcher = RfiMonitorFetcher { divertedHtml },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("roma-termini"),
                stationName = "Roma Termini",
                from = Instant.parse("2026-08-14T07:00:00Z"),
                to = Instant.parse("2026-08-14T13:00:00Z"),
                kind = BoardKind.DEPARTURES
            )
        )

        val entry = (result as ProviderResult.Success).value.entries.first { it.publicNumber == "9401" }
        assertEquals(RouteChangeKind.DIVERTED, entry.routeChange?.value?.kind)
        assertEquals(listOf("Caserta"), entry.routeChange?.value?.viaStopLabels)
    }

    @Test
    fun `riusa lo snapshot RFI entro il ttl`() = runBlocking {
        var calls = 0
        val adapter = RfiStationBoardAdapter(
            fetcher = RfiMonitorFetcher { calls += 1; htmlFixture() },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        adapter.loadRoute(route, Direction.OUTBOUND)
        adapter.loadRoute(route, Direction.OUTBOUND)

        assertEquals(1, calls)
    }

    @Test
    fun `rifiuta stazioni RFI senza identificativo verificato`() = runBlocking {
        val unsupported = route.copy(outwardOrigin = Station("stazione-ignota", "Stazione ignota", "Italia"))
        val result = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            assertEquals(RfiStationCatalog.URL, url)
            "<select id='ElencoLocalita'><option value='1'>Altra stazione</option></select>"
        })
            .loadRoute(unsupported, Direction.OUTBOUND)

        assertTrue(result is it.binario.app.data.DataResult.Failure)
    }

    private fun htmlFixture(): String = """
        <html><body><table>
          <tr name="treno" class="row">
            <td id="RVettore"><img alt="TRENITALIA" /></td>
            <td id="RCategoria"><img alt="Frecciarossa" /></td>
            <td id="RTreno">9401</td>
            <td id="RStazione">Napoli Centrale</td>
            <td id="ROrario">10:00</td>
            <td id="RRitardo" aria-label="12 minuti">12 minuti</td>
            <td id="RBinario"><div>7</div></td>
            <td class="testoinfoaggiuntive">Regolare instradamento</td>
          </tr>
          <tr name="treno" class="row">
            <td id="RVettore"><img alt="TRENITALIA" /></td>
            <td id="RCategoria"><img alt="Intercity" /></td>
            <td id="RTreno">583</td>
            <td id="RStazione">Milano Centrale</td>
            <td id="ROrario">10:20</td>
            <td id="RRitardo" aria-label="Nessuno"></td>
            <td id="RBinario"><div>4</div></td>
          </tr>
        </table></body></html>
    """.trimIndent()
}
