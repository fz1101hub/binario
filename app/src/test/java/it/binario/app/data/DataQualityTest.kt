package it.binario.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataQualityTest {
    private val route = SavedRoute(
        id = "quality-route",
        name = "Qualità",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )
    private val now = 1_750_000_000_000L

    @Test
    fun `una fonte primaria fresca e completa e verificata`() {
        val snapshot = snapshot(DataConfidence.OPERATOR_PRIMARY, Freshness.FRESH, observedAt = now - 30_000L)

        val quality = SnapshotQualityAssessor.assess(snapshot, now)

        assertEquals(DataQualityBand.VERIFIED, quality.band)
        assertTrue(quality.score >= 85)
    }

    @Test
    fun `un dato dimostrativo non e pubblicabile`() {
        val snapshot = snapshot(DataConfidence.DEMONSTRATION, Freshness.FRESH, observedAt = now)

        val quality = SnapshotQualityAssessor.assess(snapshot, now)

        assertEquals(DataQualityBand.NOT_PUBLISHABLE, quality.band)
        assertTrue(SnapshotWarning.DEMONSTRATION in quality.warnings)
    }

    @Test
    fun `il repository sceglie il candidato con punteggio migliore quando nessuno e verificato`() = runBlocking {
        val cached = snapshot(DataConfidence.CACHED, Freshness.AGING, observedAt = now - 8 * 60_000L)
        val openData = snapshot(DataConfidence.OPEN_DATA, Freshness.AGING, observedAt = now - 2 * 60_000L)
        val sourceA = source(DataResult.Success(cached))
        val sourceB = source(DataResult.Success(openData))
        val repository = RailDataRepository(listOf(sourceA, sourceB), fallback = source(DataResult.Failure("fallback non atteso")), clock = { now })

        val result = repository.loadRoute(route, Direction.OUTBOUND) as DataResult.Success

        assertEquals(DataConfidence.OPEN_DATA, result.snapshot.confidence)
        assertTrue((result.snapshot.qualityScore ?: 0) > 0)
    }

    @Test
    fun `il repository usa il fallback quando nessuna fonte live risponde`() = runBlocking {
        val repository = RailDataRepository(
            authorisedSources = listOf(source(DataResult.Failure("rete"))),
            fallback = source(DataResult.Success(snapshot(DataConfidence.DEMONSTRATION, Freshness.UNAVAILABLE, now))),
            clock = { now }
        )

        val result = repository.loadRoute(route, Direction.OUTBOUND) as DataResult.Success

        assertEquals(DataQualityBand.NOT_PUBLISHABLE, result.snapshot.qualityBand)
    }

    private fun source(result: DataResult): TrainDataSource = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = result
    }

    private fun snapshot(
        confidence: DataConfidence,
        freshness: Freshness,
        observedAt: Long
    ): RouteSnapshot = RouteSnapshot(
        route = route,
        direction = Direction.OUTBOUND,
        services = listOf(
            TrainService(
                id = "service",
                number = "123",
                operator = RailOperator.TRENITALIA_REGIONALE,
                trainCategory = TrainCategory.REGIONALE,
                origin = "Roma Termini",
                destination = "Napoli Centrale",
                scheduledDeparture = "10:00",
                expectedDeparture = "10:00",
                platform = "1",
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                stops = emptyList()
            )
        ),
        providerName = "test",
        retrievedAtEpochMillis = observedAt,
        expiresAtEpochMillis = observedAt + 60_000L,
        freshness = freshness,
        sourceObservedAtEpochMillis = observedAt,
        confidence = confidence
    )
}
