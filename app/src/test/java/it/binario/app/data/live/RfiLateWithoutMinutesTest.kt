package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.repository.StationBoardEnrichedTrainDataSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bare «RITARDO» of the RFI boards (the arrivals of the 3 Nov 2026 samples publish it without a number): the train
 * is late with no minutes, «In ritardo (minuti non pubblicati)»; never on time, never an invented delay. Synthetic rows
 * in the shapes the boards print, not a copy of the remote page.
 */
class RfiLateWithoutMinutesTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val clock = Clock.fixed(Instant.parse("2026-11-03T19:30:51Z"), zone)
    private val query = JourneyQuery(LocalDate.of(2026, 11, 3), LocalTime.of(20, 30))
    private val route = SavedRoute("live-regional", "Appiano → Roma Ostiense",
        Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")),
        Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))

    private fun row(number: String, time: String, text: String, aria: String?, destination: String = "ROMA TIBURTINA", info: String = "") =
        "<tr name=\"treno\"><td id=\"RVettore\"><img alt=\"Trenitalia\"/></td><td id=\"RCategoria\"><img alt=\"Categoria REG\"/></td>" +
            "<td id=\"RTreno\">$number</td><td id=\"RStazione\"><div>$destination</div></td><td id=\"ROrario\">$time</td>" +
            "<td id=\"RRitardo\"" + (aria?.let { " aria-label='$it'" } ?: "") + ">$text</td><td id=\"RBinario\"><div>2</div></td>" +
            "<td class=\"testoinfoaggiuntive\">$info</td></tr>"
    private fun board(vararg rows: String) = "<html><body><table>${rows.joinToString("")}</table></body></html>"

    private fun parsed(text: String, aria: String?, info: String = ""): TrainService =
        RfiStationBoardAdapter(clock = clock).parseDepartures(board(row("20729", "20:13", text, aria, info = info)), "Appiano", "", route, Direction.OUTBOUND).single()

    @Test fun `a bare RITARDO is late without minutes, neither verified nor on time`() {
        for ((text, aria) in listOf("RITARDO" to null, " ritardo " to null, "" to "RITARDO", "\"RITARDO\"" to null)) {
            val service = parsed(text, aria)
            assertTrue("$text/$aria", service.delayMinutesUnpublished)
            assertFalse(service.operationalStatusVerified)
            assertNull(service.officialDelayMinutes)
            assertEquals(0, service.delayMinutes)
            assertEquals(service.scheduledDeparture, service.expectedDeparture)
            assertFalse(service.stops.first().operationalStatusVerified == true)
        }
        // With its minutes it is an ordinary delay; a cancellation stays a cancellation; nothing else is late.
        val minutes = parsed("RITARDO", "7")
        assertFalse(minutes.delayMinutesUnpublished)
        assertEquals(7, minutes.officialDelayMinutes)
        assertEquals(ServiceStatus.DELAYED, minutes.status)
        assertFalse(parsed("RITARDO", null, info = "Treno soppresso").delayMinutesUnpublished)
        for ((text, aria) in listOf("" to "\"Nessuno\"", "" to null, "Ritardo 5" to null, "In orario" to null, "RITARDO 12'" to null)) {
            assertFalse("$text/$aria", parsed(text, aria).delayMinutesUnpublished)
        }
    }

    @Test fun `the board row says late without an expected time`() = runBlocking {
        val html = board(row("20729", "20:13", "RITARDO", null), row("20539", "20:14", "", "\"Nessuno\""), row("20381", "20:33", "4", null))
        val adapter = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html }, clock = clock)
        val request = StationBoardRequest(
            StationId("roma-ostiense"), "Roma Ostiense", from = clock.instant().minusSeconds(3600), to = clock.instant().plusSeconds(3 * 3600),
            kind = BoardKind.ARRIVALS
        )
        val entries = (adapter.getStationBoard(request) as ProviderResult.Success).value.entries.associateBy { it.publicNumber }
        assertEquals(RunStatus.DELAYED, entries.getValue("20729").status?.value)
        assertNull(entries.getValue("20729").expectedTime)
        assertNull(entries.getValue("20539").status)
        assertEquals(RunStatus.DELAYED, entries.getValue("20381").status?.value)
        assertEquals(LocalTime.of(20, 37), entries.getValue("20381").expectedTime?.value?.toLocalTime())
    }

    @Test fun `the live route keeps the late train unverified and says so`() = runBlocking {
        val departures = board(row("20383", "20:34", "RITARDO", null), row("20385", "20:49", "4", null))
        val arrivals = board(row("20383", "20:48", "", "\"Nessuno\"", "ROMA OSTIENSE"), row("20385", "21:03", "4", null, "ROMA OSTIENSE"))
        val catalogue = "<select id='ElencoLocalita'><option value='3162'>Appiano</option><option value='2379'>Roma Ostiense</option></select>"
        val boards = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            when {
                url == RfiStationCatalog.URL -> catalogue
                url.endsWith("arrivals=True") -> arrivals
                else -> departures
            }
        }, clock = clock)
        val result = RfiLiveRouteDataSource(boards, clock).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        val services = result.snapshot.services.associateBy { it.number }
        val late = services.getValue("20383")
        assertTrue(late.delayMinutesUnpublished)
        assertFalse(late.operationalStatusVerified)
        assertNull(late.officialDelayMinutes)
        assertEquals(ServiceStatus.ON_TIME, late.status)
        assertTrue(late.operationalSourceIds.isEmpty())
        val delayed = services.getValue("20385")
        assertFalse(delayed.delayMinutesUnpublished)
        assertTrue(delayed.operationalStatusVerified)
        assertEquals(4, delayed.officialDelayMinutes)
    }

    @Test fun `a programmed train matched to a bare RITARDO is late without minutes after the board reconciliation`() = runBlocking {
        val today = LocalDate.of(2026, 11, 3)
        val origin = Station("roma-termini", "Roma Termini", "Roma")
        val destination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
        val programmed = SavedRoute("roma-napoli", "Roma–Napoli", origin, destination)
        val planned = RouteSnapshot(
            route = programmed,
            direction = Direction.OUTBOUND,
            services = listOf(
                TrainService(
                    "gtfs:T1", "20729", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma Termini", "Napoli Centrale",
                    "20:40", "20:40", null, 0, ServiceStatus.ON_TIME,
                    listOf(TrainStop("Roma Termini", "20:40", "20:40", null, 0, false), TrainStop("Napoli Centrale", "22:40", "22:40", null, 0, false)),
                    operationalStatusVerified = false, scheduledDepartureDateTime = LocalDateTime.of(today, LocalTime.of(20, 40)),
                    operatorDisplayName = "Trenitalia"
                )
            ),
            providerName = "GTFS programmato",
            retrievedAtEpochMillis = clock.millis(),
            expiresAtEpochMillis = clock.millis() + 3_600_000,
            freshness = Freshness.AGING
        )
        val plannedSource = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = DataResult.Success(planned)
        }
        val html = board(row("20729", "20:40", "RITARDO", null, "NAPOLI CENTRALE"))
        val adapter = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html }, clock = clock)
        val result = StationBoardEnrichedTrainDataSource(plannedSource, listOf(adapter), clock, zone)
            .loadRoute(programmed, Direction.OUTBOUND, JourneyQuery(today, LocalTime.of(20, 30))) as DataResult.Success
        val service = result.snapshot.services.single()
        assertTrue(service.delayMinutesUnpublished)
        assertFalse(service.operationalStatusVerified)
        assertEquals(ServiceStatus.ON_TIME, service.status)
        assertEquals(0, service.delayMinutes)
        assertEquals("20:40", service.expectedDeparture)
    }
}
