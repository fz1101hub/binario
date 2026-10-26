package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.AlertRequest
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
    fun `decodifica avviso SNCF protobuf con stazione e severita`() = runBlocking {
        var calls = 0
        val adapter = SncfGtfsRealtimeAdapter(
            fetcher = SncfRealtimeFetcher { feed ->
                calls += 1
                if (feed == SncfRealtimeFeed.SERVICE_ALERTS) alertsFeed().toByteArray() else tripUpdatesFeed().toByteArray()
            },
            clock = Clock.fixed(Instant.parse("2026-08-15T08:00:00Z"), ZoneOffset.UTC)
        )

        val first = adapter.getAlerts(AlertRequest(stationIds = setOf(StationId("sncf-gtfs:FRPMO"))))
        val second = adapter.getAlerts(AlertRequest())

        assertTrue(first is ProviderResult.Success)
        val alerts = (first as ProviderResult.Success).value
        assertEquals(1, alerts.size)
        assertEquals("sncf:alert-1", alerts.single().id.value)
        assertEquals("Information circulation", alerts.single().title.value)
        assertEquals(1, alerts.single().affectedStations.size)
        assertTrue(second is ProviderResult.Success)
        assertEquals(1, calls)
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
        assertEquals(java.time.LocalDate.of(2026, 8, 15), updates.single().serviceDate)
        assertEquals(it.binario.app.data.core.RealtimeStopEventKind.DEPARTURE, updates.single().eventKind)
        assertEquals("trip-42", updates.single().sourceTripId)
        assertEquals("FRPMO", updates.single().sourceStopId)
        assertEquals(180L, updates.single().officialDelay?.value?.seconds)
        assertTrue(updates.single().predictedAt != null)
        assertEquals(null, updates.single().actualAt)
    }

    private fun alertsFeed(): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(1_786_147_200L))
        .addEntity(
            GtfsRealtime.FeedEntity.newBuilder().setId("alert-1").setAlert(
                GtfsRealtime.Alert.newBuilder()
                    .setEffect(GtfsRealtime.Alert.Effect.SIGNIFICANT_DELAYS)
                    .setHeaderText(translation("Information circulation", "fr"))
                    .addInformedEntity(GtfsRealtime.EntitySelector.newBuilder().setStopId("FRPMO").setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("trip-42")))
            )
        ).build()

    private fun tripUpdatesFeed(): GtfsRealtime.FeedMessage = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(1_786_147_200L))
        .addEntity(
            GtfsRealtime.FeedEntity.newBuilder().setId("tu-1").setTripUpdate(
                GtfsRealtime.TripUpdate.newBuilder()
                    .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("trip-42").setStartDate("20260815"))
                    .addStopTimeUpdate(
                        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                            .setStopId("FRPMO")
                            .setStopSequence(3)
                            .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setDelay(180).setTime(1_786_147_380L))
                    )
            )
        ).build()

    private fun translation(text: String, language: String): GtfsRealtime.TranslatedString = GtfsRealtime.TranslatedString.newBuilder()
        .addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder().setText(text).setLanguage(language))
        .build()
}
