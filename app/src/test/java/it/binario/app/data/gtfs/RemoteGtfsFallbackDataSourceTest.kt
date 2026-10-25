package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.sources.InternationalSourceCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteGtfsFallbackDataSourceTest {
    private val now = Instant.parse("2026-09-10T07:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val source = requireNotNull(InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs")))
    private val origin = Station("curated:bru", "Bruxelles-Midi", "Bruxelles")
    private val destination = Station("curated:mil", "Milano Centrale", "Milano")
    private val route = SavedRoute("es", "Bruxelles–Milano", origin, destination)

    @Test
    fun `realtime is applied only after exact same-feed trip and stop reconciliation`() = runBlocking {
        val adapter = adapter(realtimeProvider("trip-1"))

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        val service = snapshot.services.single()
        assertEquals("09:10", service.expectedDeparture)
        assertEquals(10, service.delayMinutes)
        assertEquals(10, service.officialDelayMinutes)
        assertTrue(service.operationalStatusVerified)
        assertTrue("european-sleeper-gtfs-rt" in service.operationalSourceIds)
        assertTrue(snapshot.providerName.contains("Realtime test"))
        assertTrue(snapshot.coverageFallback)
    }

    @Test
    fun `mismatched realtime trip cannot modify fallback service`() = runBlocking {
        val adapter = adapter(realtimeProvider("different-trip"))

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        val service = snapshot.services.single()
        assertEquals("09:00", service.expectedDeparture)
        assertEquals(0, service.delayMinutes)
        assertFalse(service.operationalStatusVerified)
        assertTrue(service.operationalSourceIds.isEmpty())
        assertFalse(snapshot.providerName.contains("Realtime test"))
    }

    @Test
    fun `a trip shared across two service days remains static without a run date`() = runBlocking {
        val nightFeed = gtfs(stopTimesOverride =
            "trip-1,25:10:00,25:10:00,bru,1\ntrip-1,35:00:00,35:00:00,mil,2\n")
        val result = adapter(realtimeProvider("trip-1"), nightFeed).loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(0, 30))
        )

        assertTrue(result is DataResult.Success)
        val snapshot = (result as DataResult.Success).snapshot
        assertEquals(2, snapshot.services.size)
        assertEquals(2, snapshot.services.map { it.id }.toSet().size)
        assertTrue(snapshot.services.all { !it.operationalStatusVerified && it.operationalSourceIds.isEmpty() })
        assertFalse(snapshot.providerName.contains("Realtime test"))
    }

    @Test
    fun `il mirror community non popola tratte di altri operatori`() = runBlocking {
        val adapter = adapter(realtimeProvider("trip-1"), gtfs(foreignOperatorTrip = true))
        val query = JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
        val domestic = SavedRoute("rm-mi", "Roma–Milano", Station("curated:rom", "Roma Termini", "Roma"), destination)

        val foreign = adapter.loadRoute(domestic, Direction.OUTBOUND, query)
        assertTrue(foreign.toString(), foreign is DataResult.Failure)
        assertEquals(RouteFailureReason.RouteNotCovered, (foreign as DataResult.Failure).reason)

        val own = adapter.loadRoute(route, Direction.OUTBOUND, query)
        val services = (own as DataResult.Success).snapshot.services
        assertEquals(listOf("ES123"), services.map { it.number })
        assertEquals(listOf("European Sleeper"), services.map { it.operatorDisplayName })
    }

    @Test
    fun `un orario realtime malformato degrada solo quel singolo update allo snapshot statico`() = runBlocking {
        val adapter = adapter(
            realtimeProvider(realtimeUpdate("trip-1"), realtimeUpdate("trip-1", "col", 2)),
            gtfs(includeMalformedStop = true)
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val service = (result as DataResult.Success).snapshot.services.single()
        assertEquals("09:10", service.expectedDeparture)
        val malformedStop = service.stops.single { it.station == "Köln Hbf" }
        assertEquals("00:00", malformedStop.expected)
        assertEquals(0, malformedStop.delayMinutes)
    }

    @Test
    fun `la cancellazione realtime non viene degradata a snapshot statico`() {
        val adapter = adapter(object : TripUpdateProvider {
            override val descriptor = realtimeDescriptor()

            override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> =
                throw CancellationException("cancelled")
        })

        assertThrows(CancellationException::class.java) {
            runBlocking {
                adapter.loadRoute(
                    route,
                    Direction.OUTBOUND,
                    JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
                )
            }
        }
    }

    @Test
    fun `feed download runs off the calling thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        var downloadThread: Thread? = null
        val adapter = RemoteGtfsFallbackDataSource(
            source = source,
            parserSourceId = SourceId("european-sleeper"),
            operatorScope = "European Sleeper",
            downloader = GtfsArtifactSource { _, _ ->
                downloadThread = Thread.currentThread()
                GtfsDownloadSession(ByteArrayInputStream(gtfs()))
            },
            clock = clock,
            realtimeZone = ZoneId.of("Europe/Brussels")
        )

        val result = adapter.loadRoute(
            route,
            Direction.OUTBOUND,
            JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))
        )

        assertTrue(result is DataResult.Success)
        val recorded = requireNotNull(downloadThread) { "download non eseguito" }
        assertNotSame(callerThread, recorded)
        assertTrue(recorded.name, recorded.name.startsWith("DefaultDispatcher-worker"))
    }

    @Test
    fun `concurrent requests share one download and one bounded adapter parse during TTL`() = runTest {
        val downloads = AtomicInteger()
        val parses = AtomicInteger()
        val scheduler = testScheduler
        val ioDispatcher = StandardTestDispatcher(scheduler, "gtfs-test-io")
        val parsingDispatcher = StandardTestDispatcher(scheduler, "gtfs-test-cpu")
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val adapter = RemoteGtfsFallbackDataSource(
            source = source,
            parserSourceId = SourceId("european-sleeper"),
            operatorScope = "European Sleeper",
            downloader = GtfsArtifactSource { _, _ ->
                downloads.incrementAndGet()
                downloadStarted.complete(Unit)
                releaseDownload.await()
                GtfsDownloadSession(ByteArrayInputStream(gtfs()))
            },
            clock = clock,
            ioDispatcher = ioDispatcher,
            parsingDispatcher = parsingDispatcher,
            onParsing = parses::incrementAndGet
        )
        val query = JourneyQuery(LocalDate.of(2026, 9, 10), LocalTime.of(8, 0))

        val results = coroutineScope {
            val first = async { adapter.loadRoute(route, Direction.OUTBOUND, query) }
            scheduler.runCurrent()
            assertTrue(downloadStarted.isCompleted)
            assertEquals(1, downloads.get())
            val followers = List(5) { async { adapter.loadRoute(route, Direction.OUTBOUND, query) } }
            scheduler.runCurrent()
            releaseDownload.complete(Unit)
            scheduler.advanceUntilIdle()
            listOf(first) + followers
        }.awaitAll()

        assertTrue(results.all { it is DataResult.Success })
        assertEquals(1, downloads.get())
        assertEquals(2, parses.get()) // presenza fermate + indice compatto, una sola volta ciascuno.

        val cached = adapter.loadRoute(route, Direction.OUTBOUND, query)
        assertTrue(cached is DataResult.Success)
        assertEquals(1, downloads.get())
        assertEquals(2, parses.get())
    }

    private fun adapter(provider: TripUpdateProvider, feed: ByteArray = gtfs()) = RemoteGtfsFallbackDataSource(
        source = source,
        parserSourceId = SourceId("european-sleeper"),
        operatorScope = "European Sleeper",
        downloader = GtfsArtifactSource { _, _ -> GtfsDownloadSession(ByteArrayInputStream(feed)) },
        clock = clock,
        realtimeProvider = provider,
        realtimeZone = ZoneId.of("Europe/Brussels")
    )

    private fun realtimeProvider(vararg tripIds: String): TripUpdateProvider =
        realtimeProvider(*tripIds.map(::realtimeUpdate).toTypedArray())

    private fun realtimeProvider(vararg updates: RealtimeStopUpdate): TripUpdateProvider = object : TripUpdateProvider {
        override val descriptor = realtimeDescriptor()

        override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
            return ProviderResult.Success(
                updates.toList(),
                ProviderResponseMeta(descriptor.id, now, observedAt = now, expiresAt = now.plusSeconds(60))
            )
        }
    }

    private fun realtimeDescriptor() = ProviderDescriptor(
        id = SourceId("european-sleeper-gtfs-rt"),
        displayName = "Realtime test",
        capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(description = "test"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofMinutes(1)),
        schemaVersion = 1
    )

    private fun realtimeUpdate(tripId: String, stopId: String = "bru", stopSequence: Int = 1): RealtimeStopUpdate {
        val ref = SourceRef(
            sourceId = SourceId("european-sleeper-gtfs-rt"),
            nativeId = tripId,
            observedAt = now,
            fetchedAt = now,
            confidence = Confidence.DERIVED,
            traceId = "test:$tripId"
        )
        return RealtimeStopUpdate(
            runId = TrainRunId("es:$tripId"),
            sourceTripId = tripId,
            sourceStopId = stopId,
            stopSequence = stopSequence,
            predictedAt = SourcedValue(Instant.parse("2026-09-10T07:10:00Z").atZone(ZoneId.of("Europe/Brussels")), ref),
            officialDelay = SourcedValue(Duration.ofMinutes(10), ref),
            observedAt = now,
            source = ref
        )
    }

    private fun gtfs(
        includeMalformedStop: Boolean = false,
        stopTimesOverride: String? = null,
        foreignOperatorTrip: Boolean = false
    ): ByteArray {
        val stop = if (includeMalformedStop) "col,Köln Hbf\n" else ""
        val stopTimes = if (includeMalformedStop) {
            "trip-1,09:00:00,09:00:00,bru,1\ntrip-1,12:00:00,nope,col,2\ntrip-1,19:00:00,19:00:00,mil,3\n"
        } else {
            "trip-1,09:00:00,09:00:00,bru,1\ntrip-1,19:00:00,19:00:00,mil,2\n"
        }
        // A tampered or over-broad mirror: another agency's train serving the same and a domestic route.
        val foreignAgency = if (foreignOperatorTrip) "x,Altro Vettore\n" else ""
        val foreignStop = if (foreignOperatorTrip) "rom,Roma Termini\n" else ""
        val foreignRoute = if (foreignOperatorTrip) "r2,x,IC,Bruxelles Roma Milano,2\n" else ""
        val foreignTrip = if (foreignOperatorTrip) "r2,daily,trip-2,IC500\n" else ""
        val foreignStopTimes = if (foreignOperatorTrip) {
            "trip-2,10:00:00,10:00:00,bru,1\ntrip-2,16:00:00,16:00:00,rom,2\ntrip-2,20:00:00,20:00:00,mil,3\n"
        } else {
            ""
        }
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name\nes,European Sleeper\n$foreignAgency",
            "stops.txt" to "stop_id,stop_name\nbru,Bruxelles-Midi\n${stop}${foreignStop}mil,Milano Centrale\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nr1,es,ES,Bruxelles Milano,2\n$foreignRoute",
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name\nr1,daily,trip-1,ES123\n$foreignTrip",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n${stopTimesOverride ?: stopTimes}$foreignStopTimes",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\ndaily,1,1,1,1,1,1,1,20260901,20261231\n",
            "calendar_dates.txt" to "service_id,date,exception_type\n"
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
