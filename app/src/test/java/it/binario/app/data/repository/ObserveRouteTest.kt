package it.binario.app.data.repository

import it.binario.app.data.DataConfidence
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit proposal 4: the offline result shows first; the last emission is what loadRoute returns, live first as before. */
class ObserveRouteTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val route = SavedRoute("r", "r", Station("a", "A", "A"), Station("b", "B", "B"))
    private val query = JourneyQuery(LocalDate.of(2026, 11, 3), LocalTime.of(9, 0))

    private fun snapshot(provider: String, verified: Boolean) = RouteSnapshot(route, Direction.OUTBOUND, listOf(
        TrainService("$provider-1", "100", RailOperator.OTHER, TrainCategory.REGIONALE, "A", "B", "10:00", "10:00", null, 0,
            ServiceStatus.ON_TIME, listOf(TrainStop("A", "10:00", "10:00", null, 0, false)), operationalStatusVerified = verified)),
        provider, now.toEpochMilli(), now.toEpochMilli() + 60_000, Freshness.AGING, confidence = DataConfidence.STATIC_ONLY)

    private fun source(result: DataResult, waitMillis: Long = 0) = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult { delay(waitMillis); return result }
        override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult = loadRoute(route, direction)
    }

    /** Audit android-data-2: the verified live run takes the place of the same planned run instead of replacing the list. */
    @Test fun `offline rows show while the live source answers, then the verified live run replaces the same planned run`() = runBlocking {
        val planned = DataResult.Success(snapshot("offline", verified = false))
        val live = DataResult.Success(snapshot("live", verified = true))
        val enriched = StationBoardEnrichedTrainDataSource(source(planned), emptyList(), clock, zone, liveRouteSources = listOf(source(live, 300)))
        val emissions = enriched.observeRoute(route, Direction.OUTBOUND, query).toList()
        assertEquals(listOf("offline", "offline + live"), emissions.map { (it as DataResult.Success).snapshot.providerName })
        assertEquals(listOf("live-1"), (emissions.last() as DataResult.Success).snapshot.services.map { it.id })
        val loaded = enriched.loadRoute(route, Direction.OUTBOUND, query)
        assertEquals(emissions.last(), loaded)
        assertTrue((loaded as DataResult.Success).snapshot.services.single().operationalStatusVerified)
    }

    /** Audit android-data-2: a verified ViaggiaTreno route never hides the planned trains it has no run for (Italo). */
    @Test fun `an Italo train of the timetable survives a verified ViaggiaTreno route`() = runBlocking {
        val italo = TrainService("italo-8984", "8984", RailOperator.ITALO, TrainCategory.AV_ITALO, "A", "B", "10:05", "10:05", null, 0,
            ServiceStatus.ON_TIME, listOf(TrainStop("A", "10:05", "10:05", null, 0, false)), operationalStatusVerified = false)
        val offline = snapshot("offline", verified = false).let { it.copy(services = it.services + italo) }
        val planned = DataResult.Success(offline)
        val live = DataResult.Success(snapshot("live", verified = true))
        val enriched = StationBoardEnrichedTrainDataSource(source(planned), emptyList(), clock, zone, liveRouteSources = listOf(source(live, 300)))
        val emissions = enriched.observeRoute(route, Direction.OUTBOUND, query).toList()
        val final = (emissions.last() as DataResult.Success).snapshot.services
        assertEquals(listOf("live-1", "italo-8984"), final.map { it.id })
        assertEquals(listOf(true, false), final.map { it.operationalStatusVerified })
        assertTrue(emissions.all { (it as DataResult.Success).snapshot.services.any { train -> train.id == "italo-8984" } })
        assertEquals(emissions.last(), enriched.loadRoute(route, Direction.OUTBOUND, query))
    }

    @Test fun `without a verified live result the planned result is the answer`() = runBlocking {
        val planned = DataResult.Success(snapshot("offline", verified = false))
        val enriched = StationBoardEnrichedTrainDataSource(source(planned), emptyList(), clock, zone,
            liveRouteSources = listOf(source(DataResult.Failure("down"), 50)))
        val emissions = enriched.observeRoute(route, Direction.OUTBOUND, query).toList()
        assertEquals(1, emissions.size)
        assertSame(planned, emissions.single())
    }
}
