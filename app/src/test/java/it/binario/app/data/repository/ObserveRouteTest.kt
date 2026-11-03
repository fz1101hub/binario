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

    @Test fun `offline rows show while the live source answers, and the verified live result still wins`() = runBlocking {
        val planned = DataResult.Success(snapshot("offline", verified = false))
        val live = DataResult.Success(snapshot("live", verified = true))
        val enriched = StationBoardEnrichedTrainDataSource(source(planned), emptyList(), clock, zone, liveRouteSources = listOf(source(live, 300)))
        val emissions = enriched.observeRoute(route, Direction.OUTBOUND, query).toList()
        assertEquals(listOf("offline", "live"), emissions.map { (it as DataResult.Success).snapshot.providerName })
        assertEquals("live", (enriched.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.providerName)
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
