package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.ProviderResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwissGtfsRealtimeAdapterTest {
    private val observed = Instant.parse("2026-09-04T08:00:00Z")
    private val clock = Clock.fixed(observed, ZoneOffset.UTC)

    @Test
    fun `decodifica trip updates con ritardo svizzero`() {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(
                GtfsRealtime.FeedHeader.newBuilder()
                    .setGtfsRealtimeVersion("1.0")
                    .setTimestamp(observed.epochSecond)
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("entity-1")
                    .setTripUpdate(
                        GtfsRealtime.TripUpdate.newBuilder()
                            .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("trip-1"))
                            .addStopTimeUpdate(
                                GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                                    .setStopId("ch:1:sloid:8503000")
                                    .setStopSequence(3)
                                    .setDeparture(
                                        GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                                            .setDelay(180)
                                    )
                            )
                    )
            )
            .build()

        val adapter = SwissGtfsRealtimeAdapter(fetcher = SwissRealtimeFetcher { feed.toByteArray() }, clock = clock)
        val updates = adapter.parseTripUpdates(feed, observed)

        assertEquals(1, updates.size)
        assertEquals("trip-1", updates.single().sourceTripId)
        assertEquals(180L, updates.single().officialDelay?.value?.seconds)
    }

    @Test
    fun `decodifica service alert e agency`() {
        val translated = GtfsRealtime.TranslatedString.newBuilder()
            .addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder().setText("Linea interrotta").setLanguage("it"))
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(observed.epochSecond))
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("alert-1")
                    .setAlert(
                        GtfsRealtime.Alert.newBuilder()
                            .setHeaderText(translated)
                            .setEffect(GtfsRealtime.Alert.Effect.DETOUR)
                            .addInformedEntity(
                                GtfsRealtime.EntitySelector.newBuilder()
                                    .setAgencyId("TILO")
                                    .setStopId("ch:1:sloid:8505300")
                            )
                    )
            )
            .build()

        val adapter = SwissGtfsRealtimeAdapter(fetcher = SwissRealtimeFetcher { feed.toByteArray() }, clock = clock)
        val alerts = adapter.parseAlerts(feed, observed)

        assertEquals(1, alerts.size)
        assertEquals("Linea interrotta", alerts.single().title.value)
        assertTrue(alerts.single().affectedOperators.any { it.value == "TILO" })
    }

    @Test
    fun `cache evita richieste piu frequenti del TTL`() = runBlocking {
        var calls = 0
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("1.0").setTimestamp(observed.epochSecond))
            .build()
        val adapter = SwissGtfsRealtimeAdapter(
            fetcher = SwissRealtimeFetcher { calls += 1; feed.toByteArray() },
            clock = clock
        )

        val first = adapter.getTripUpdates()
        val second = adapter.getTripUpdates()

        assertTrue(first is ProviderResult.Success)
        assertTrue(second is ProviderResult.Success)
        assertEquals(1, calls)
    }

    @Test
    fun `alert provider funziona senza filtri`() = runBlocking {
        val feed = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(observed.epochSecond))
            .build()
        val adapter = SwissGtfsRealtimeAdapter(fetcher = SwissRealtimeFetcher { feed.toByteArray() }, clock = clock)

        assertTrue(adapter.getAlerts(AlertRequest()) is ProviderResult.Success)
    }
}
