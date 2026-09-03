package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.RailDataRepository
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LiveProviderFallbackTest {
    @Test
    fun `usa RFI quando ViaggiaTreno non è raggiungibile`() = runBlocking {
        val route = SavedRoute(
            id = "roma-napoli",
            name = "Roma–Napoli",
            outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
            outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
        )
        val viaggiatreno = ViaggiaTrenoStationAdapter(
            fetcher = ViaggiaTrenoStationFetcher { throw IOException("temporarily unavailable") }
        )
        val rfi = RfiStationBoardAdapter(
            fetcher = RfiMonitorFetcher {
                """<table><tr name="treno"><td id="RTreno">9401</td><td id="RStazione">Napoli Centrale</td><td id="ROrario">10:00</td><td id="RRitardo" aria-label="Nessuno"></td><td id="RBinario">7</td></tr></table>"""
            }
        )

        val result = RailDataRepository(authorisedSources = listOf(viaggiatreno, rfi)).loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is DataResult.Success)
        assertEquals("RFI — Monitor Arrivi/Partenze live", (result as DataResult.Success).snapshot.providerName)
    }
}
