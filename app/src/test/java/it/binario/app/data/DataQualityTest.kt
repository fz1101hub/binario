package it.binario.app.data

import it.binario.app.data.core.DelayConsistency
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
    fun `un coverage fallback resta pubblicabile ma non puo diventare affidabile o verificato`() {
        val fallback = snapshot(
            confidence = DataConfidence.STATIC_ONLY,
            freshness = Freshness.FRESH,
            observedAt = now - 10_000L,
            providerName = "European Sleeper GTFS · fallback best-effort",
            serviceId = "european-sleeper:trip-1"
        )

        val quality = SnapshotQualityAssessor.assess(fallback, now)

        assertEquals(DataQualityBand.LIMITED, quality.band)
        assertTrue(quality.score <= 64)
        assertTrue(fallback.providerName.contains("fallback best-effort", ignoreCase = true))
    }

    @Test
    fun `un orario ufficiale senza stato operativo usa una banda programmata distinta`() {
        val scheduled = snapshot(
            confidence = DataConfidence.STATIC_ONLY,
            freshness = Freshness.FRESH,
            observedAt = now - 10_000L,
            providerName = "Regione Lombardia · orario programmato",
            operationalStatusVerified = false
        )

        val quality = SnapshotQualityAssessor.assess(scheduled, now)

        assertEquals(DataQualityBand.OFFICIAL_SCHEDULED, quality.band)
        assertTrue(quality.band != DataQualityBand.VERIFIED)
    }

    @Test
    fun `segnala divergenza tra ritardo ufficiale e rilevazione senza eliminare la corsa`() {
        val base = snapshot(DataConfidence.OPERATOR_PRIMARY, Freshness.FRESH, observedAt = now - 30_000L)
        val divergent = base.copy(services = base.services.map { it.copy(delayConsistency = DelayConsistency.DIVERGENT) })

        val quality = SnapshotQualityAssessor.assess(divergent, now)

        assertTrue(SnapshotWarning.DELAY_DIVERGENCE in quality.warnings)
        assertTrue(quality.score < 100)
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
    fun `una fonte ufficiale batte un coverage fallback anche se il fallback e piu fresco`() = runBlocking {
        val official = snapshot(
            DataConfidence.OPEN_DATA,
            Freshness.AGING,
            observedAt = now - 2 * 60_000L,
            providerName = "Open data ufficiale"
        )
        val fallback = snapshot(
            DataConfidence.STATIC_ONLY,
            Freshness.FRESH,
            observedAt = now - 5_000L,
            providerName = "European Sleeper · fallback best-effort",
            serviceId = "european-sleeper:trip-1"
        )
        val repository = RailDataRepository(
            authorisedSources = listOf(source(DataResult.Success(fallback)), source(DataResult.Success(official))),
            fallback = source(DataResult.Failure("fallback repository non atteso")),
            clock = { now }
        )

        val result = repository.loadRoute(route, Direction.OUTBOUND) as DataResult.Success

        assertEquals("Open data ufficiale", result.snapshot.providerName)
        assertEquals(DataQualityBand.RELIABLE, result.snapshot.qualityBand)
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

    @Test
    fun `pack senza copertura conserva il motivo specifico del quadro ufficiale`() = runBlocking {
        val query = JourneyQuery(java.time.LocalDate.of(2026, 9, 4), java.time.LocalTime.of(8, 0))
        val noLocalCoverage = source(DataResult.Failure("GTFS assente", RouteFailureReason.RouteNotCovered))
        val reasons = listOf(
            RouteFailureReason.NoServiceAfter(query.serviceDate, query.departureTime),
            RouteFailureReason.ScheduleExpired(query.serviceDate.minusDays(1)),
            RouteFailureReason.ScheduleOutsideValidity(query.serviceDate, query.serviceDate.plusDays(30)),
            RouteFailureReason.NetworkUnavailable,
            RouteFailureReason.Unavailable
        )
        for (reason in reasons) {
            val repository = RailDataRepository(
                authorisedSources = listOf(noLocalCoverage, source(DataResult.Failure("RFI", reason))),
                fallback = noLocalCoverage
            )
            assertEquals(reason, (repository.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Failure).reason)
        }
    }

    @Test
    fun `risultato locale specifico prevale su errore di rete ufficiale`() = runBlocking {
        val reason = RouteFailureReason.ScheduleExpired(java.time.LocalDate.of(2026, 9, 1))
        val repository = RailDataRepository(
            authorisedSources = listOf(source(DataResult.Failure("rete"))),
            fallback = source(DataResult.Failure("pack scaduto", reason))
        )
        assertEquals(reason, (repository.loadRoute(route, Direction.OUTBOUND) as DataResult.Failure).reason)
    }

    private fun source(result: DataResult): TrainDataSource = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = result
    }

    private fun snapshot(
        confidence: DataConfidence,
        freshness: Freshness,
        observedAt: Long,
        providerName: String = "test",
        serviceId: String = "service",
        operationalStatusVerified: Boolean = true
    ): RouteSnapshot = RouteSnapshot(
        route = route,
        direction = Direction.OUTBOUND,
        services = listOf(
            TrainService(
                id = serviceId,
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
                stops = emptyList(),
                operationalStatusVerified = operationalStatusVerified
            )
        ),
        providerName = providerName,
        retrievedAtEpochMillis = observedAt,
        expiresAtEpochMillis = observedAt + 60_000L,
        freshness = freshness,
        sourceObservedAtEpochMillis = observedAt,
        confidence = confidence
    )
}
