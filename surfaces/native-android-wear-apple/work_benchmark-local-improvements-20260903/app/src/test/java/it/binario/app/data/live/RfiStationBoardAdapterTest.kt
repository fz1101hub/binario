package it.binario.app.data.live

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
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
        val result = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { error("non deve essere chiamato") })
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
