package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.Confidence
import it.binario.app.data.provider.ProviderResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EuropeanSleeperGtfsRealtimeAdapterTest {
    @Test
    fun `prima del lancio Milano non chiama la rete`() = runBlocking {
        var calls = 0
        val adapter = EuropeanSleeperGtfsRealtimeAdapter(
            fetcher = EuropeanSleeperRealtimeFetcher { calls += 1; byteArrayOf() },
            clock = Clock.fixed(Instant.parse("2026-09-04T08:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.getTripUpdates()

        assertTrue(result is ProviderResult.Success)
        assertEquals(0, calls)
        assertTrue((result as ProviderResult.Success).value.isEmpty())
    }

    @Test
    fun `dopo il lancio decodifica trip update come dato derivato`() = runBlocking {
        val observed = Instant.parse("2026-09-10T08:00:00Z")
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("2.0")
                    .setTimestamp(observed.epochSecond)
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("es-1")
                    .setTripUpdate(
                        GtfsRealtime.TripUpdate.newBuilder()
                            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("ES-MILAN-1"))
                            .addStopTimeUpdate(
                                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                                    .setStopId("MILANO-PG")
                                    .setStopSequence(8)
                                    .setArrival(
                                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                                            .setDelay(420)
                                    )
                            )
                    )
            )
            .build()
        val adapter = EuropeanSleeperGtfsRealtimeAdapter(
            fetcher = EuropeanSleeperRealtimeFetcher { feed.toByteArray() },
            clock = Clock.fixed(observed, ZoneOffset.UTC)
        )

        val result = adapter.getTripUpdates() as ProviderResult.Success
        val update = result.value.single()

        assertEquals("ES-MILAN-1", update.sourceTripId)
        assertEquals(420L, update.officialDelay?.value?.seconds)
        assertEquals(Confidence.DERIVED, update.source.confidence)
    }
}
