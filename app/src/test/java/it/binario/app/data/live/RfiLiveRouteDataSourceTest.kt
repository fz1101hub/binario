package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.*
import it.binario.app.data.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RfiLiveRouteDataSourceTest {
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zone = ZoneId.of("Europe/Rome")
    private val route = SavedRoute("test", "Due fermate intermedie", Station("curated:S01234", "Origine", "Italia", railwayCode = "S01234"),
        Station("curated:S05678", "Destinazione", "Italia", railwayCode = "S05678"))
    private val query = JourneyQuery(LocalDate.of(2026, 10, 16), LocalTime.of(10, 0))
    private val calls = java.util.concurrent.atomic.AtomicInteger()

    @Test fun `stessa corsa sui due tabelloni riconosce una destinazione intermedia senza orari statici`() = runBlocking {
        val result = load(entry(10, 30, 12), entry(11, 0, 4)) as DataResult.Success
        val service = result.snapshot.services.single()
        assertEquals(12, service.officialDelayMinutes)
        assertEquals("10:42", service.expectedDeparture)
        assertEquals("11:04", service.stops.last().expected)
        assertEquals("Destinazione", service.destination)
        assertEquals(2, service.stops.size)
        assertEquals(setOf("rfi-station-board"), service.operationalSourceIds)
        assertTrue(service.operationalStatusVerified)
    }

    @Test fun `numero operatore ordine e univocita devono coincidere`() = runBlocking {
        val departures = entry(10, 30, 12)
        listOf(entry(11, 0, 0).copy(publicNumber = "999"),
            entry(11, 0, 0).copy(operatorId = OperatorId("italo")),
            entry(11, 0, 0).copy(operatorId = null), entry(10, 0, 0)).forEach { arrival ->
            assertTrue(load(departures, arrival) is DataResult.Failure)
        }
        assertTrue(load(departures, entry(11, 0, 0), duplicateArrival = true) is DataResult.Failure)
    }

    @Test fun `nessun ritardo inventato se solo il tabellone arrivi ha dati operativi`() = runBlocking {
        val departure = entry(10, 30, 0).copy(expectedTime = null, status = null)
        assertTrue(load(departure, entry(11, 0, 7)) is DataResult.Failure)
    }

    @Test fun `zero ufficiale e valido ma osservazioni scadute non lo sono`() = runBlocking {
        assertTrue((load(entry(10, 30, 0), entry(11, 0, 0)) as DataResult.Success).snapshot.services.single().operationalStatusVerified)
        val stale = entry(10, 30, 0).let { it.copy(expectedTime = it.expectedTime?.let { value -> value.copy(source = value.source.copy(observedAt = now.minusSeconds(301))) },
            status = it.status?.let { value -> value.copy(source = value.source.copy(observedAt = now.minusSeconds(301))) }) }
        assertTrue(load(stale, entry(11, 0, 0)) is DataResult.Failure)
    }

    @Test fun `cancellazione esplicita di arrivo conserva la soppressione della tratta`() = runBlocking {
        val arrival = entry(11, 0, 0).let { it.copy(status = it.status!!.copy(value = RunStatus.CANCELLED)) }
        assertEquals(ServiceStatus.CANCELLED, (load(entry(10, 30, 0), arrival) as DataResult.Success).snapshot.services.single().status)
    }

    @Test fun `date future e stazioni estere non interrogano i monitor RFI`() = runBlocking {
        val provider = provider(entry(10, 30, 0), entry(11, 0, 0))
        val source = RfiLiveRouteDataSource(provider, clock)
        source.loadRoute(route, Direction.OUTBOUND, query.copy(serviceDate = query.serviceDate.plusDays(1)))
        source.loadRoute(route.copy(outwardOrigin = Station("gtfs:fr:test", "Paris", "France")), Direction.OUTBOUND, query)
        assertEquals(0, calls.get())
    }

    @Test fun `orari fuori dalla finestra corrente non diventano corse del giorno successivo`() = runBlocking {
        assertTrue(load(entry(23, 30, 0), entry(23, 50, 0)) is DataResult.Failure)
    }

    @Test fun `cache riusa i due monitor senza una richiesta per ogni destinazione o refresh`() = runBlocking {
        val source = RfiLiveRouteDataSource(provider(entry(10, 30, 0), entry(11, 0, 0)), clock)
        repeat(2) { source.loadRoute(route, Direction.OUTBOUND, query) }
        assertEquals(2, calls.get())
    }

    private suspend fun load(departure: StationBoardEntry, arrival: StationBoardEntry, duplicateArrival: Boolean = false): DataResult =
        RfiLiveRouteDataSource(provider(departure, arrival, duplicateArrival), clock).loadRoute(route, Direction.OUTBOUND, query)

    private fun entry(hour: Int, minute: Int, delay: Int): StationBoardEntry {
        val scheduled = query.serviceDate.atTime(hour, minute).atZone(zone)
        val source = SourceRef(SourceId("rfi-station-board"), observedAt = now, fetchedAt = now, expiresAt = now.plusSeconds(45), confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "test")
        return StationBoardEntry(TrainRunId("test:123"), scheduled, SourcedValue(scheduled.plusMinutes(delay.toLong()), source),
            StationId("capolinea-diverso"), destinationOrOriginLabel = "Capolinea diverso dalle due fermate cercate", publicNumber = "123",
            operatorId = OperatorId("trenitalia"), status = SourcedValue(if (delay > 0) RunStatus.DELAYED else RunStatus.ON_TIME, source))
    }

    private fun provider(departure: StationBoardEntry, arrival: StationBoardEntry, duplicateArrival: Boolean = false) = object : StationBoardProvider {
        override val descriptor = RfiStationBoardAdapter().descriptor
        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
            calls.incrementAndGet()
            val entries = if (request.kind == BoardKind.DEPARTURES) listOf(departure) else if (duplicateArrival) listOf(arrival, arrival) else listOf(arrival)
            return ProviderResult.Success(StationBoard(request.stationId, request.kind, request.from, request.to, entries,
                SnapshotMeta(1, now, now.plusSeconds(45), it.binario.app.data.core.Freshness.FRESH, Completeness.PARTIAL, setOfNotNull(departure.expectedTime?.source))), ProviderResponseMeta(descriptor.id, now))
        }
    }
}
