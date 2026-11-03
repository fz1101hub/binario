package it.binario.app.data.urban

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.TransitGtfsPacks
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Entities cut from the real feeds by the discovery workflow (run 41583916012, 3 November 2026, 06:26 in Rome): two
 * tram 8 runs of Roma Servizi per la Mobilità (arrival-only stops, early runs, a NO_DATA terminus) and three Mestre
 * tram runs of ACTV (T1, T2). Joined to the personal-build packs; skipped when a refreshed pack no longer has the run.
 */
class UrbanRealtimeCiSampleTest {
    private val zone = ZoneId.of("Europe/Rome")

    private fun sample(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/sources/gtfs-rt/$name")) { "Fixture assente: $name" }.readBytes()

    private fun pack(id: String): Pair<EmbeddedGtfsPack, ByteArray>? {
        val pack = TransitGtfsPacks.all.first { it.sourceId.value == id }
        val file = File("src/personal/assets", pack.assetPath).takeIf(File::isFile) ?: return null
        return pack to file.readBytes()
    }

    private fun board(packId: String, feed: UrbanRealtimeFeed, payload: ByteArray, now: Instant, stop: String) = runBlocking {
        val (pack, bytes) = pack(packId) ?: return@runBlocking null
        val routes = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(bytes) }, packs = listOf(pack),
            parsingDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        val clock = MutablePublicSourceClock(now)
        val adapter = UrbanStopBoardAdapter(pack, routes, UrbanGtfsRealtimeAdapter(feed, UrbanRealtimeFetcher { payload }, clock,
            packRoutes = { UrbanPackRoutes.read(ByteArrayInputStream(bytes)) }), clock = clock, zone = zone)
        adapter.getStationBoard(StationBoardRequest(StationId("gtfs:$packId:$stop"), from = now, to = now.plusSeconds(3600), kind = BoardKind.DEPARTURES))
    }

    @Test
    fun `the Rome sample decodes as two tram 8 runs with arrival times`() {
        val snapshot = UrbanGtfsRealtimeDecoder.decode(sample("roma-tram8-trip-updates.pb"), UrbanRealtimeFeeds.ROMA, Instant.EPOCH) { it == "8" }
        assertEquals(Instant.ofEpochSecond(1_793_683_588), snapshot.observedAt)
        assertEquals(setOf("0#4655-4", "0#4652-5"), snapshot.trips.keys)
        val run = snapshot.trips.getValue("0#4655-4").single()
        // The terminus is NO_DATA: four stops remain, with arrival times only.
        assertEquals(listOf("80656", "80657", "80658", "80659"), run.stops.map { it.stopId })
        assertTrue(run.stops.all { it.arrival != null && it.departure == null })
        assertEquals(-202, run.stops[1].arrivalDelaySeconds)
    }

    @Test
    fun `a Rome tram stop board shows the early tram 8 from the real feed`() {
        val result = board("roma-mobilita-gtfs", UrbanRealtimeFeeds.ROMA, sample("roma-tram8-trip-updates.pb"), Instant.ofEpochSecond(1_793_683_620), "80657")
        assumeTrue("Pack roma-mobilita-gtfs non incluso o rigenerato senza la corsa del campione", result is ProviderResult.Success &&
            result.value.entries.any { it.nativeRunIds.values.contains("0#4655-4") })
        val entries = (result as ProviderResult.Success).value.entries
        val tram = entries.first { it.nativeRunIds.values.contains("0#4655-4") }
        assertEquals("06:33", tram.scheduledTime.withZoneSameInstant(zone).toLocalTime().toString())
        // Arrival-only stop: the arrival time stands for the departure (06:30:32, 202 s early).
        assertEquals(Instant.ofEpochSecond(1_793_683_832), tram.expectedTime!!.value.toInstant())
        assertEquals("8", tram.lineLabel)
        // The platform across the street (80632, same name) has the other direction's run.
        assertTrue(entries.any { it.nativeRunIds.values.contains("0#4652-5") && it.expectedTime?.value?.toInstant() == Instant.ofEpochSecond(1_793_683_886) })
        assertEquals("Roma Servizi per la Mobilità", UrbanRealtimeFeeds.observation(entries)!!.first)
    }

    @Test
    fun `a Mestre tram stop board shows T2 at Mestre station from the real ACTV feed`() {
        val payload = sample("actv-tram-trip-updates.pb")
        val snapshot = UrbanGtfsRealtimeDecoder.decode(payload, UrbanRealtimeFeeds.ACTV, Instant.EPOCH) { it in setOf("T1_UM", "T2_UM") }
        assertEquals(setOf("AUT_ACTV_795_31314", "AUT_ACTV_795_30666", "AUT_ACTV_795_31279"), snapshot.trips.keys)
        val result = board("venezia-actv-gtfs", UrbanRealtimeFeeds.ACTV, payload, Instant.ofEpochSecond(1_793_683_640), "6073")
        assumeTrue("Pack venezia-actv-gtfs non incluso o rigenerato senza la corsa del campione", result is ProviderResult.Success &&
            result.value.entries.any { it.nativeRunIds.values.contains("AUT_ACTV_795_31279") })
        val tram = (result as ProviderResult.Success).value.entries.first { it.nativeRunIds.values.contains("AUT_ACTV_795_31279") }
        assertEquals("06:32", tram.scheduledTime.withZoneSameInstant(zone).toLocalTime().toString())
        assertEquals(Instant.ofEpochSecond(1_793_683_967), tram.expectedTime!!.value.toInstant())
        assertEquals("T2", tram.lineLabel)
    }
}
