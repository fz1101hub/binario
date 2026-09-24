package it.binario.app.data.repository

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
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationBoardAggregateServiceTest {
    private val now = Instant.parse("2026-09-05T08:00:00Z")
    private val zone = ZoneId.of("Europe/Rome")
    private val scheduled = now.plus(Duration.ofHours(2)).atZone(zone)
    private val station = Station("milano-centrale", "Milano Centrale", "Milano", "Lombardia")

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
