package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.repository.StationBoardEnrichedTrainDataSource
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RfiRealtimeIntegrationTest {
    private val now = Instant.parse("2026-10-27T00:46:42Z")
    private val clock = Clock.fixed(now, ZoneId.of("Europe/Rome"))
    private val milano = Station("curated:milano", "Milano Centrale", "Milano")
    private val lodi = Station("curated:lodi", "Lodi", "Lodi")
    private val route = SavedRoute("commute", "Lavoro", milano, lodi)

    private fun fixture(name: String = "rfi-milano-20261027.html") =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }

    private val missingSchedule = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
            error("A current live train must not wait for or depend on the static schedule")
    }

    @Test fun `real monitor DOM reaches route data source for an intermediate commuter stop`() = runBlocking {
        val adapter = RfiStationBoardAdapter(RfiMonitorFetcher { fixture() }, clock)
        val result = StationBoardEnrichedTrainDataSource(missingSchedule, listOf(adapter), clock)
            .loadRoute(route, Direction.OUTBOUND)
        val snapshot = (result as DataResult.Success).snapshot
        val service = snapshot.services.first { it.number == "2451" }
        assertTrue(service.stops.any { it.station == "LODI" && it.scheduled == "05:41" })
        assertTrue(service.operationalStatusVerified)
        assertEquals(0, service.officialDelayMinutes)
        assertEquals(Instant.parse("2026-10-27T00:46:38Z").toEpochMilli(), snapshot.sourceObservedAtEpochMillis)
        assertTrue(snapshot.expiresAtEpochMillis > now.toEpochMilli())
    }

    @Test fun `delay published in the real DOM reaches the service and departure time`() = runBlocking {
        val document = Jsoup.parse(fixture())
        val row = document.select("tr[name=treno]").first { it.selectFirst("#RTreno")?.text()?.trim() == "2451" }
        row.selectFirst("#RRitardo")!!.attr("aria-label", "15 minuti").text("15")
        val adapter = RfiStationBoardAdapter(RfiMonitorFetcher { document.html() }, clock)
        val snapshot = (StationBoardEnrichedTrainDataSource(missingSchedule, listOf(adapter), clock)
            .loadRoute(route, Direction.OUTBOUND) as DataResult.Success).snapshot
        val service = snapshot.services.first { it.number == "2451" }
        assertEquals(15, service.officialDelayMinutes)
        assertEquals("05:30", service.expectedDeparture)
        assertEquals(ServiceStatus.DELAYED, service.status)
        assertEquals(setOf("rfi-station-board"), service.operationalSourceIds)
        // RFI only predicts the departure here; don't invent predictions at later stops.
        assertEquals("—", service.stops.first { it.station == "LODI" }.expected)
    }

    @Test fun `a stale monitor cannot be relabelled live by a successful download`() = runBlocking {
        val stale = fixture().replace("27/10/2026", "26/10/2026")
        val result = RfiStationBoardAdapter(RfiMonitorFetcher { stale }, clock).loadRoute(route, Direction.OUTBOUND)
        assertTrue(result is DataResult.Failure)
    }

    @Test fun `a monitor for another station is rejected`() = runBlocking {
        val result = RfiStationBoardAdapter(RfiMonitorFetcher { fixture("rfi-roma-20261027.html") }, clock)
            .loadRoute(route, Direction.OUTBOUND)
        assertTrue(result is DataResult.Failure)
    }

    @Test fun `board and route reuse one station request and preserve source observation`() = runBlocking {
        var calls = 0
        val adapter = RfiStationBoardAdapter(RfiMonitorFetcher { calls++; fixture() }, clock)
        adapter.loadRoute(route, Direction.OUTBOUND)
        val result = adapter.getStationBoard(StationBoardRequest(
            stationId = StationId(milano.id), stationName = milano.name,
            from = now, to = now.plusSeconds(8 * 3_600), kind = BoardKind.DEPARTURES
        ))
        assertEquals(1, calls)
        val board = (result as ProviderResult.Success).value
        assertTrue(board.entries.any { it.publicNumber == "2451" })
        assertEquals(Instant.parse("2026-10-27T00:46:38Z"), board.entries.first().status?.source?.observedAt)
    }

    @Test fun `national public station map resolves commuter stations and rejects ambiguous aliases`() {
        assertEquals(1539, RfiStationIds.placeId(Station("curated:latina", "Latina", "Lazio")))
        assertNotNull(RfiStationIds.placeId(Station("curated:lodi", "Lodi", "Lombardia")))
        assertNotNull(RfiStationIds.placeId(Station("curated:pisa", "Pisa Centrale", "Toscana")))
        assertNull(RfiStationIds.placeId(Station("unknown", "Unknown", "")))
        assertNull(RfiStationIds.placeId(Station("ambiguous", "Unknown", "", aliases = listOf("Roma Termini", "Milano Centrale"))))
    }

    @Test fun `current HTTPS source reaches the production route wrapper`() = runBlocking {
        assumeTrue("Explicit read-only live verification", System.getenv("BINARIO_VERIFY_LIVE") == "true")
        val liveClock = Clock.system(ZoneId.of("Europe/Rome"))
        for (station in listOf(Station("roma-termini", "Roma Termini", "Roma"), milano)) {
            val adapter = RfiStationBoardAdapter(clock = liveClock)
            val instant = liveClock.instant()
            val result = adapter.getStationBoard(StationBoardRequest(
                stationId = StationId(station.id), stationName = station.name,
                from = instant.minusSeconds(600), to = instant.plusSeconds(8 * 3_600), kind = BoardKind.DEPARTURES
            ))
            assertTrue("Current RFI board unavailable: $station / $result", result is ProviderResult.Success)
            val board = (result as ProviderResult.Success).value
            assertTrue("No current departures for ${station.name}", board.entries.isNotEmpty())
            val entry = board.entries.first { it.status != null }
            val destination = Station("published", checkNotNull(entry.destinationOrOriginLabel), "")
            val liveRoute = SavedRoute("live-check", "Read-only check", station, destination)
            val snapshot = (StationBoardEnrichedTrainDataSource(missingSchedule, listOf(adapter), liveClock)
                .loadRoute(liveRoute, Direction.OUTBOUND) as DataResult.Success).snapshot
            assertTrue(snapshot.services.any { it.number == entry.publicNumber && it.operationalStatusVerified })
            assertTrue(snapshot.expiresAtEpochMillis > liveClock.millis())
            println("LIVE VERIFIED ${station.name}: ${board.entries.size} departures; train ${entry.publicNumber}; source ${Instant.ofEpochMilli(snapshot.sourceObservedAtEpochMillis)}")
        }
    }
}
