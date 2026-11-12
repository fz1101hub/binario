package it.binario.app.data.repository

import it.binario.app.UiMessageKey
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.Precision
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.stationBoardWarningKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle

class StationBoardAggregateServiceTest {
    private val now = Instant.parse("2026-09-05T08:00:00Z")
    private val zone = ZoneId.of("Europe/Rome")
    private val scheduled = now.plus(Duration.ofHours(2)).atZone(zone)
    private val station = Station("milano-centrale", "Milano Centrale", "Milano", "Lombardia")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a fast board is visible before an optional source times out`() = kotlinx.coroutines.test.runTest {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val fast = provider("rfi-station-board", "RFI", board(source, 6, "4", RunStatus.DELAYED))
        var slowCancelled = false
        val slow = object : StationBoardProvider by fast {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
                try { kotlinx.coroutines.awaitCancellation() } finally { slowCancelled = true }
            }
        }
        val updates = mutableListOf<StationBoardAggregateResult>()
        val job = launch {
            StationBoardAggregateService(listOf(fast, slow), Clock.fixed(now, ZoneId.of("UTC")),
                providerTimeoutMillis = 1_000, providerDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
                .observe(station).collect { updates += it }
        }
        runCurrent()
        assertEquals(1, updates.size)
        assertEquals("4", (updates.single() as StationBoardAggregateResult.Success).board.entries.single().platform?.value)
        assertTrue(!(updates.single() as StationBoardAggregateResult.Success).isComplete)
        advanceUntilIdle()
        job.join()
        val final = updates.last() as StationBoardAggregateResult.Success
        assertTrue(final.isComplete)
        assertTrue(slowCancelled)
        assertTrue(ProviderWarning.REALTIME_UNAVAILABLE in final.board.warnings)
    }

    @Test
    fun `a provider throwing an exception cannot erase another providers board`() = kotlinx.coroutines.runBlocking {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val fast = provider("rfi-station-board", "RFI", board(source, 6, "4", RunStatus.DELAYED))
        val broken = object : StationBoardProvider by fast {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> = throw IllegalStateException("bad upstream")
        }
        val result = StationBoardAggregateService(listOf(broken, fast), Clock.fixed(now, ZoneId.of("UTC"))).load(station)
            as StationBoardAggregateResult.Success
        assertEquals(1, result.board.entries.size)
        assertTrue(ProviderWarning.REALTIME_UNAVAILABLE in result.board.warnings)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `leaving a board cancels its pending providers`() = kotlinx.coroutines.test.runTest {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val fast = provider("rfi-station-board", "RFI", board(source, 6, "4", RunStatus.DELAYED))
        var cancelled = false
        val slow = object : StationBoardProvider by fast {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
            }
        }
        val job = launch {
            StationBoardAggregateService(listOf(slow), Clock.fixed(now, ZoneId.of("UTC")),
                providerDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).observe(station).collect {}
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(cancelled)
    }

    @Test
    fun `merging infrastructure fields preserves the operator native run identity`() = kotlinx.coroutines.runBlocking {
        val rfi = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val vt = source("viaggiatreno", Confidence.OPERATOR_PRIMARY)
        val programmed = board(rfi, 6, "4", RunStatus.DELAYED)
        val live = board(vt, 6, "5", RunStatus.DELAYED).let { b ->
            b.copy(entries = b.entries.map { it.copy(nativeRunIds = mapOf(SourceId("viaggiatreno") to "S08001:1234:1788559200000")) })
        }
        val result = StationBoardAggregateService(listOf(provider("rfi-station-board", "RFI", programmed), provider("viaggiatreno", "VT", live)),
            Clock.fixed(now, ZoneId.of("UTC"))).load(station) as StationBoardAggregateResult.Success
        assertEquals("4", result.board.entries.single().platform?.value)
        assertEquals("S08001:1234:1788559200000", result.board.entries.single().nativeRunIds[SourceId("viaggiatreno")])
        val expired = result.board.withoutExpiredObservations(now.plusSeconds(600)).entries.single()
        assertEquals(null, expired.expectedTime)
        assertEquals(null, expired.status)
        assertEquals(programmed.entries.single().scheduledTime, expired.scheduledTime)
    }

    @Test
    fun `field authority is category aware and source conflicts are preserved`() = kotlinx.coroutines.runBlocking {
        val rfiSource = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val italoSource = source("italo-status", Confidence.OPERATOR_PRIMARY)

        val rfi = provider(
            id = "rfi-station-board",
            name = "RFI",
            board = board(
                source = rfiSource,
                expectedMinutes = 8,
                platform = "4",
                status = RunStatus.DELAYED
            )
        )
        val italo = provider(
            id = "italo-status",
            name = "Italo",
            board = board(
                source = italoSource,
                expectedMinutes = 5,
                platform = "7",
                status = RunStatus.ON_TIME
            )
        )

        val result = StationBoardAggregateService(
            providers = listOf(italo, rfi),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station)

        val success = result as StationBoardAggregateResult.Success
        val entry = success.board.entries.single()

        // Infrastructure authority wins the station/platform field.
        assertEquals("4", entry.platform?.value)
        assertEquals(SourceId("rfi-station-board"), entry.platform?.source?.sourceId)

        // Operator realtime affinity wins expected time/status for an Italo run.
        assertEquals(scheduled.plusMinutes(5).toInstant(), entry.expectedTime?.value?.toInstant())
        assertEquals(SourceId("italo-status"), entry.expectedTime?.source?.sourceId)
        assertEquals(RunStatus.ON_TIME, entry.status?.value)
        assertEquals(SourceId("italo-status"), entry.status?.source?.sourceId)

        assertTrue(ProviderWarning.SOURCE_CONFLICT in success.board.warnings)
        assertEquals(listOf("Italo", "RFI"), success.board.providerNames)
    }

    @Test
    fun `same number outside identity tolerance remains two board entries`() = kotlinx.coroutines.runBlocking {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val first = board(source = source, expectedMinutes = 0, platform = "1", status = RunStatus.ON_TIME)
        val second = first.copy(
            entries = first.entries.map {
                it.copy(
                    runId = TrainRunId("second"),
                    scheduledTime = it.scheduledTime.plusMinutes(12),
                    expectedTime = it.expectedTime?.copy(value = it.expectedTime.value.plusMinutes(12))
                )
            }
        )

        val result = StationBoardAggregateService(
            providers = listOf(
                provider("rfi-station-board", "RFI A", first),
                provider("rfi-station-board", "RFI B", second)
            ),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station)

        assertEquals(2, (result as StationBoardAggregateResult.Success).board.entries.size)
    }

    @Test
    fun `same number time and endpoint never merge across known operators`() = kotlinx.coroutines.runBlocking {
        val rfiSource = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val italoSource = source("italo-status", Confidence.OPERATOR_PRIMARY)
        val first = board(rfiSource, 0, "4", RunStatus.DELAYED)
        val second = board(italoSource, 4, "7", RunStatus.ON_TIME).copy(
            entries = board(italoSource, 4, "7", RunStatus.ON_TIME).entries.map {
                it.copy(
                    runId = TrainRunId("italo-native-other-run"),
                    scheduledTime = it.scheduledTime.plusMinutes(4),
                    operatorId = OperatorId("italo")
                )
            }
        )
        val rfi = first.copy(entries = first.entries.map { it.copy(operatorId = OperatorId("trenitalia")) })

        val result = StationBoardAggregateService(
            providers = listOf(
                provider("rfi-station-board", "RFI", rfi),
                provider("italo-status", "Italo", second)
            ),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station) as StationBoardAggregateResult.Success

        assertEquals(2, result.board.entries.size)
        assertTrue(ProviderWarning.IDENTITY_AMBIGUOUS in result.board.warnings)
    }

    @Test
    fun `same source cannot merge two native run ids inside tolerance`() = kotlinx.coroutines.runBlocking {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val first = board(source, 0, "1", RunStatus.ON_TIME)
        val second = first.copy(
            entries = first.entries.map {
                it.copy(
                    runId = TrainRunId("rfi-native-second-run"),
                    scheduledTime = it.scheduledTime.plusMinutes(4),
                    expectedTime = it.expectedTime?.copy(value = it.expectedTime.value.plusMinutes(4))
                )
            }
        )

        val result = StationBoardAggregateService(
            providers = listOf(
                provider("rfi-station-board", "RFI A", first),
                provider("rfi-station-board", "RFI B", second)
            ),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station) as StationBoardAggregateResult.Success

        assertEquals(2, result.board.entries.size)
        assertTrue(ProviderWarning.IDENTITY_AMBIGUOUS in result.board.warnings)
    }

    @Test
    fun `selected replacement service is exposed as board warning`() = kotlinx.coroutines.runBlocking {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val plain = board(source, 0, "1", RunStatus.REPLACED_BY_BUS)
        val replacement = plain.copy(
            entries = plain.entries.map {
                it.copy(
                    routeChange = SourcedValue(
                        OperationalRouteChange(
                            kind = RouteChangeKind.REPLACEMENT_SERVICE,
                            summary = "Servizio sostitutivo con autobus"
                        ),
                        source
                    )
                )
            }
        )

        val result = StationBoardAggregateService(
            providers = listOf(provider("rfi-station-board", "RFI", replacement)),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station) as StationBoardAggregateResult.Success

        assertTrue(ProviderWarning.REPLACEMENT_SERVICE in result.board.warnings)
    }

    @Test
    fun `no enabled provider is explicitly unavailable without network work`() = kotlinx.coroutines.runBlocking {
        val result = StationBoardAggregateService(
            providers = emptyList(),
            clock = Clock.fixed(now, ZoneId.of("UTC"))
        ).load(station)

        assertTrue(result is StationBoardAggregateResult.Unavailable)
    }

    @Test
    fun `a station every source says it does not cover is final, a source failure is not`() = kotlinx.coroutines.runBlocking {
        val source = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)
        val fast = provider("rfi-station-board", "RFI", board(source, 6, "4", RunStatus.DELAYED))
        fun failing(kind: it.binario.app.data.provider.ProviderErrorKind) = object : StationBoardProvider by fast {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
                ProviderResult.Failure(it.binario.app.data.provider.ProviderError(kind, "test"))
        }
        val unsupported = it.binario.app.data.provider.ProviderErrorKind.NOT_SUPPORTED
        val network = it.binario.app.data.provider.ProviderErrorKind.NETWORK

        val uncovered = StationBoardAggregateService(listOf(failing(unsupported), failing(unsupported)), Clock.fixed(now, ZoneId.of("UTC"))).load(station)
        val mixed = StationBoardAggregateService(listOf(failing(unsupported), failing(network)), Clock.fixed(now, ZoneId.of("UTC"))).load(station)

        assertTrue((uncovered as StationBoardAggregateResult.Unavailable).allUnsupported)
        assertTrue(!(mixed as StationBoardAggregateResult.Unavailable).allUnsupported)
    }

    // Live first: the live boards decide the board, the RFI programmed board is the fallback.

    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val programmedSource = source("rfi-programmed", Confidence.INFRASTRUCTURE_PRIMARY)
    private val rfiSource = source("rfi-station-board", Confidence.INFRASTRUCTURE_PRIMARY)

    private fun programmed(vararg trains: Pair<String, Long>) =
        provider("rfi-programmed", "RFI programmato", rows(programmedSource, live = false, *trains))

    private fun rfiMonitor(vararg trains: Pair<String, Long>) =
        provider("rfi-station-board", "RFI", rows(rfiSource, live = true, *trains))

    private fun numbers(result: StationBoardAggregateResult): List<String?> =
        (result as StationBoardAggregateResult.Success).board.entries.map { it.publicNumber }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `live rows decide the board and programmed rows only follow the end of the live span`() = kotlinx.coroutines.test.runTest {
        // 200 is listed by the programmed board alone inside the live span (up to +110 min); 400 and 500 come after it.
        val timetable = programmed("100" to 10, "200" to 30, "300" to 60, "400" to 180, "500" to 300)
        val monitor = rfiMonitor("100" to 10, "300" to 60, "600" to 110)
        fun StationBoardProvider.answeringAfter(millis: Long) = object : StationBoardProvider by this {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
                kotlinx.coroutines.delay(millis)
                return this@answeringAfter.getStationBoard(request)
            }
        }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)

        // The programmed board answers first: nothing is shown until the live board has answered.
        val programmedFirst = mutableListOf<StationBoardAggregateResult>()
        launch {
            StationBoardAggregateService(listOf(monitor.answeringAfter(1_000), timetable), clock, providerDispatcher = dispatcher)
                .observe(station).collect { programmedFirst += it }
        }
        runCurrent()
        assertTrue(programmedFirst.isEmpty())
        advanceUntilIdle()

        // The live board answers first: its rows at once, the programmed rows after its span when the timetable arrives.
        val liveFirst = mutableListOf<StationBoardAggregateResult>()
        launch {
            StationBoardAggregateService(listOf(monitor, timetable.answeringAfter(1_000)), clock, providerDispatcher = dispatcher)
                .observe(station).collect { liveFirst += it }
        }
        runCurrent()
        assertEquals(listOf(listOf("100", "300", "600")), liveFirst.map(::numbers))
        advanceUntilIdle()

        for (updates in listOf(programmedFirst, liveFirst)) {
            updates.forEach { assertTrue("200" !in numbers(it)) }
            val final = updates.last() as StationBoardAggregateResult.Success
            assertTrue(final.isComplete)
            assertEquals(listOf("100", "300", "600", "400", "500"), numbers(final))
            // A run both boards list keeps the live fields; the rows after the live span are marked programmed.
            assertEquals("2", final.board.entries.first().platform?.value)
            final.board.entries.filter { it.publicNumber in setOf("400", "500") }.forEach {
                assertEquals(null, it.expectedTime)
                assertEquals(null, it.status)
                assertEquals(null, it.platform)
            }
            assertTrue(ProviderWarning.REALTIME_UNAVAILABLE !in final.board.warnings)
            assertEquals(listOf("RFI", "RFI programmato"), final.board.providerNames)
        }

        // Switched off, the board ends with the live span; the timetable still fills in the runs both boards list.
        val liveOnly = StationBoardAggregateService(listOf(timetable, monitor), clock, providerDispatcher = dispatcher,
            programmedRowsAfterLiveSpan = false).load(station) as StationBoardAggregateResult.Success
        assertEquals(listOf("100", "300", "600"), numbers(liveOnly))
        assertEquals(listOf("RFI", "RFI programmato"), liveOnly.board.providerNames)
    }

    @Test
    fun `when every live board fails or is empty the programmed rows are shown with the unavailable notice`() = kotlinx.coroutines.runBlocking {
        val timetable = programmed("100" to 10, "200" to 30)
        val down = failing("rfi-station-board", ProviderErrorKind.NETWORK)
        val notCovered = failing("ch-transport-opendata", ProviderErrorKind.NOT_SUPPORTED)
        // At night: the live board answers, without trains in its span.
        val empty = provider("viaggiatreno", "VT", rows(source("viaggiatreno", Confidence.OPERATOR_PRIMARY), live = true))
        for (live in listOf(listOf(down, notCovered), listOf(empty), listOf(down, empty), listOf(notCovered), emptyList())) {
            val result = StationBoardAggregateService(live + timetable, clock).load(station) as StationBoardAggregateResult.Success
            assertEquals(listOf("100", "200"), numbers(result))
            assertTrue(result.board.entries.all { it.expectedTime == null && it.status == null })
            assertTrue(ProviderWarning.REALTIME_UNAVAILABLE in result.board.warnings)
            assertEquals("RFI programmato", result.board.providerNames.last())
        }
        // The screen shows that warning as its "realtime unavailable" notice.
        assertEquals(UiMessageKey.STATION_BOARD_REALTIME_UNAVAILABLE, ProviderWarning.REALTIME_UNAVAILABLE.stationBoardWarningKey())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a live board timing out after the programmed answer shows the programmed rows only when it ends`() = kotlinx.coroutines.test.runTest {
        val monitor = rfiMonitor("100" to 10)
        val hanging = object : StationBoardProvider by monitor {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> = kotlinx.coroutines.awaitCancellation()
        }
        val updates = mutableListOf<Pair<Long, StationBoardAggregateResult>>()
        launch {
            StationBoardAggregateService(listOf(hanging, programmed("100" to 10, "200" to 30)), clock, providerTimeoutMillis = 1_000,
                providerDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
                .observe(station).collect { updates += testScheduler.currentTime to it }
        }
        runCurrent()
        testScheduler.advanceTimeBy(999)
        runCurrent()
        assertTrue(updates.isEmpty())
        advanceUntilIdle()
        val (shownAt, result) = updates.single()
        assertEquals(1_000L, shownAt)
        assertTrue((result as StationBoardAggregateResult.Success).isComplete)
        assertEquals(listOf("100", "200"), numbers(result))
        assertTrue(ProviderWarning.REALTIME_UNAVAILABLE in result.board.warnings)
    }

    @Test
    fun `an operator only live board does not stand for the station live board`() = kotlinx.coroutines.runBlocking {
        // RFI and ViaggiaTreno are down, Italo answers for its own train: the board falls back to the timetable.
        val italo = provider("italo-status", "Italo", rows(source("italo-status", Confidence.OPERATOR_PRIMARY), live = true, "9920" to 120))
        val timetable = programmed("100" to 10, "200" to 70, "9920" to 120)
        val result = StationBoardAggregateService(
            listOf(failing("rfi-station-board", ProviderErrorKind.NETWORK), italo, timetable), clock
        ).load(station) as StationBoardAggregateResult.Success
        assertEquals(listOf("100", "200", "9920"), numbers(result))
        assertEquals(SourceId("italo-status"), result.board.entries.last().expectedTime?.source?.sourceId)
        assertTrue(ProviderWarning.REALTIME_UNAVAILABLE in result.board.warnings)

        // With the station live board up (span to +10 min), Italo's later row does not stretch the span over 200.
        val withMonitor = StationBoardAggregateService(listOf(rfiMonitor("100" to 10), italo, timetable), clock)
            .load(station) as StationBoardAggregateResult.Success
        assertEquals(listOf("100", "200", "9920"), numbers(withMonitor))
        assertEquals(null, withMonitor.board.entries[1].expectedTime)
        assertTrue(ProviderWarning.REALTIME_UNAVAILABLE !in withMonitor.board.warnings)
    }

    @Test
    fun `the station widget reads the same board as the screen and keeps programmed rows without live claims`() = kotlinx.coroutines.runBlocking {
        val timetable = programmed("100" to 10, "200" to 30, "400" to 180)
        val cases = listOf(
            listOf(rfiMonitor("100" to 10, "600" to 110), timetable) to listOf("100", "600", "400"),
            listOf(failing("rfi-station-board", ProviderErrorKind.NETWORK), timetable) to listOf("100", "200", "400")
        )
        for ((providers, expected) in cases) {
            val service = StationBoardAggregateService(providers, clock)
            val screen = service.observe(station).toList().last() as StationBoardAggregateResult.Success
            // StationWidgetRefresher: service.load, then StationWidgetSnapshot.fromBoard.
            val widget = service.load(station) as StationBoardAggregateResult.Success
            assertEquals(screen.board, widget.board)
            assertEquals(expected, numbers(widget))
            val snapshot = it.binario.app.widget.StationWidgetSnapshot.fromBoard(widget.board, now)
            assertEquals(expected, snapshot.departures.map { it.number })
            val live = widget.board.entries.filter { it.expectedTime != null }.map { it.publicNumber }.toSet()
            snapshot.departures.forEach { assertEquals(it.number in live, it.hasObservation) }
        }
    }

    /** One row per (number, minutes from now) to Roma Termini; live rows carry an expected time, platform and status. */
    private fun rows(source: SourceRef, live: Boolean, vararg trains: Pair<String, Long>): StationBoard {
        val entries = trains.map { (number, minutes) ->
            val time = now.plus(Duration.ofMinutes(minutes)).atZone(zone)
            StationBoardEntry(
                runId = TrainRunId("${source.sourceId.value}:$number"),
                scheduledTime = time,
                expectedTime = if (live) SourcedValue(time.plusMinutes(3), source, Precision.MINUTE) else null,
                destinationOrOrigin = StationId("roma-termini"),
                destinationOrOriginLabel = "Roma Termini",
                publicNumber = number,
                platform = if (live) SourcedValue("2", source) else null,
                status = if (live) SourcedValue(RunStatus.DELAYED, source) else null
            )
        }
        return StationBoard(
            stationId = StationId(station.id),
            kind = BoardKind.DEPARTURES,
            from = now,
            to = now.plus(Duration.ofHours(6)),
            entries = entries,
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = now,
                validUntil = now.plus(Duration.ofMinutes(2)),
                freshness = Freshness.FRESH,
                completeness = if (live) Completeness.PARTIAL else Completeness.STATIC_ONLY,
                contributingSources = setOf(source)
            )
        )
    }

