package it.binario.app.data.planned

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.core.StationId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfiProgrammedStationBoardAdapterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-09-04T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))

    @Test
    fun `official programmed board exposes scheduled departures only`() = kotlinx.coroutines.runBlocking {
        val adapter = RfiProgrammedStationBoardAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture() },
            clock = clock
        )
        val result = adapter.getStationBoard(
            request("roma-termini", "Roma Termini", BoardKind.DEPARTURES)
        )

        val success = result as ProviderResult.Success
        val board = success.value
        assertEquals(listOf("9611", "9923"), board.entries.map { it.publicNumber })
        assertEquals(Completeness.STATIC_ONLY, board.snapshot.completeness)
        board.entries.forEach { entry ->
            assertNull(entry.expectedTime)
            assertNull(entry.platform)
            assertNull(entry.status)
        }
    }

    @Test
    fun `catalogue hubs resolve by railway code even when the display name differs from RFI`() = kotlinx.coroutines.runBlocking {
        val requestedUrls = mutableListOf<String>()
        val adapter = RfiProgrammedStationBoardAdapter(
            fetcher = RfiProgrammedTimetableFetcher { url -> requestedUrls += url; fixture() },
            clock = clock
        )
        listOf(
            Triple("curated:S06421", "Firenze S. M. Novella", "S06421") to "Id=1325",
            Triple("curated:S11119", "Bari ( Tutte Le Stazioni )", "S11119") to "Id=550",
            Triple("curated:S11781", "Reggio Di Calabria Centrale", "S11781") to "Id=116",
            Triple("curated:S07113", "Ancona ( Tutte Le Stazioni )", "S07113") to "Id=408"
        ).forEach { (station, expectedId) ->
            val (id, name, code) = station
            val result = adapter.getStationBoard(
                StationBoardRequest(
                    stationId = StationId(id),
                    stationName = name,
                    railwayCode = code,
                    from = now,
                    to = now.plusSeconds(3600),
                    kind = BoardKind.DEPARTURES
                )
            )
            assertTrue("$name: $result", result is ProviderResult.Success)
            assertTrue("$name -> ${requestedUrls.lastOrNull()}", requestedUrls.last().contains(expectedId))
        }
    }

    @Test
    fun `unmapped station does not perform network request`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val adapter = RfiProgrammedStationBoardAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val result = adapter.getStationBoard(
            request("unknown", "Stazione ignota", BoardKind.DEPARTURES)
        )

        assertTrue(result is ProviderResult.Failure)
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (result as ProviderResult.Failure).error.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `arrivals are explicitly unsupported rather than fabricated`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val adapter = RfiProgrammedStationBoardAdapter(
            fetcher = RfiProgrammedTimetableFetcher { calls += 1; fixture() },
            clock = clock
        )
        val result = adapter.getStationBoard(
            request("roma-termini", "Roma Termini", BoardKind.ARRIVALS)
        )

        assertTrue(result is ProviderResult.Failure)
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (result as ProviderResult.Failure).error.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `weekday periodicity produces no Saturday departures`() = kotlinx.coroutines.runBlocking {
        val saturdayClock = Clock.fixed(Instant.parse("2026-09-05T06:00:00Z"), ZoneId.of("UTC"))
        val adapter = RfiProgrammedStationBoardAdapter(
            fetcher = RfiProgrammedTimetableFetcher { fixture("Lun-Ven") },
            clock = saturdayClock
        )
        val from = Instant.parse("2026-09-05T06:00:00Z")
        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("roma-termini"),
                stationName = "Roma Termini",
                from = from,
                to = from.plusSeconds(6 * 60 * 60),
                kind = BoardKind.DEPARTURES
            )
        )

        assertTrue(result is ProviderResult.Success)
        assertTrue((result as ProviderResult.Success).value.entries.isEmpty())
    }

    private fun request(id: String, name: String, kind: BoardKind): StationBoardRequest =
        StationBoardRequest(
            stationId = StationId(id),
            stationName = name,
            from = Instant.parse("2026-09-04T06:00:00Z"),
            to = Instant.parse("2026-09-04T12:00:00Z"),
            kind = kind
        )

    private fun fixture(periodicity: String = "Giornaliero"): String = """
        <html><body>
          <div>ORARIO PROGRAMMATO 14 Giugno 2026 - 12 Dicembre 2026</div>
          <table>
            <tr>
              <th>Orario di partenza</th><th>Tipo e n. di treno</th><th>Stazione di arrivo</th>
              <th>Binario programmato</th><th>Classi e servizi a bordo</th><th>Impresa di trasporto</th>
              <th>Fermate precedenti (orario di partenza)</th>
              <th>Fermate successive (orario di arrivo)</th><th>Periodicità e avvertenze</th>
            </tr>
            <tr>
              <td>08.20</td>
              <td><img alt="Treno Frecciarossa Alta Velocità"/>9611</td>
              <td>Milano Centrale (11.40)</td>
              <td>8</td><td></td><td>TI</td><td></td>
              <td>Roma Tiburtina (08.27) Firenze S.M.N. (09.46) Bologna Centrale (10.23) Milano Centrale (11.40)</td>
              <td>$periodicity</td>
            </tr>
            <tr>
              <td>09.30</td>
              <td><img alt="Treno Italo Alta Velocità"/>9923</td>
              <td>Torino Porta Nuova (14.50)</td>
              <td>10</td><td></td><td>NTV</td><td></td>
              <td>Firenze S.M.N. (10.55) Bologna Centrale (11.33) Milano Centrale (12.45) Torino Porta Nuova (14.50)</td>
              <td>$periodicity</td>
            </tr>
          </table>
        </body></html>
    """.trimIndent()
}
