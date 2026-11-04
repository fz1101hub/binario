package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.live.RfiMonitorFetcher
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit F1: a delayed train stays on the board until its expected departure, as on the station's own monitor. */
class LateTrainBoardTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-08-14T08:00:00Z") // 10:00 in Rome

    private fun row(number: String, time: String, delay: String) = """
        <tr name="treno"><td id="RVettore"><img alt="TRENITALIA"/></td><td id="RCategoria"><img alt="Regionale"/></td>
          <td id="RTreno">$number</td><td id="RStazione">Napoli Centrale</td><td id="ROrario">$time</td>
          <td id="RRitardo" aria-label="$delay">$delay</td><td id="RBinario"><div>7</div></td></tr>"""

    @Test fun `RFI board keeps a late train until its expected departure and drops the ones that left`() = runBlocking {
        val html = "<html><body><table>" +
            row("2345", "09:45", "30") +   // expected 10:15: still to leave
            row("2341", "09:00", "20") +   // expected 09:20: gone
            row("2343", "09:30", "0") +    // on time at 09:30: gone
            row("2349", "09:55", "0") +    // inside the usual 10-minute grace
            row("2347", "10:20", "0") +
            "</table></body></html>"
        val rfi = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html }, clock = Clock.fixed(now, rome))
        val service = StationBoardAggregateService(listOf(rfi), Clock.fixed(now, rome), providerDispatcher = Dispatchers.Unconfined)
        val board = (service.load(Station("roma-termini", "Roma Termini", "Roma Termini"), BoardKind.DEPARTURES, now)
            as StationBoardAggregateResult.Success).board

        assertEquals(listOf("2349", "2345", "2347"), board.entries.map { it.publicNumber })
        val late = board.entries.single { it.publicNumber == "2345" }
        assertEquals("09:45", late.scheduledTime.toLocalTime().toString())
        assertEquals("10:15", late.expectedTime?.value?.toLocalTime().toString())
    }

    @Test fun `without a late window the provider keeps the old scheduled-time rule`() = runBlocking {
        val html = "<table>" + row("2345", "09:45", "30") + row("2347", "10:20", "0") + "</table>"
        val rfi = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html }, clock = Clock.fixed(now, rome))
        val result = rfi.getStationBoard(StationBoardRequest(StationId("roma-termini"), "Roma Termini",
            from = now.minus(Duration.ofMinutes(10)), to = now.plus(Duration.ofHours(6)), kind = BoardKind.DEPARTURES))
        assertEquals(listOf("2347"), (result as ProviderResult.Success).value.entries.map { it.publicNumber })
    }

    @Test fun `the shared rule needs a live expected time inside the late window`() {
        val request = StationBoardRequest(StationId("s"), from = now, to = now.plusSeconds(3600), kind = BoardKind.DEPARTURES,
            lateSince = now.minusSeconds(7200))
        assertTrue(request.keeps(now.minusSeconds(600), now.plusSeconds(60)))
        assertFalse(request.keeps(now.minusSeconds(600), null))
        assertFalse(request.keeps(now.minusSeconds(600), now.minusSeconds(1)))
        assertFalse(request.keeps(now.minusSeconds(7300), now.plusSeconds(60)))
        assertTrue(request.keeps(now, null))
        assertFalse(request.keeps(now.plusSeconds(3601), null))
    }
}
