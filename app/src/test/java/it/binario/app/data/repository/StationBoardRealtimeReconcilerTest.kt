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
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness as CoreFreshness
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class StationBoardRealtimeReconcilerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val route = SavedRoute(
        id = "roma-napoli",
        name = "Roma–Napoli",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )
    private val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(12, 0))
    private val clock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `arricchisce la corsa GTFS con ritardo binario e stato RFI`() = runBlocking {
        val source = SourceId("rfi-station-board")
        val provider = FakeBoardProvider(
            source,
            "RFI",
            board(
                source = source,
                number = "9401",
                scheduled = zdt(12, 30),
                expected = zdt(12, 42),
                platform = "7",
                status = RunStatus.DELAYED
            )
        )
        val snapshot = StationBoardRealtimeReconciler(listOf(provider), clock, zone)
            .enrich(plannedSnapshot(), query)

        val service = snapshot.services.single()
        assertEquals("12:42", service.expectedDeparture)
        assertEquals(12, service.delayMinutes)
        assertEquals("7", service.platform)
        assertEquals(ServiceStatus.DELAYED, service.status)
        assertTrue(service.operationalStatusVerified)
        assertEquals(setOf("rfi-station-board"), service.operationalSourceIds)
        assertTrue(snapshot.providerName.contains("RFI"))
    }

    @Test
    fun `non applica un tabellone a un numero treno diverso`() = runBlocking {
        val source = SourceId("rfi-station-board")
        val provider = FakeBoardProvider(
            source,
            "RFI",
            board(
                source = source,
                number = "9999",
                scheduled = zdt(12, 30),
                expected = zdt(12, 50),
                platform = "9",
                status = RunStatus.DELAYED
            )
        )

        val snapshot = StationBoardRealtimeReconciler(listOf(provider), clock, zone)
            .enrich(plannedSnapshot(), query)

        val service = snapshot.services.single()
        assertEquals("12:30", service.expectedDeparture)
        assertFalse(service.operationalStatusVerified)
        assertTrue(service.operationalSourceIds.isEmpty())
    }

    @Test
    fun `accetta il capolinea del tabellone diverso dalla fermata richiesta`() = runBlocking {
        val source = SourceId("rfi-station-board")
        val provider = FakeBoardProvider(
            source,
            "RFI",
            board(
                source = source,
                number = "9401",
                scheduled = zdt(12, 30),
                expected = zdt(12, 38),
                platform = "6",
                status = RunStatus.DELAYED,
                destinationLabel = "Milano Centrale"
            )
        )

        val snapshot = StationBoardRealtimeReconciler(listOf(provider), clock, zone)
            .enrich(plannedSnapshot(), query)

        assertTrue(snapshot.services.single().operationalStatusVerified)
        assertEquals("12:38", snapshot.services.single().expectedDeparture)
    }

    @Test
    fun `applica una variazione di percorso realtime alla corsa GTFS`() = runBlocking {
        val source = SourceId("rfi-station-board")
        val change = OperationalRouteChange(
            kind = RouteChangeKind.SKIPPED_STOPS,
            summary = "Per guasto alla linea il treno non effettua fermata a Formia-Gaeta",
            skippedStopLabels = listOf("Formia-Gaeta")
        )
        val provider = FakeBoardProvider(
            source,
            "RFI",
            board(
                source = source,
                number = "9401",
                scheduled = zdt(12, 30),
                expected = zdt(12, 35),
                platform = "7",
                status = RunStatus.DELAYED,
                routeChange = change
            )
        )
        val planned = plannedSnapshot().copy(
            services = plannedSnapshot().services.map { service ->
                service.copy(
                    stops = listOf(
                        TrainStop("Roma Termini", "12:30", "12:30", null, 0, false),
                        TrainStop("Formia-Gaeta", "13:15", "13:15", null, 0, false),
                        TrainStop("Napoli Centrale", "13:40", "13:40", null, 0, false)
                    )
                )
            }
        )

        val snapshot = StationBoardRealtimeReconciler(listOf(provider), clock, zone).enrich(planned, query)
        val service = snapshot.services.single()

        assertEquals(RouteChangeKind.SKIPPED_STOPS, service.routeChange?.kind)
        assertEquals(setOf("rfi-station-board"), service.routeChangeSourceIds)
        assertEquals(StopRouteChange.SKIPPED, service.stops.first { it.station == "Formia-Gaeta" }.routeChange)
    }

    @Test
    fun `sceglie operatore per stato e RFI per binario e segnala conflitto`() = runBlocking {
        val rfi = SourceId("rfi-station-board")
        val trenitalia = SourceId("viaggiatreno")
        val providers = listOf(
            FakeBoardProvider(rfi, "RFI", board(rfi, "9401", zdt(12, 30), zdt(12, 35), "7", RunStatus.DELAYED)),
            FakeBoardProvider(trenitalia, "ViaggiaTreno", board(trenitalia, "9401", zdt(12, 30), zdt(12, 48), "8", RunStatus.DELAYED))
        )

        val snapshot = StationBoardRealtimeReconciler(providers, clock, zone)
            .enrich(plannedSnapshot(), query)

        val service = snapshot.services.single()
        assertEquals("12:48", service.expectedDeparture)
        assertEquals("7", service.platform)
        assertEquals(setOf("viaggiatreno", "rfi-station-board"), service.operationalSourceIds)
        assertTrue(SnapshotWarning.SOURCE_CONFLICT in snapshot.qualityWarnings)
    }

    @Test
    fun `usa il fallback diretto solo quando manca il piano programmato`() = runBlocking {
        val liveSnapshot = plannedSnapshot().copy(
            providerName = "RFI live",
            confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
            services = plannedSnapshot().services.map { it.copy(operationalStatusVerified = true) }
        )
        val provider = object : StationBoardProvider, TrainDataSource {
            override val descriptor = descriptor(SourceId("rfi-station-board"), "RFI")

            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
                ProviderResult.Success(
                    board(SourceId("rfi-station-board"), "9401", zdt(12, 30), zdt(12, 35), "7", RunStatus.DELAYED),
                    ProviderResponseMeta(SourceId("rfi-station-board"), clock.instant())
                )

            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
                DataResult.Success(liveSnapshot)

            override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
                DataResult.Success(liveSnapshot)
        }
        val missingPlanned = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
                DataResult.Failure("no planned itinerary")
            override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
                DataResult.Failure("no planned itinerary")
        }

        val result = StationBoardEnrichedTrainDataSource(missingPlanned, listOf(provider), clock, zone)
            .loadRoute(route, Direction.OUTBOUND, query)

        assertTrue(result is DataResult.Success)
        assertTrue((result as DataResult.Success).snapshot.notice.orEmpty().contains("Fallback realtime diretto"))
    }

    @Test
    fun `il wrapper non interroga realtime per una data futura`() = runBlocking {
        var boardCalls = 0
        val provider = object : StationBoardProvider {
            override val descriptor = descriptor(SourceId("rfi-station-board"), "RFI")
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
                boardCalls += 1
                return ProviderResult.Success(
                    board(SourceId("rfi-station-board"), "9401", zdt(12, 30), zdt(12, 35), "7", RunStatus.DELAYED),
                    ProviderResponseMeta(SourceId("rfi-station-board"), clock.instant())
                )
            }
        }
        val plannedSource = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
                DataResult.Success(plannedSnapshot())

            override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
                DataResult.Success(plannedSnapshot().copy(
                    services = plannedSnapshot().services.map {
                        it.copy(scheduledDepartureDateTime = LocalDateTime.of(query.serviceDate, LocalTime.of(12, 30)))
                    }
                ))
        }
        val wrapper = StationBoardEnrichedTrainDataSource(plannedSource, listOf(provider), clock, zone)

        wrapper.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 5), LocalTime.of(12, 0))
        )

        assertEquals(0, boardCalls)
    }

    private fun plannedSnapshot(): RouteSnapshot = RouteSnapshot(
        route = route,
        direction = Direction.OUTBOUND,
        services = listOf(
            TrainService(
                id = "gtfs:T1",
                number = "9401",
                operator = RailOperator.TRENITALIA_AV,
                operatorDisplayName = "Trenitalia",
                trainCategory = TrainCategory.FRECCE,
                origin = "Roma Termini",
                destination = "Napoli Centrale",
                scheduledDeparture = "12:30",
                expectedDeparture = "12:30",
                platform = null,
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                stops = listOf(
                    TrainStop("Roma Termini", "12:30", "12:30", null, 0, false),
                    TrainStop("Napoli Centrale", "13:40", "13:40", null, 0, false)
                ),
                operationalStatusVerified = false,
                scheduledDepartureDateTime = LocalDateTime.of(2026, 9, 4, 12, 30)
            )
        ),
        providerName = "GTFS programmato",
        retrievedAtEpochMillis = clock.millis(),
        expiresAtEpochMillis = clock.millis() + Duration.ofHours(6).toMillis(),
        freshness = Freshness.AGING,
        confidence = DataConfidence.STATIC_ONLY
    )

    private fun board(
        source: SourceId,
        number: String,
        scheduled: ZonedDateTime,
        expected: ZonedDateTime,
        platform: String,
        status: RunStatus,
        destinationLabel: String = "Napoli Centrale",
        routeChange: OperationalRouteChange? = null
    ): StationBoard {
        val ref = SourceRef(
            sourceId = source,
            nativeId = number,
            observedAt = clock.instant(),
            fetchedAt = clock.instant(),
            expiresAt = clock.instant().plusSeconds(60),
            confidence = if (source.value == "rfi-station-board") Confidence.INFRASTRUCTURE_PRIMARY else Confidence.OPERATOR_PRIMARY,
            traceId = "${source.value}:$number"
        )
        return StationBoard(
            stationId = StationId("roma-termini"),
            kind = BoardKind.DEPARTURES,
            from = query.requestedAt.atZone(zone).minusMinutes(30).toInstant(),
            to = query.requestedAt.atZone(zone).plusHours(8).toInstant(),
            entries = listOf(
                StationBoardEntry(
                    runId = TrainRunId("${source.value}:$number"),
                    scheduledTime = scheduled,
                    expectedTime = SourcedValue(expected, ref),
                    destinationOrOrigin = StationId("published:napolicentrale"),
                    destinationOrOriginLabel = destinationLabel,
                    publicNumber = number,
                    operatorId = OperatorId("trenitalia"),
                    platform = SourcedValue(platform, ref),
                    status = SourcedValue(status, ref),
                    routeChange = routeChange?.let { SourcedValue(it, ref) }
                )
            ),
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = clock.instant(),
                validUntil = clock.instant().plusSeconds(60),
                freshness = CoreFreshness.FRESH,
                completeness = Completeness.PARTIAL,
                contributingSources = setOf(ref)
            )
        )
    }

    private fun descriptor(source: SourceId, name: String) = ProviderDescriptor(
        id = source,
        displayName = name,
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "test"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofMinutes(1)),
        schemaVersion = 1
    )

    private fun zdt(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 4, hour, minute, 0, 0, zone)

    private class FakeBoardProvider(
        source: SourceId,
        name: String,
        private val board: StationBoard
    ) : StationBoardProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = source,
            displayName = name,
            capabilities = setOf(ProviderCapability.STATION_BOARD),
            coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "test"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofMinutes(1)),
            schemaVersion = 1
        )

        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
            ProviderResult.Success(
                board,
                ProviderResponseMeta(
                    providerId = descriptor.id,
                    fetchedAt = board.snapshot.generatedAt,
                    observedAt = board.snapshot.generatedAt,
                    expiresAt = board.snapshot.validUntil
                )
            )
    }
}
