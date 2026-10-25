package it.binario.app.data.repository

import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness as CoreFreshness
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.live.SwissOpenTransportStationBoardAdapter
import it.binario.app.data.live.sourceFixture
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** transport.opendata.ch rows join the existing Swiss GTFS services and other boards instead of adding duplicates. */
class SwissBoardAggregationTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T20:10:00Z"))
    private val rome = ZoneId.of("Europe/Rome")
    private val lugano = Station("gtfs:ch-otd-gtfs:8505300", "Lugano", "Lugano", "Open Data Platform Mobility Switzerland — GTFS")
    private val swiss = SwissOpenTransportStationBoardAdapter(
        RecordingPublicSourceFetcher { sourceFixture("opendata-ch-stationboard-lugano-departures.json") }, clock, 40, 0
    )

    @Test fun `a Swiss row merges with the same run from another board and keeps the line label`() = runBlocking {
        val scheduled = LocalDateTime.of(2026, 10, 25, 21, 30).atZone(rome)
        val other = staticProvider("rfi-programmed", StationBoardEntry(
            runId = TrainRunId("rfi-programmed:28"), scheduledTime = scheduled,
            destinationOrOrigin = StationId("published:zurichhb"), destinationOrOriginLabel = "Zürich HB", publicNumber = "28"
        ))
        val result = StationBoardAggregateService(listOf(other, swiss), clock, providerDispatcher = Dispatchers.Default).load(lugano) as StationBoardAggregateResult.Success
        val ec28 = result.board.entries.filter { it.publicNumber == "28" }
        assertEquals(1, ec28.size)
        assertEquals("EC 28", ec28.single().lineLabel)
        assertEquals("3", ec28.single().platform?.value)
        assertEquals(LocalDateTime.of(2026, 10, 25, 21, 31).atZone(rome).toInstant(), ec28.single().expectedTime?.value?.toInstant())
        assertEquals(3, result.board.entries.size)
    }

    @Test fun `the route reconciler enriches an embedded Swiss GTFS service by its journey number`() = runBlocking {
        val route = SavedRoute("lugano-zurigo", "Lugano → Zürich HB", lugano, Station("gtfs:ch-otd-gtfs:8503000", "Zürich HB", "Zürich"))
        val service = TrainService(
            id = "ch-otd-gtfs:28:2026-10-25", number = "28", operator = RailOperator.OTHER, trainCategory = TrainCategory.OTHER,
            origin = "Lugano", destination = "Zürich HB", scheduledDeparture = "21:30", expectedDeparture = "21:30",
            platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME, operatorDisplayName = "SBB",
            stops = listOf(TrainStop("Lugano", "21:30", "21:30", null, 0, false, operationalStatusVerified = false),
                TrainStop("Zürich HB", "23:38", "23:38", null, 0, false, operationalStatusVerified = false)),
            operationalStatusVerified = false, scheduledDepartureDateTime = LocalDateTime.of(2026, 10, 25, 21, 30)
        )
        val snapshot = RouteSnapshot(route, Direction.OUTBOUND, listOf(service), "Swiss GTFS", clock.millis(),
            clock.millis() + 600_000, Freshness.FRESH, confidence = DataConfidence.STATIC_ONLY)
        val enriched = StationBoardRealtimeReconciler(listOf(swiss), clock, rome)
            .enrich(snapshot, JourneyQuery(java.time.LocalDate.of(2026, 10, 25), java.time.LocalTime.of(21, 10)))
        val train = enriched.services.single()
        assertEquals("21:31", train.expectedDeparture)
        assertEquals(1, train.delayMinutes)
        assertEquals("3", train.platform)
        assertTrue(train.operationalStatusVerified)
        assertTrue("ch-transport-opendata" in train.operationalSourceIds)
    }

    private fun staticProvider(id: String, entry: StationBoardEntry) = object : StationBoardProvider {
        override val descriptor = ProviderDescriptor(SourceId(id), id, setOf(ProviderCapability.STATION_BOARD),
            CoverageDescriptor(description = "fixture"), AccessMode.OPEN_DIRECT,
            RatePolicy(Duration.ofSeconds(1), defaultTimeToLive = Duration.ofMinutes(1)), 1)

        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
            val source = SourceRef(SourceId(id), fetchedAt = clock.instant(), confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = id)
            return ProviderResult.Success(
                StationBoard(request.stationId, BoardKind.DEPARTURES, request.from, request.to, listOf(entry),
                    SnapshotMeta(1, clock.instant(), clock.instant().plusSeconds(60), CoreFreshness.FRESH, Completeness.PARTIAL, setOf(source))),
                ProviderResponseMeta(SourceId(id), clock.instant())
            )
        }
    }
}
