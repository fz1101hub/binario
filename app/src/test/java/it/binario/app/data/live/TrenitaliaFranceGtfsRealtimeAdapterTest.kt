package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.provider.KnownProviders
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderResult
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trenitalia France Trip Updates: parsing, cache, the refusal pause of the host and the gate on the bundled pack. */
class TrenitaliaFranceGtfsRealtimeAdapterTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-11-23T15:00:00Z"))

    @After fun clear() = SourcePauses.clearForTest()

    @Test fun `legge le fermate SCHEDULED con data di servizio e orario assoluto`() {
        val updates = TrenitaliaFranceGtfsRealtimeAdapter(clock = clock).parseTripUpdates(feed(), clock.instant())
        assertEquals(listOf("10009" to 0, "10007" to 62), updates.map { it.sourceStopId to it.stopSequence })
        assertTrue(updates.all { it.sourceTripId == "70458" && it.serviceDate == LocalDate.of(2026, 11, 23) })
        assertEquals("trenitalia-france-gtfs:70458", updates.first().runId.value)
        assertEquals(TrenitaliaFranceGtfsRealtimeAdapter.SOURCE_ID, updates.first().source.sourceId)
        assertEquals(Duration.ofMinutes(4), updates.first().officialDelay?.value)
        // 16:12 UTC is 17:12 in Paris and in Italy: the event time is absolute, whatever the feed's start_time says.
        assertEquals(Instant.parse("2026-11-23T16:12:00Z"), updates.first().predictedAt?.value?.toInstant())
        assertEquals(ZoneId.of("Europe/Paris"), updates.first().predictedAt?.value?.zone)
    }

    @Test fun `una sola richiesta per due minuti di cache`() = runBlocking {
        var calls = 0
        val adapter = TrenitaliaFranceGtfsRealtimeAdapter(fetcher = { calls++; feed().toByteArray() }, clock = clock)
        assertTrue(adapter.getTripUpdates() is ProviderResult.Success)
        clock.advance(Duration.ofSeconds(90))
        assertTrue((adapter.getTripUpdates() as ProviderResult.Success).meta.fromCache)
        assertEquals(1, calls)
        clock.advance(Duration.ofSeconds(31))
        adapter.getTripUpdates()
        assertEquals(2, calls)
    }

    /** At the first 403 (no Retry-After) the host is paused for both feeds it serves: no second request. */
    @Test fun `al primo 403 l'host resta in pausa anche dopo la memoria del guasto`() = runBlocking {
        var calls = 0
        val adapter = TrenitaliaFranceGtfsRealtimeAdapter(
            fetcher = { calls++; throw PublicSourceHttpException(403, "HTTP 403") },
            clock = clock,
            requests = TransportDataGouvRequests(clock)
        )
        assertTrue(adapter.getTripUpdates() is ProviderResult.Failure)
        for (step in 1..4) {
            clock.advance(Duration.ofSeconds(31))
            assertTrue(adapter.getTripUpdates() is ProviderResult.Failure)
        }
        assertEquals(1, calls)
        assertTrue(SourcePauses.active(clock.instant()).any { it.first == "transport.data.gouv.fr" })
    }

    @Test fun `la richiesta va solo al feed pubblicato con lo User-Agent di Binario`() = runBlocking {
        val asked = mutableListOf<Pair<URL, String?>>()
        val bytes = feed().toByteArray()
        val connection = { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = bytes.inputStream().also { asked += url to getRequestProperty("User-Agent") }
            }
        }
        val fetcher = HttpTrenitaliaFranceRealtimeFetcher(it.binario.app.data.urban.HttpsBytesFetcher(maxBytes = 1024 * 1024, openConnection = connection))
        assertTrue(TrenitaliaFranceGtfsRealtimeAdapter(fetcher = fetcher, clock = clock).getTripUpdates() is ProviderResult.Success)
        assertEquals(listOf(URL("https://proxy.transport.data.gouv.fr/resource/trenitalia-gtfs-rt")), asked.map { it.first })
        assertEquals(BINARIO_PUBLIC_SOURCE_USER_AGENT, asked.single().second)
        assertFalse(asked.single().second.orEmpty().contains("Mozilla"))
    }

    /** A candidate pack: the provider is on only in a build that bundles trenitalia-france-gtfs. */
    @Test fun `il provider e attivo solo con il pack Trenitalia France incluso`() {
        val bundled = TrenitaliaFranceGtfsRealtimeAdapter.STATIC_PACK in EmbeddedGtfsPacks.sourceIds
        val registry = KnownProviders.defaultRegistry()
        assertEquals(bundled, registry.isEnabled(TrenitaliaFranceGtfsRealtimeAdapter.SOURCE_ID))
        assertEquals(bundled, registry.activeFor(ProviderCapability.TRAIN_RUN_REALTIME).any { it.descriptor.id == TrenitaliaFranceGtfsRealtimeAdapter.SOURCE_ID })
    }

    private fun feed(): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(clock.instant().epochSecond - 20))
        .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("0").setTripUpdate(
            GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("70458").setStartTime("16:08:00").setStartDate("20261123").setRouteId("6656"))
                .addStopTimeUpdate(stop("10009", 0, Instant.parse("2026-11-23T16:12:00Z"), 240))
                .addStopTimeUpdate(stop("10006", 8, Instant.parse("2026-11-23T16:26:00Z"), 240)
                    .setScheduleRelationship(GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA))
                .addStopTimeUpdate(stop("10007", 62, Instant.parse("2026-11-23T18:22:00Z"), 240))))
        .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("1").setTripUpdate(
            GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("70469").setStartDate("20261123")
                    .setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED))
                .addStopTimeUpdate(stop("10009", 0, Instant.parse("2026-11-23T15:12:00Z"), 0))))
        .build()

    private fun stop(id: String, sequence: Int, at: Instant, delay: Int) = GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
        .setStopId(id).setStopSequence(sequence)
        .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setDelay(delay).setTime(at.epochSecond))
        .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setDelay(delay).setTime(at.epochSecond))
}
