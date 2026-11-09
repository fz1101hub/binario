package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SncfGtfsRealtimeAdapterTest {
    @Test
    fun `SNCF conserva data e timestamp corsa e ignora NO_DATA`() {
        val feed = tripUpdatesFeed().toBuilder()
        val trip = feed.getEntityBuilder(0).tripUpdateBuilder
        trip.tripBuilder.setStartDate("20260815")
        trip.setTimestamp(1786147000)
        val adapter = SncfGtfsRealtimeAdapter()
        val updates = adapter.parseTripUpdates(feed.build(), Instant.ofEpochSecond(1786147200))
        assertEquals(java.time.LocalDate.of(2026, 8, 15), updates.single().serviceDate)
        assertEquals(Instant.ofEpochSecond(1786147000), updates.single().observedAt)
        trip.getStopTimeUpdateBuilder(0).setScheduleRelationship(GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA)
        assertTrue(adapter.parseTripUpdates(feed.build(), Instant.EPOCH).isEmpty())
    }

    @Test
    fun `normalizza trip updates senza forzare un evento effettivo`() = runBlocking {
        val adapter = SncfGtfsRealtimeAdapter(
            fetcher = SncfRealtimeFetcher { tripUpdatesFeed().toByteArray() },
            clock = Clock.fixed(Instant.parse("2026-08-15T08:00:00Z"), ZoneOffset.UTC)
        )

        val result = adapter.getTripUpdates()

        assertTrue(result is ProviderResult.Success)
        val updates = (result as ProviderResult.Success).value
        assertEquals(1, updates.size)
        assertEquals("trip-42", updates.single().sourceTripId)
        assertEquals("FRPMO", updates.single().sourceStopId)
        assertEquals(180L, updates.single().officialDelay?.value?.seconds)
        assertTrue(updates.single().predictedAt != null)
        assertEquals(null, updates.single().actualAt)
    }

    private fun tripUpdatesFeed(): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(1_786_147_200L))
        .addEntity(
            GtfsRealtime.FeedEntity.newBuilder().setId("tu-1").setTripUpdate(
                GtfsRealtime.TripUpdate.newBuilder()
                    .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("trip-42"))
                    .addStopTimeUpdate(
                        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                            .setStopId("FRPMO")
                            .setStopSequence(3)
                            .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setDelay(180).setTime(1_786_147_380L))
                    )
            )
        ).build()
}
