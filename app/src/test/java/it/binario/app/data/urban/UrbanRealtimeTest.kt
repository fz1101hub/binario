package it.binario.app.data.urban

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPackKind
import it.binario.app.data.live.FakePublicSourceConnection
import it.binario.app.data.live.MutablePublicSourceClock
import java.io.IOException
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Trip Updates shaped like the Roma Servizi per la Mobilità feed read from CI (run 41561359450): FeedHeader 2.0,
 * trip_id of the static GTFS ("1#720-2"), route_id, start_date, every stop with stop_sequence, stop_id and both a time
 * and a delay, the first stop NO_DATA.
 */
class UrbanRealtimeTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val observed = Instant.parse("2026-11-03T08:00:00Z")

    @Test
    fun `decoder keeps the city pack routes, cancellations and skipped stops`() {
        val snapshot = UrbanGtfsRealtimeDecoder.decode(romeFeed().toByteArray(), UrbanRealtimeFeeds.ROMA, observed) { it in setOf("8", "MEA") }
        assertEquals(setOf("1#720-2", "2#100-1"), snapshot.trips.keys)
        val tram = snapshot.trips.getValue("1#720-2").single()
        assertEquals(LocalDate.of(2026, 11, 3), tram.startDate)
        assertEquals(listOf("70002", "70003"), tram.stops.map { it.stopId })
        assertTrue(tram.stops.last().skipped)
        assertTrue(snapshot.trips.getValue("2#100-1").single().cancelled)
        assertEquals(Instant.ofEpochSecond(1_793_692_650), snapshot.observedAt)
    }

    @Test
    fun `decoder drops added trips, trips without a date it can read and feeds without a header`() {
        val feed = romeFeed().toBuilder()
        feed.getEntityBuilder(0).tripUpdateBuilder.tripBuilder.setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED)
        feed.getEntityBuilder(1).tripUpdateBuilder.tripBuilder.setStartDate("3 novembre")
        val snapshot = UrbanGtfsRealtimeDecoder.decode(feed.build().toByteArray(), UrbanRealtimeFeeds.ROMA, observed)
        assertEquals(setOf("bus#9"), snapshot.trips.keys)
        try {
            UrbanGtfsRealtimeDecoder.decode(GtfsRealtime.FeedMessage.newBuilder().buildPartial().toByteArray(), UrbanRealtimeFeeds.ROMA, observed)
            fail("Un feed senza FeedHeader non è un feed GTFS-Realtime")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `expected time comes from the stop's own update only`() {
        val snapshot = UrbanGtfsRealtimeDecoder.decode(romeFeed().toByteArray(), UrbanRealtimeFeeds.ROMA, observed)
        val trip = snapshot.trip("1#720-2", LocalDate.of(2026, 11, 3))!!
        val scheduled = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone)
        assertEquals(scheduled.plusMinutes(2).toInstant(), trip.expectedAt("70002", scheduled, departure = true)!!.expected!!.toInstant())
        assertTrue(trip.expectedAt("70003", scheduled.plusMinutes(3), departure = false)!!.cancelled)
        // The NO_DATA stop and a stop the update does not list stay scheduled.
        assertNull(trip.expectedAt("70001", scheduled.minusMinutes(2), departure = true))
        assertNull(trip.expectedAt("79999", scheduled, departure = true))
        // A time hours away from the timetable is another run.
        assertNull(trip.expectedAt("70002", scheduled.plusHours(5), departure = true))
    }

    @Test
    fun `delay is used when the feed publishes no time`() {
        val trip = UrbanTripRealtime("t", "8", null, false, listOf(UrbanStopTimeRealtime("70002", 2, departureDelaySeconds = 180)), observed)
        val scheduled = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone)
        assertEquals(scheduled.plusMinutes(3), trip.expectedAt("70002", scheduled, departure = true)!!.expected)
    }

    @Test
    fun `an update without start_date only answers for the day it was observed`() {
        val update = UrbanTripRealtime("t", "8", null, false, listOf(UrbanStopTimeRealtime("70002", 2, departureDelaySeconds = 60)), observed)
        val snapshot = UrbanRealtimeSnapshot(UrbanRealtimeFeeds.ROMA, observed, observed, mapOf("t" to listOf(update)))
        assertEquals(update, snapshot.trip("t", LocalDate.of(2026, 11, 3)))
        assertNull(snapshot.trip("t", LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun `a snapshot older than five minutes is not live`() {
        val snapshot = UrbanRealtimeSnapshot(UrbanRealtimeFeeds.ROMA, observed, observed, emptyMap())
        assertTrue(snapshot.isFresh(observed.plus(Duration.ofMinutes(4))))
        assertFalse(snapshot.isFresh(observed.plus(Duration.ofMinutes(6))))
        assertFalse(snapshot.isFresh(observed.minus(Duration.ofMinutes(2))))
    }

    @Test
    fun `the feed is read at most every 30 seconds, kept stale for two minutes, then given up`() = runBlocking {
        val clock = MutablePublicSourceClock(observed)
        var calls = 0
        var failing = false
        val adapter = UrbanGtfsRealtimeAdapter(
            feed = UrbanRealtimeFeeds.ROMA,
            fetcher = UrbanRealtimeFetcher { calls += 1; if (failing) throw IOException("rete") else romeFeed().toByteArray() },
            clock = clock,
            packRoutes = { setOf("8") }
        )
        assertEquals(setOf("1#720-2"), adapter.snapshot()!!.trips.keys)
        clock.advance(Duration.ofSeconds(20))
        adapter.snapshot()
        assertEquals(1, calls)
        clock.advance(Duration.ofSeconds(15))
        failing = true
        // The refresh fails (one retry): the last good snapshot is kept.
        assertTrue(adapter.snapshot() != null)
        clock.advance(Duration.ofMinutes(3))
        assertNull(adapter.snapshot())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cache shorter than 30 seconds is refused`() {
        UrbanGtfsRealtimeAdapter(UrbanRealtimeFeeds.ROMA, fetcher = UrbanRealtimeFetcher { ByteArray(0) }, cacheTtl = Duration.ofSeconds(10))
    }

    @Test
    fun `a personal-only feed exists only in personal builds and only with its city pack`() {
        val rome = EmbeddedGtfsPack(SourceId("roma-mobilita-gtfs"), "gtfs-personal/roma-mobilita-gtfs.zip", kind = EmbeddedGtfsPackKind.URBAN, personalOnly = true)
        assertEquals(listOf(UrbanRealtimeFeeds.ROMA), UrbanRealtimeFeeds.forBuild(personalSources = true, packs = listOf(rome)))
        assertTrue(UrbanRealtimeFeeds.forBuild(personalSources = false, packs = listOf(rome)).isEmpty())
        assertTrue(UrbanRealtimeFeeds.forBuild(personalSources = true, packs = emptyList()).isEmpty())
        assertTrue(UrbanRealtimeFeeds.all.all { it.tripUpdatesUrl.startsWith("https://") })
    }

    @Test
    fun `the fetcher follows a redirect only to HTTPS on the same host`() = runBlocking {
        val payload = romeFeed().toByteArray()
        val moved = "https://romamobilita.it/wp-content/uploads/shared/rome_rtgtfs_trip_updates_feed.pb"
        val opened = mutableListOf<String>()
        val fetcher = HttpsBytesFetcher(openConnection = { url ->
            opened += url.toString()
            if (url.path.startsWith("/sites/")) FakePublicSourceConnection(url, 301, location = moved)
            else FakePublicSourceConnection(url, 200, body = payload)
        })
        assertEquals(payload.size, fetcher.fetch(UrbanRealtimeFeeds.ROMA.tripUpdatesUrl).size)
        assertEquals(listOf(UrbanRealtimeFeeds.ROMA.tripUpdatesUrl, moved), opened)

        val elsewhere = HttpsBytesFetcher(openConnection = { url -> FakePublicSourceConnection(url, 302, location = "https://example.org/feed.pb") })
        assertFailure { elsewhere.fetch(UrbanRealtimeFeeds.ROMA.tripUpdatesUrl) }
        val cleartext = HttpsBytesFetcher(openConnection = { url -> FakePublicSourceConnection(URL("http://romamobilita.it/x"), 200) })
        assertFailure { cleartext.fetch("http://romamobilita.it/feed.pb") }
        val large = HttpsBytesFetcher(maxBytes = 10, openConnection = { url -> FakePublicSourceConnection(url, 200, body = payload) })
        assertFailure { large.fetch(UrbanRealtimeFeeds.ROMA.tripUpdatesUrl) }
    }

    private suspend fun assertFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Richiesta non rifiutata")
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Tram 8 run with a NO_DATA first stop, a timed stop and a skipped stop; a cancelled metro A run; a bus run. */
        fun romeFeed(): GtfsRealtime.FeedMessage {
            val header = GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(1_793_692_650)
            fun event(time: Long, delay: Int) = GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(time).setDelay(delay)
            val departure = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneId.of("Europe/Rome")).toEpochSecond()
            val tram = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("1#720-2").setRouteId("8").setStartDate("20261103"))
                .setTimestamp(1_793_692_620)
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(1).setStopId("70001")
                    .setScheduleRelationship(GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA))
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(2).setStopId("70002")
                    .setArrival(event(departure + 120, 120)).setDeparture(event(departure + 120, 120)))
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(3).setStopId("70003")
                    .setScheduleRelationship(GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED))
            val metro = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("2#100-1").setRouteId("MEA").setStartDate("20261103")
                    .setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED))
            val bus = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("bus#9").setRouteId("64").setStartDate("20261103"))
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(4).setStopId("70500")
                    .setDeparture(event(departure, 0)))
            return GtfsRealtime.FeedMessage.newBuilder().setHeader(header)
                .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("1").setTripUpdate(tram))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("2").setTripUpdate(metro))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("3").setTripUpdate(bus))
                .build()
        }
    }
}
