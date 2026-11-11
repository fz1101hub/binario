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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
    @Test fun `native source deadlines overlap instead of adding another wait for the fallback`() = runTest {
        var cancelled = false
        val slow = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val fast = source { DataResult.Success(plannedSnapshot().let { it.copy(services = it.services.map { train -> train.copy(operationalStatusVerified = true) }) }) }
        val result = StationBoardEnrichedTrainDataSource(source { DataResult.Success(plannedSnapshot()) }, emptyList(), clock, zone,
            liveRouteSources = listOf(slow, fast)).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertTrue(result.snapshot.services.single().operationalStatusVerified)
        assertTrue(cancelled)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test fun `a faster fallback does not override the verified operator delay`() = runTest {
        val preferred = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
                kotlinx.coroutines.delay(1_000)
                return DataResult.Success(plannedSnapshot().let { it.copy(services = it.services.map { train -> train.copy(
                    operationalStatusVerified = true, officialDelayMinutes = 6, delayMinutes = 6) }) })
            }
        }
        val fallback = source { DataResult.Success(plannedSnapshot().let { it.copy(services = it.services.map { train -> train.copy(
            operationalStatusVerified = true, officialDelayMinutes = 8, delayMinutes = 8) }) }) }
        val result = StationBoardEnrichedTrainDataSource(source { DataResult.Success(plannedSnapshot()) }, emptyList(), clock, zone,
            liveRouteSources = listOf(preferred, fallback)).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(6, result.snapshot.services.single().officialDelayMinutes)
        assertEquals(1_000L, testScheduler.currentTime)
    }
    private val zone = ZoneId.of("Europe/Rome")
    private val route = SavedRoute(
        id = "roma-napoli",
        name = "Roma–Napoli",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )
    private val query = JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(12, 0))
    private val clock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC)

    @Test fun `slow operator source has a total deadline and falls back to programmed data`() = runTest {
        var cancelled = false
        val slow = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val wrapper = StationBoardEnrichedTrainDataSource(source { DataResult.Success(plannedSnapshot()) }, emptyList(), clock, zone,
            liveRouteSources = listOf(slow))
        val result = wrapper.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(15_000L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertFalse(result.snapshot.services.single().operationalStatusVerified)
        assertEquals("12:30", result.snapshot.services.single().scheduledDeparture)
    }

    @Test fun `cancelling the route search propagates instead of starting a fallback`() = runTest {
        val started = CompletableDeferred<Unit>()
        var plannedCalled = false
        val slow = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
                started.complete(Unit); awaitCancellation()
            }
        }
        val wrapper = StationBoardEnrichedTrainDataSource(source { plannedCalled = true; DataResult.Success(plannedSnapshot()) }, emptyList(), clock, zone,
            liveRouteSources = listOf(slow))
        val pending = async { wrapper.loadRoute(route, Direction.OUTBOUND, query) }
        started.await()
        pending.cancelAndJoin()
        assertFalse(plannedCalled)
    }

    @Test
    fun `il ritardo non include una differenza fra orario statico e orario pubblicato`() = runBlocking {
        val id = SourceId("rfi-station-board")
        val provider = FakeBoardProvider(id, "RFI", board(id, "9401", zdt(12, 35), zdt(12, 41), "7", RunStatus.DELAYED))
        val service = StationBoardRealtimeReconciler(listOf(provider), clock, zone).enrich(plannedSnapshot(), query).services.single()
        assertEquals(6, service.delayMinutes)
        assertEquals("12:35", service.scheduledDeparture)
        assertEquals("12:41", service.expectedDeparture)
        assertEquals("12:35", service.stops.first().scheduled)
    }

    @Test
    fun `un aggiornamento operatore scaduto non sostituisce il dato RFI recente`() = runBlocking {
        val rfi = SourceId("rfi-station-board")
        val vt = SourceId("viaggiatreno")
        val stale = board(vt, "9401", zdt(12, 30), zdt(12, 30), "8", RunStatus.ON_TIME).let { b ->
            b.copy(entries = b.entries.map { e ->
                e.copy(expectedTime = e.expectedTime!!.let { it.copy(source = it.source.copy(observedAt = clock.instant().minusSeconds(301))) },
                    status = e.status!!.let { it.copy(source = it.source.copy(observedAt = clock.instant().minusSeconds(301))) })
            })
        }
        val providers = listOf(FakeBoardProvider(rfi, "RFI", board(rfi, "9401", zdt(12, 30), zdt(12, 36), "7", RunStatus.DELAYED)),
            FakeBoardProvider(vt, "VT", stale))
        val service = StationBoardRealtimeReconciler(providers, clock, zone).enrich(plannedSnapshot(), query).services.single()
        assertEquals(6, service.delayMinutes)
        assertEquals(ServiceStatus.DELAYED, service.status)
    }

    @Test
    fun `un binario senza stato non cancella un ritardo live gia verificato`() = runBlocking {
        val source = SourceId("rfi-station-board")
        val board = board(source, "9401", zdt(12, 30), zdt(12, 42), "7", RunStatus.DELAYED)
            .let { it.copy(entries = it.entries.map { entry -> entry.copy(expectedTime = null, status = null) }) }
        val planned = plannedSnapshot()
        val live = planned.copy(services = planned.services.map { it.copy(
            expectedDeparture = "12:42", delayMinutes = 12, officialDelayMinutes = 12,
            operationalStatusVerified = true, operationalSourceIds = setOf("alto-adige-sta-realtime"),
            operationalObservedAtEpochMillis = clock.millis()
        ) })
        val service = StationBoardRealtimeReconciler(listOf(FakeBoardProvider(source, "RFI", board)), clock, zone)
            .enrich(live, query).services.single()
        assertTrue(service.operationalStatusVerified)
        assertEquals("12:42", service.expectedDeparture)
        assertEquals(12, service.officialDelayMinutes)
        assertEquals("7", service.platform)
        assertTrue("alto-adige-sta-realtime" in service.operationalSourceIds)
    }

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
        assertEquals(true, service.stops.first().operationalStatusVerified)
        assertEquals(false, service.stops.last().operationalStatusVerified)
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

    /** Audit android-data-2: the live route enriches the planned list instead of replacing it. */
    @Test
    fun `la ricerca odierna mette la corsa live al posto della stessa corsa programmata`() = runBlocking {
        val calls = mutableListOf<String>()
        val live = DataResult.Success(plannedSnapshot().copy(providerName = "ViaggiaTreno",
            services = plannedSnapshot().services.map { it.copy(id = "viaggiatreno:9401", operationalStatusVerified = true) }))
        val wrapper = StationBoardEnrichedTrainDataSource(
            source { calls.add("planned"); DataResult.Success(plannedSnapshot()) },
            emptyList(), clock, zone,
            listOf(source { calls.add("live"); live })
        )
        val result = wrapper.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(live.snapshot.services, result.snapshot.services)
        assertEquals("GTFS programmato + ViaggiaTreno", result.snapshot.providerName)
        assertEquals(listOf("live", "planned"), calls)
        // Without a planned list the verified live route is still the answer.
        val alone = StationBoardEnrichedTrainDataSource(source { DataResult.Failure("no timetable") }, emptyList(), clock, zone,
            listOf(source { live })).loadRoute(route, Direction.OUTBOUND, query)
        assertEquals(live, alone)
    }

    /**
     * Audit fix-android-5: the boards are asked from the instants the live route already asked ViaggiaTreno for (the
     * requested time and, around now, two hours before it), so no source is asked for a third window.
     */
    @Test
    fun `la riconciliazione chiede i tabelloni dagli stessi istanti della tratta live`() = runBlocking {
        val rfi = SourceId("rfi-station-board")
        val seen = mutableListOf<StationBoardRequest>()
        val provider = FakeBoardProvider(rfi, "RFI", board(rfi, "9401", zdt(12, 30), zdt(12, 37), "4", RunStatus.DELAYED)) { seen += it }
        val reconciler = StationBoardRealtimeReconciler(listOf(provider), clock, zone)

        val enriched = reconciler.enrich(plannedSnapshot(), query)
        val aroundNow = seen.single()
        assertEquals(query.requestedAt.atZone(zone).toInstant(), aroundNow.from)
        assertEquals(aroundNow.from.minus(Duration.ofHours(2)), aroundNow.lateSince)
        assertEquals(7, enriched.services.single().delayMinutes)

        // A search for later today has no look-back, as the live route has none.
        reconciler.enrich(plannedSnapshot(), query.copy(departureTime = LocalTime.of(15, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 4, 15, 0).atZone(zone).toInstant(), seen.last().from)
        assertEquals(null, seen.last().lateSince)
    }

    /**
     * Audit fix-android-2: a train the day's timetable moved by half an hour is one train, not a timetable row that
     * does not exist plus the live row. Tomorrow's run of the same number, and a run hours away, stay apart.
     */
    @Test
    fun `la corsa live spostata di trenta minuti prende il posto della stessa corsa programmata`() = runBlocking {
        val today = plannedSnapshot().services.single()
        val departure = today.scheduledDepartureDateTime!!
        val tomorrow = today.copy(id = "gtfs:tomorrow", scheduledDepartureDateTime = departure.plusDays(1))
        val planned = plannedSnapshot().copy(services = listOf(today, tomorrow))
        fun liveAt(time: LocalDateTime) = DataResult.Success(plannedSnapshot().copy(providerName = "ViaggiaTreno", services = listOf(
            today.copy(id = "viaggiatreno:9401", scheduledDeparture = time.toLocalTime().toString(), expectedDeparture = time.toLocalTime().toString(),
                scheduledDepartureDateTime = time, operationalStatusVerified = true))))
        suspend fun merged(live: DataResult.Success) = (StationBoardEnrichedTrainDataSource(source { DataResult.Success(planned) }, emptyList(),
            clock, zone, listOf(source { live })).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services

        assertEquals(setOf("viaggiatreno:9401", "gtfs:tomorrow"), merged(liveAt(departure.plusMinutes(30))).map { it.id }.toSet())
        assertEquals(setOf("viaggiatreno:9401", "gtfs:tomorrow"), merged(liveAt(departure.minusMinutes(45))).map { it.id }.toSet())
        // More than three hours away it is not the same run of the day: both stay.
        assertEquals(setOf("gtfs:T1", "viaggiatreno:9401", "gtfs:tomorrow"), merged(liveAt(departure.plusHours(4))).map { it.id }.toSet())
        // The live run of tomorrow takes tomorrow's place, never today's.
        assertEquals(setOf("gtfs:T1", "viaggiatreno:9401"), merged(liveAt(departure.plusDays(1).plusMinutes(30))).map { it.id }.toSet())
    }

    /**
     * Audit fix-android-1: a live run that does not say who runs the train (ViaggiaTreno without compImpresa or
     * codiceCliente) takes the place of the planned train but keeps the timetable's operator, category and bikes.
     */
    @Test
    fun `la corsa live senza operatore conserva operatore categoria e bici del treno programmato`() = runBlocking {
        val planned = plannedSnapshot().let { snapshot ->
            snapshot.copy(services = snapshot.services.map {
                it.copy(operator = RailOperator.TRENORD, operatorDisplayName = "Trenord", bikesAllowed = true)
            })
        }
        val live = DataResult.Success(planned.copy(providerName = "ViaggiaTreno", services = planned.services.map {
            it.copy(id = "viaggiatreno:9401", operator = RailOperator.OTHER, operatorDisplayName = null,
                trainCategory = TrainCategory.OTHER, bikesAllowed = null, delayMinutes = 7,
                officialDelayMinutes = 7, status = ServiceStatus.DELAYED, operationalStatusVerified = true)
        }))
        val result = StationBoardEnrichedTrainDataSource(source { DataResult.Success(planned) }, emptyList(), clock, zone,
            listOf(source { live })).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        val merged = result.snapshot.services.single()
        assertEquals("viaggiatreno:9401", merged.id)
        assertEquals(7, merged.delayMinutes)
        assertEquals(RailOperator.TRENORD, merged.operator)
        assertEquals("Trenord", merged.operatorDisplayName)
        assertEquals(planned.services.single().trainCategory, merged.trainCategory)
        assertEquals(true, merged.bikesAllowed)
        // A live run that states its operator keeps it.
        val stated = DataResult.Success(live.snapshot.copy(services = live.snapshot.services.map { it.copy(operator = RailOperator.TRENITALIA_REGIONALE) }))
        val kept = StationBoardEnrichedTrainDataSource(source { DataResult.Success(planned) }, emptyList(), clock, zone,
            listOf(source { stated })).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(RailOperator.TRENITALIA_REGIONALE, kept.snapshot.services.single().operator)
    }

    /**
     * Audit android-data-2: with a verified ViaggiaTreno route the boards still reconcile the other trains: the Italo
     * train it never lists keeps the Italo board delay, and a run it lists unverified keeps the RFI board delay.
     */
    @Test
    fun `una tratta ViaggiaTreno verificata non nasconde Italo e non salta la riconciliazione dei tabelloni`() = runBlocking {
        val frecciarossa = plannedSnapshot().services.single()
        val italo = frecciarossa.copy(id = "gtfs:I1", number = "9921", operator = RailOperator.ITALO, operatorDisplayName = "Italo",
            trainCategory = TrainCategory.AV_ITALO, scheduledDeparture = "12:40", expectedDeparture = "12:40",
            scheduledDepartureDateTime = LocalDateTime.of(2026, 9, 4, 12, 40))
        val regional = frecciarossa.copy(id = "gtfs:R1", number = "9405", scheduledDeparture = "12:50", expectedDeparture = "12:50",
            scheduledDepartureDateTime = LocalDateTime.of(2026, 9, 4, 12, 50))
        val planned = plannedSnapshot().copy(services = listOf(frecciarossa, italo, regional))
        val live = plannedSnapshot().copy(providerName = "ViaggiaTreno", services = listOf(
            frecciarossa.copy(id = "viaggiatreno:9401", operationalStatusVerified = true, delayMinutes = 2, officialDelayMinutes = 2,
                expectedDeparture = "12:32", operationalSourceIds = setOf("viaggiatreno")),
            regional.copy(id = "viaggiatreno:9405", operationalStatusVerified = false)
        ))
        val rfi = SourceId("rfi-station-board")
        val italoBoard = SourceId("italo-status")
        val providers = listOf(
            FakeBoardProvider(rfi, "RFI", board(rfi, "9405", zdt(12, 50), zdt(12, 57), "4", RunStatus.DELAYED)),
            FakeBoardProvider(italoBoard, "Italo", board(italoBoard, "9921", zdt(12, 40), zdt(12, 44), "9", RunStatus.DELAYED, operator = "italo"))
        )
        val wrapper = StationBoardEnrichedTrainDataSource(source { DataResult.Success(planned) }, providers, clock, zone,
            listOf(source { DataResult.Success(live) }))

        val result = (wrapper.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services
        assertEquals(listOf("viaggiatreno:9401", "gtfs:I1", "gtfs:R1"), result.map { it.id })
        assertEquals(listOf(2, 4, 7), result.map { it.delayMinutes })
        assertTrue(result.all { it.operationalStatusVerified })
        val observed = wrapper.observeRoute(route, Direction.OUTBOUND, query).toList()
        assertEquals(wrapper.loadRoute(route, Direction.OUTBOUND, query), observed.last())
    }

    @Test
    fun `live indisponibile conserva il fallback programmato senza inventare ritardi`() = runBlocking {
        val calls = mutableListOf<String>()
        val planned = DataResult.Success(plannedSnapshot())
        val wrapper = StationBoardEnrichedTrainDataSource(
            source { calls.add("planned"); planned }, emptyList(), clock, zone,
            listOf(source { calls.add("live"); DataResult.Failure("offline") })
        )
        val result = wrapper.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success
        assertEquals(listOf("live", "planned"), calls)
        assertFalse(result.snapshot.services.single().operationalStatusVerified)
    }

    @Test
    fun `uno zero non verificato della prima fonte non nasconde sei minuti verificati del fallback`() = runBlocking {
        val unknown = DataResult.Success(plannedSnapshot())
        val fresh = DataResult.Success(plannedSnapshot().copy(services = plannedSnapshot().services.map {
            it.copy(delayMinutes = 6, officialDelayMinutes = 6, expectedDeparture = "12:36", operationalStatusVerified = true)
        }))
        val wrapper = StationBoardEnrichedTrainDataSource(source { DataResult.Success(plannedSnapshot()) },
            emptyList(), clock, zone, listOf(source { unknown }, source { fresh }))
        assertEquals(fresh.snapshot.services,
            (wrapper.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services)
    }

    @Test
    fun `sorgente diretta live non viene chiamata per ricerche future`() = runBlocking {
        val wrapper = StationBoardEnrichedTrainDataSource(
            source { DataResult.Success(plannedSnapshot()) }, emptyList(), clock, zone,
            listOf(source { error("Nessun realtime per date future") })
        )
        assertTrue(wrapper.loadRoute(route, Direction.OUTBOUND,
            query.copy(serviceDate = query.serviceDate.plusDays(1))) is DataResult.Success)
    }

    private fun source(result: () -> DataResult) = object : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = result()
        override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult = result()
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
        routeChange: OperationalRouteChange? = null,
        operator: String = "trenitalia"
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
                    operatorId = OperatorId(operator),
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
        private val board: StationBoard,
        private val onRequest: (StationBoardRequest) -> Unit = {}
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
                board.also { onRequest(request) },
                ProviderResponseMeta(
                    providerId = descriptor.id,
                    fetchedAt = board.snapshot.generatedAt,
                    observedAt = board.snapshot.generatedAt,
                    expiresAt = board.snapshot.validUntil
                )
            )
    }
}
