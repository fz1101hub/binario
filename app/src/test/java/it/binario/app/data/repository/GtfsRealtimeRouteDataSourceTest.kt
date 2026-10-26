package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.RealtimeStopEventKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.gtfs.ResolvedScheduledStop
import it.binario.app.data.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class GtfsRealtimeRouteDataSourceTest {
    private val now = Instant.parse("2026-10-26T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("Europe/Rome")
    private val staticFeed = SourceId("alto-adige-sta-gtfs")
    private val liveFeed = SourceId("alto-adige-sta-realtime")
    private val route = SavedRoute("route", "Tratta", Station("a", "Bolzano", "Bolzano"), Station("b", "Merano", "Merano"))
    private val source = SourceRef(liveFeed, observedAt = now, fetchedAt = now, expiresAt = now.plusSeconds(90), confidence = Confidence.OPEN_DATA, traceId = "test")
    private val scheduled = ResolvedScheduledStop(staticFeed, "trip", "a", 1, "Bolzano", day.atTime(8, 5).atZone(zone))
    private val train = TrainService(
        id = "${staticFeed.value}:trip:$day", number = "123", operator = RailOperator.OTHER, trainCategory = TrainCategory.REGIONALE,
        origin = "Bolzano", destination = "Merano", scheduledDeparture = "08:05", expectedDeparture = "08:05", platform = null,
        delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = listOf(TrainStop("Bolzano", "08:05", "08:05", null, 0, false)),
        operationalStatusVerified = false, scheduledDepartureDateTime = scheduled.scheduledAt.toLocalDateTime()
    )
    private val snapshot = RouteSnapshot(route, Direction.OUTBOUND, listOf(train), "GTFS", clock.millis(), clock.millis() + 600_000, Freshness.FRESH, confidence = DataConfidence.STATIC_ONLY)
    private val update = RealtimeStopUpdate(
        TrainRunId("trip"), "trip", "a", 1, predictedAt = SourcedValue(scheduled.scheduledAt.plusMinutes(7), source),
        officialDelay = SourcedValue(Duration.ofMinutes(7), source), observedAt = now, source = source, serviceDate = day,
        eventKind = RealtimeStopEventKind.DEPARTURE
    )

    @Test fun `confirmed same feed trip day and stop enrich the actual route`() = runBlocking {
        val result = load(listOf(update))
        val service = result.services.single()
        assertEquals("08:12", service.expectedDeparture)
        assertEquals(7, service.delayMinutes)
        assertTrue(service.operationalStatusVerified)
        assertNull(service.platform)
        assertNull(service.lastOperationalDetection)
        assertEquals(source.expiresAt!!.toEpochMilli(), result.expiresAtEpochMillis)
        assertEquals(DataConfidence.OPEN_DATA, result.confidence)
    }

    @Test fun `missing or wrong service date cannot turn a repeated trip template live`() = runBlocking {
        for (date in listOf(null, day.minusDays(1), day.plusDays(1))) {
            assertFalse(load(listOf(update.copy(serviceDate = date))).services.single().operationalStatusVerified)
        }
    }

    @Test fun `expired future or unrelated observations leave the schedule intact`() = runBlocking {
        val rejected = listOf(
            update.copy(source = source.copy(expiresAt = now)),
            update.copy(observedAt = now.plusSeconds(61)),
            update.copy(sourceTripId = "unrelated"),
            update.copy(source = source.copy(sourceId = SourceId("other-feed")))
        )
        for (candidate in rejected) assertEquals("08:05", load(listOf(candidate)).services.single().expectedDeparture)
    }

    @Test fun `ambiguous origin observations do not manufacture a delay`() = runBlocking {
        assertFalse(load(listOf(update, update)).services.single().operationalStatusVerified)
    }

    @Test fun `irrelevant providers and planned dates produce no realtime requests`() = runBlocking {
        val provider = FakeProvider(listOf(update))
        load(listOf(update), candidate = snapshot.copy(services = listOf(train.copy(id = "other:trip:$day"))), provider = provider)
        load(listOf(update), query = JourneyQuery(day.plusDays(1), LocalTime.of(8, 0)), provider = provider)
        assertEquals(0, provider.calls)
    }

    @Test fun `a prediction without an official delay stays explicitly derived`() = runBlocking {
        val service = load(listOf(update.copy(officialDelay = null))).services.single()
        assertTrue(service.operationalStatusVerified)
        assertEquals(7, service.delayMinutes)
        assertNull(service.officialDelayMinutes)
    }

    @Test fun `conflicting or overflowing delay values do not become operational status`() = runBlocking {
        val conflicting = update.copy(officialDelay = SourcedValue(Duration.ofMinutes(20), source))
        val overflowing = update.copy(officialDelay = SourcedValue(Duration.ofMinutes(Int.MAX_VALUE.toLong() + 8), source))
        for (candidate in listOf(conflicting, overflowing)) {
            assertFalse(load(listOf(candidate)).services.single().operationalStatusVerified)
        }
    }

    @Test fun `an arrival or unknown event cannot become a departure prediction`() = runBlocking {
        for (event in listOf(RealtimeStopEventKind.ARRIVAL, RealtimeStopEventKind.UNKNOWN)) {
            assertFalse(load(listOf(update.copy(eventKind = event))).services.single().operationalStatusVerified)
        }
    }

    private suspend fun load(
        updates: List<RealtimeStopUpdate>, candidate: RouteSnapshot = snapshot,
        query: JourneyQuery = JourneyQuery(day, LocalTime.of(8, 0)), provider: FakeProvider = FakeProvider(updates)
    ): RouteSnapshot {
        val planned = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction) = DataResult.Success(candidate)
        }
        val repository = GtfsRealtimeRouteDataSource(planned, mapOf(staticFeed to provider), { _, _, _, _ -> scheduled }, clock, zone)
        return (repository.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot
    }

    private inner class FakeProvider(private val updates: List<RealtimeStopUpdate>) : TripUpdateProvider {
        var calls = 0
        override val descriptor = ProviderDescriptor(liveFeed, "STA", setOf(ProviderCapability.TRAIN_RUN_REALTIME), CoverageDescriptor(description = "Test"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ofSeconds(60), defaultTimeToLive = Duration.ofSeconds(90)), 1)
        override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
            calls++
            return ProviderResult.Success(updates, ProviderResponseMeta(liveFeed, now, observedAt = now, expiresAt = now.plusSeconds(90)))
        }
    }
}
