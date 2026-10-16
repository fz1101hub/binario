package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.*
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GtfsRealtimeEnrichedTrainDataSourceTest {
    private val now = Instant.parse("2026-09-04T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zone = ZoneId.of("Europe/Rome")
    private val date = LocalDate.of(2026, 9, 4)
    private val route = SavedRoute("test", "Test", Station("a", "A", "A"), Station("b", "B", "B"))
    private val query = JourneyQuery(date, LocalTime.NOON)
    private val sta = "alto-adige-sta-realtime"
    private val sncf = "sncf-gtfs-rt"
    private var calls = 0

    @Test fun `due fonti con stesso trip id restano separate e aggiornano tutte le fermate`() = runBlocking {
        val result = load(listOf(service("alto-adige-sta-gtfs"), service("sncf-gtfs")),
            listOf(provider(sta, listOf(update(sta, 420), update(sta, 540, stop = "B", sequence = 2))),
                provider(sncf, listOf(update(sncf, 120)))))
        assertEquals(listOf(7, 2), result.services.map { it.delayMinutes })
        assertEquals("12:07", result.services[0].expectedDeparture)
        assertEquals("12:39", result.services[0].stops.last().expected)
        assertTrue(result.services.all { it.operationalStatusVerified })
        assertEquals(setOf(sta), result.services[0].operationalSourceIds)
        assertEquals(setOf(sncf), result.services[1].operationalSourceIds)
        assertEquals(DataQualityBand.VERIFIED, result.qualityBand)
        assertFalse(SnapshotWarning.STATIC_ONLY in result.qualityWarnings)
    }

    @Test fun `zero esplicito e un dato live ma un evento privo di tempi resta sconosciuto`() = runBlocking {
        assertTrue(load(listOf(service()), listOf(provider(sta, listOf(update(sta, 0))))).services.single().operationalStatusVerified)
        val unknown = update(sta, 0).copy(officialDelay = null)
        assertFalse(load(listOf(service()), listOf(provider(sta, listOf(unknown)))).services.single().operationalStatusVerified)
    }

    @Test fun `scarta osservazioni scadute future e di altro giorno operativo`() = runBlocking {
        val invalid = listOf(
            update(sta, 600).copy(observedAt = now.minusSeconds(301)),
            update(sta, 600).copy(observedAt = now.plusSeconds(61)),
            update(sta, 600).copy(serviceDate = date.minusDays(1)),
            update(sta, 600).let { it.copy(source = it.source.copy(expiresAt = now)) }
        )
        invalid.forEach { value ->
            assertFalse(load(listOf(service()), listOf(provider(sta, listOf(value)))).services.single().operationalStatusVerified)
        }
    }

    @Test fun `nessuna contaminazione da trip fermata sequence o fonte diversa`() = runBlocking {
        val invalid = listOf(
            update(sta, 600).copy(sourceTripId = "OTHER"),
            update(sta, 600).copy(sourceStopId = "OTHER"),
            update(sta, 600).copy(stopSequence = 9),
            update(sncf, 600)
        )
        invalid.forEach { value ->
            assertFalse(load(listOf(service()), listOf(provider(sta, listOf(value)))).services.single().operationalStatusVerified)
        }
    }

    @Test fun `non interroga feed senza identita statica o per ricerche future`() = runBlocking {
        load(listOf(service().copy(gtfsIdentity = null)), listOf(provider(sta, listOf(update(sta, 60)))))
        load(listOf(service()), listOf(provider(sta, listOf(update(sta, 60)))), query.copy(serviceDate = date.plusDays(1)))
        load(listOf(service("oebb-gtfs")), listOf(provider(sta, listOf(update(sta, 60)))))
        assertEquals(0, calls)
    }

    @Test fun `guasto del feed conserva il risultato programmato senza inventare ritardi`() = runBlocking {
        val failed = provider(sta, emptyList(), fail = true)
        val result = load(listOf(service()), listOf(failed))
        assertFalse(result.services.single().operationalStatusVerified)
        assertEquals(DataConfidence.STATIC_ONLY, result.confidence)
    }

    @Test fun `dato solo in arrivo aggiorna il dettaglio senza verificare la partenza`() = runBlocking {
        val result = load(listOf(service()), listOf(provider(sta, listOf(update(sta, 300, stop = "B", sequence = 2)))))
        assertFalse(result.services.single().operationalStatusVerified)
        assertEquals("12:35", result.services.single().stops.last().expected)
        assertEquals("12:00", result.services.single().expectedDeparture)
    }

    @Test fun `predizione assoluta calcola il ritardo senza creare un evento effettivo`() = runBlocking {
        val update = update(sta, 0).let { it.copy(officialDelay = null,
            predictedAt = SourcedValue(date.atTime(12, 4).atZone(zone), it.source)) }
        val result = load(listOf(service()), listOf(provider(sta, listOf(update)))).services.single()
        assertEquals(4, result.delayMinutes)
        assertNull(result.lastOperationalDetection)
        assertNull(result.detectionDelayMinutes)
    }

    private suspend fun load(services: List<TrainService>, providers: List<TripUpdateProvider>, requested: JourneyQuery = query): RouteSnapshot {
        val snapshot = RouteSnapshot(route, Direction.OUTBOUND, services, "GTFS", now.toEpochMilli(),
            now.plusSeconds(3600).toEpochMilli(), it.binario.app.data.Freshness.AGING, confidence = DataConfidence.STATIC_ONLY)
        val planned = object : TrainDataSource {
            override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = DataResult.Success(snapshot)
        }
        val packs = listOf("alto-adige-sta-gtfs", "sncf-gtfs").map { EmbeddedGtfsPack(SourceId(it), it) }
        val resolver = EmbeddedGtfsRealtimeScheduleResolver({ ByteArrayInputStream(feed()) }, packs)
        val enriched = GtfsRealtimeEnrichedTrainDataSource(planned, RealtimeEnrichmentService(providers, resolver), clock, zone)
        return (LegacyTrainDataSourceFacade(enriched, KnownProviders.defaultRegistry(), clock::millis)
            .loadRoute(route, Direction.OUTBOUND, requested) as DataResult.Success).snapshot
    }

    private fun service(source: String = "alto-adige-sta-gtfs") = TrainService(
        id = "$source:T1:$date", number = "1", operator = RailOperator.OTHER, trainCategory = TrainCategory.REGIONALE,
        origin = "A", destination = "B", scheduledDeparture = "12:00", expectedDeparture = "12:00", platform = null,
        delayMinutes = 0, officialDelayMinutes = null, status = ServiceStatus.ON_TIME,
        stops = listOf(TrainStop("A", "12:00", "12:00", null, 0, false), TrainStop("B", "12:30", "12:30", null, 0, false)),
        operationalStatusVerified = false, scheduledDepartureDateTime = date.atTime(12, 0),
        gtfsIdentity = GtfsServiceIdentity(source, "T1", date, listOf(GtfsStopIdentity("A", 1), GtfsStopIdentity("B", 2)))
    )

    private fun update(sourceId: String, seconds: Long, stop: String = "A", sequence: Int = 1): RealtimeStopUpdate {
        val source = SourceRef(SourceId(sourceId), fetchedAt = now, observedAt = now,
            expiresAt = now.plusSeconds(90), confidence = Confidence.OPEN_DATA, traceId = "test")
        return RealtimeStopUpdate(TrainRunId("$sourceId:T1"), "T1", stop, sequence,
            officialDelay = SourcedValue(Duration.ofSeconds(seconds), source), observedAt = now, source = source, serviceDate = date)
    }

    private fun provider(id: String, updates: List<RealtimeStopUpdate>, fail: Boolean = false) = object : TripUpdateProvider {
        override val descriptor = ProviderDescriptor(SourceId(id), id, setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            CoverageDescriptor(description = "test"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(90)), 1)
        override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
            calls++
            return if (fail) ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "test"))
            else ProviderResult.Success(updates, ProviderResponseMeta(SourceId(id), now))
        }
    }

    private fun feed(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf("stops.txt" to "stop_id,stop_name\nA,A\nB,B\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,12:00:00,12:00:00,A,1\nT1,12:30:00,12:30:00,B,2\n").forEach { (file, contents) ->
                zip.putNextEntry(ZipEntry(file)); zip.write(contents.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
