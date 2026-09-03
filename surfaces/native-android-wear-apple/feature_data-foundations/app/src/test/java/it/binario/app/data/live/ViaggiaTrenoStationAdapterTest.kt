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

class ViaggiaTrenoStationAdapterTest {
    private val route = SavedRoute(
        id = "roma-napoli",
        name = "Roma–Napoli",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )

    @Test
    fun `trasforma il tabellone ViaggiaTreno in corse live`() = runBlocking {
        val adapter = ViaggiaTrenoStationAdapter(
            fetcher = ViaggiaTrenoStationFetcher { htmlFixture() },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is it.binario.app.data.DataResult.Success)
        val snapshot = (result as it.binario.app.data.DataResult.Success).snapshot
        assertEquals("Trenitalia — ViaggiaTreno live", snapshot.providerName)
        assertEquals(1, snapshot.services.size)
        val service = snapshot.services.single()
        assertEquals("9401", service.number)
        assertEquals("Napoli Centrale", service.destination)
        assertEquals("10:06", service.expectedDeparture)
        assertEquals("3", service.platform)
        assertEquals(6, service.delayMinutes)
        assertEquals(ServiceStatus.DELAYED, service.status)
    }

    @Test
    fun `riusa il tabellone ViaggiaTreno entro il ttl`() = runBlocking {
        var calls = 0
        val adapter = ViaggiaTrenoStationAdapter(
            fetcher = ViaggiaTrenoStationFetcher { calls += 1; htmlFixture() },
            clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
        )

        adapter.loadRoute(route, Direction.OUTBOUND)
        adapter.loadRoute(route, Direction.OUTBOUND)

        assertEquals(1, calls)
    }

    private fun htmlFixture(): String = """
        <html><body>
          <div class="bloccorisultato">
            <h2> FR 9401 </h2>
            <div class="bloccotreno">
              Per <strong>NAPOLI CENTRALE</strong><br/>
              Delle ore <strong>10:00</strong><br/>
              Binario Previsto: 3<br/>
              Binario Reale: <strong>3</strong><br/>
              ritardo 6 minuti
            </div>
          </div>
          <div class="bloccorisultato">
            <h2>REG 12679</h2>
            <div class="bloccotreno">
              Per <strong>LATINA</strong><br/>
              Delle ore <strong>10:05</strong><br/>
              Binario Reale: <strong>16</strong><br/>
              in orario
            </div>
          </div>
        </body></html>
    """.trimIndent()
}
