package it.binario.app.data.repository

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTieredTrainDataSourceTest {
    private val now = 1_750_000_000_000L
    private val route = SavedRoute(
        id = "tier-route",
        name = "Tier",
        outwardOrigin = Station("milano-centrale", "Milano Centrale", "Milano"),
        outwardDestination = Station("zurigo-hb", "Zürich HB", "Zürich")
    )
    private val query = JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(10, 0))

    @Test
    fun `non interroga fallback se il tier ufficiale trova la tratta`() = runBlocking {
        var fallbackCalls = 0
        val primary = source(DataResult.Success(snapshot("Swiss official", "ch-otd-gtfs:trip", DataConfidence.STATIC_ONLY)))
        val fallback = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
                fallbackCalls += 1
                return DataResult.Success(snapshot("community fallback", "european-sleeper:trip", DataConfidence.STATIC_ONLY))
            }
        }
        val dataSource = QualityTieredTrainDataSource(primary, listOf(fallback), clock = { now })

        val result = dataSource.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success

        assertEquals("Swiss official", result.snapshot.providerName)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `usa il fallback solo se il tier ufficiale fallisce`() = runBlocking {
        val primary = source(DataResult.Failure("nessuna tratta ufficiale"))
        val fallback = source(
            DataResult.Success(
                snapshot(
                    "European Sleeper · fallback best-effort",
                    "european-sleeper:trip",
                    DataConfidence.STATIC_ONLY
                )
            )
        )
        val dataSource = QualityTieredTrainDataSource(primary, listOf(fallback), clock = { now })

        val result = dataSource.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success

        assertEquals(DataQualityBand.LIMITED, result.snapshot.qualityBand)
        assertTrue(SnapshotWarning.COVERAGE_FALLBACK in result.snapshot.qualityWarnings)
    }

    @Test
    fun `tra piu fallback sceglie quello con qualita migliore`() = runBlocking {
        val primary = source(DataResult.Failure("nessuna tratta ufficiale"))
        val weak = source(
            DataResult.Success(
                snapshot(
                    "European Sleeper · fallback best-effort",
                    "european-sleeper:trip",
                    DataConfidence.STATIC_ONLY,
                    freshness = Freshness.STALE
                )
            )
        )
        val better = source(
            DataResult.Success(
                snapshot(
                    "DELFI community · fallback best-effort",
                    "delfi-gtfs-community:trip",
                    DataConfidence.STATIC_ONLY,
                    freshness = Freshness.FRESH
                )
            )
        )
        val dataSource = QualityTieredTrainDataSource(primary, listOf(weak, better), clock = { now })

        val result = dataSource.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success

        assertEquals("DELFI community · fallback best-effort", result.snapshot.providerName)
    }

    private fun source(result: DataResult): TrainDataSource = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = result
    }

    private fun snapshot(
        provider: String,
        serviceId: String,
        confidence: DataConfidence,
        freshness: Freshness = Freshness.FRESH
    ): RouteSnapshot = RouteSnapshot(
        route = route,
        direction = Direction.OUTBOUND,
        services = listOf(
            TrainService(
                id = serviceId,
                number = "ES123",
                operator = RailOperator.EUROPEAN_SLEEPER,
                operatorDisplayName = "European Sleeper",
                trainCategory = TrainCategory.OTHER,
                origin = "Milano Centrale",
                destination = "Zürich HB",
                scheduledDeparture = "10:00",
                expectedDeparture = "10:00",
                platform = null,
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                operationalStatusVerified = false,
                stops = emptyList()
            )
        ),
        providerName = provider,
        retrievedAtEpochMillis = now,
        expiresAtEpochMillis = now + 3_600_000L,
        freshness = freshness,
        sourceObservedAtEpochMillis = now,
        confidence = confidence
    )
}