    private fun failing(id: String, kind: ProviderErrorKind): StationBoardProvider = object : StationBoardProvider by provider(id, id, rows(source(id, Confidence.OPERATOR_PRIMARY), live = true)) {
        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
            ProviderResult.Failure(ProviderError(kind, "test"))
    }

    private fun board(
        source: SourceRef,
        expectedMinutes: Long,
        platform: String,
        status: RunStatus
    ): StationBoard {
        val entry = StationBoardEntry(
            runId = TrainRunId("italo-9920"),
            scheduledTime = scheduled,
            expectedTime = SourcedValue(scheduled.plusMinutes(expectedMinutes), source, Precision.MINUTE),
            destinationOrOrigin = StationId("roma-termini"),
            destinationOrOriginLabel = "Roma Termini",
            publicNumber = "9920",
            operatorId = OperatorId("italo"),
            platform = SourcedValue(platform, source),
            status = SourcedValue(status, source)
        )
        return StationBoard(
            stationId = StationId(station.id),
            kind = BoardKind.DEPARTURES,
            from = now,
            to = now.plus(Duration.ofHours(6)),
            entries = listOf(entry),
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = now,
                validUntil = now.plus(Duration.ofMinutes(2)),
                freshness = Freshness.FRESH,
                completeness = Completeness.COMPLETE,
                contributingSources = setOf(source)
            )
        )
    }

    private fun source(id: String, confidence: Confidence) = SourceRef(
        sourceId = SourceId(id),
        fetchedAt = now,
        observedAt = now,
        expiresAt = now.plus(Duration.ofMinutes(2)),
        confidence = confidence,
        traceId = "test-$id"
    )

    private fun provider(
        id: String,
        name: String,
        board: StationBoard
    ): StationBoardProvider = object : StationBoardProvider {
        override val descriptor = ProviderDescriptor(
            id = SourceId(id),
            displayName = name,
            capabilities = setOf(ProviderCapability.STATION_BOARD),
            coverage = CoverageDescriptor(description = "test"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(
                minimumRequestInterval = Duration.ZERO,
                defaultTimeToLive = Duration.ofMinutes(2)
            ),
            schemaVersion = 1
        )

        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
            ProviderResult.Success(
                value = board,
                meta = ProviderResponseMeta(
                    providerId = SourceId(id),
                    fetchedAt = now,
                    observedAt = now,
                    expiresAt = now.plus(Duration.ofMinutes(2))
                )
            )
    }
}
