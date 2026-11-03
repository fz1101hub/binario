package it.binario.app.data.urban

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPackKind
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.TransitArea
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.repository.StationBoardAggregateResult
import it.binario.app.data.repository.StationBoardAggregateService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrbanStopBoardAdapterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T07:58:00Z")
    private val pack = EmbeddedGtfsPack(
        SourceId("roma-mobilita-gtfs"), "gtfs-personal/roma-mobilita-gtfs.zip",
        kind = EmbeddedGtfsPackKind.URBAN, areas = listOf(TransitArea("Roma", 41.8986, 12.4964, 20.0)), personalOnly = true
    )
    private val zip = cityPack()
    private val routes = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(zip) }, packs = listOf(pack), parsingDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)

    private fun board(fetcher: UrbanRealtimeFetcher?, clock: MutablePublicSourceClock = MutablePublicSourceClock(now)): UrbanStopBoardAdapter =
        UrbanStopBoardAdapter(
            pack = pack,
            adapter = routes,
            realtime = fetcher?.let { UrbanGtfsRealtimeAdapter(UrbanRealtimeFeeds.ROMA, it, clock) },
            networkName = "Metro e tram di Roma — GTFS",
            clock = clock,
            zone = zone
        )

    private fun request(stop: String, kind: BoardKind = BoardKind.DEPARTURES) =
        StationBoardRequest(StationId("gtfs:roma-mobilita-gtfs:$stop"), from = now.minus(Duration.ofMinutes(10)), to = now.plus(Duration.ofHours(6)), kind = kind)

    @Test
    fun `departures of a stop and its platform across the street, with the tram's live time`() = runBlocking {
        val adapter = board(UrbanRealtimeFetcher { UrbanRealtimeTest.romeFeed().toByteArray() })
        val result = adapter.getStationBoard(request("70002"))
        assertTrue(result is ProviderResult.Success)
        val entries = (result as ProviderResult.Success<StationBoard>).value.entries
        assertEquals(listOf("1#720-2", "1#720-3"), entries.map { it.nativeRunIds.getValue(pack.sourceId) })
        val first = entries.first()
        assertEquals(ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone).toInstant(), first.scheduledTime.toInstant())
        assertEquals(ZonedDateTime.of(2026, 11, 3, 9, 2, 0, 0, zone).toInstant(), first.expectedTime!!.value.toInstant())
        assertEquals(UrbanRealtimeFeeds.ROMA.sourceId, first.expectedTime!!.source.sourceId)
        assertEquals(RunStatus.DELAYED, first.status!!.value)
        assertEquals("Piazza Mancini", first.destinationOrOriginLabel)
        assertEquals("8", first.lineLabel)
        // No update for the other run: scheduled only.
        assertNull(entries[1].expectedTime)
        assertEquals("Casaletto", entries[1].destinationOrOriginLabel)
        val observation = UrbanRealtimeFeeds.observation(entries)!!
        assertEquals("Roma Servizi per la Mobilità", observation.first)
    }

    @Test
    fun `a skipped stop is shown cancelled and arrivals name the origin`() = runBlocking {
        val adapter = board(UrbanRealtimeFetcher { UrbanRealtimeTest.romeFeed().toByteArray() })
        val arrivals = (adapter.getStationBoard(request("70003", BoardKind.ARRIVALS)) as ProviderResult.Success).value.entries
        val tram = arrivals.single { it.nativeRunIds[pack.sourceId] == "1#720-2" }
        assertEquals(RunStatus.CANCELLED, tram.status!!.value)
        assertNull(tram.expectedTime)
        assertEquals("Casaletto", tram.destinationOrOriginLabel)
    }

    @Test
    fun `without the feed the board stays on the timetable and says so`() = runBlocking {
        val adapter = board(UrbanRealtimeFetcher { throw IOException("rete") })
        val result = adapter.getStationBoard(request("70002"))
        assertTrue(result is ProviderResult.Partial)
        result as ProviderResult.Partial
        assertEquals(setOf(ProviderWarning.REALTIME_UNAVAILABLE), result.warnings)
        assertTrue(result.value.entries.all { it.expectedTime == null && it.status == null })
        assertNull(UrbanRealtimeFeeds.observation(result.value.entries))
    }

    @Test
    fun `a stale feed is not shown as live`() = runBlocking {
        val clock = MutablePublicSourceClock(now.plus(Duration.ofMinutes(9)))
        val adapter = board(UrbanRealtimeFetcher { UrbanRealtimeTest.romeFeed().toByteArray() }, clock)
        val result = adapter.getStationBoard(request("70002"))
        assertTrue(result is ProviderResult.Partial)
    }

    @Test
    fun `other stations are not this board's`() = runBlocking {
        val adapter = board(null)
        assertTrue(adapter.servesStation(StationId("gtfs:roma-mobilita-gtfs:70002")))
        val railway = adapter.getStationBoard(StationBoardRequest(StationId("S08409"), from = now, to = now.plusSeconds(3600), kind = BoardKind.DEPARTURES))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (railway as ProviderResult.Failure).error.kind)
        assertEquals(ProviderErrorKind.NOT_FOUND, (adapter.getStationBoard(request("99999")) as ProviderResult.Failure).error.kind)
        // Without a feed the timetable board is complete as it is.
        assertTrue(adapter.getStationBoard(request("70002")) is ProviderResult.Success)
    }

    @Test
    fun `the board service asks the city board for a city stop and the railway boards for a station`() = runBlocking {
        val railway = RecordingBoard()
        val service = StationBoardAggregateService(
            providers = listOf(railway),
            clock = java.time.Clock.fixed(now, zone),
            stopProviders = listOf(board(null))
        )
        val stop = Station("gtfs:roma-mobilita-gtfs:70002", "Belli", "Roma")
        val result = service.observe(stop, BoardKind.DEPARTURES, now).last()
        assertTrue(result is StationBoardAggregateResult.Success)
        assertEquals(listOf("Metro e tram di Roma — GTFS"), (result as StationBoardAggregateResult.Success).board.providerNames)
        assertEquals(0, railway.calls)
        service.observe(Station("S08409", "Roma Termini", "Roma"), BoardKind.DEPARTURES, now).last()
        assertEquals(1, railway.calls)
    }

    private class RecordingBoard : StationBoardProvider {
        var calls = 0
        override val descriptor = UrbanGtfsRealtimeAdapter.descriptor(UrbanRealtimeFeeds.ROMA).copy(id = SourceId("recording-board"), displayName = "Ferrovia")
        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
            calls += 1
            return ProviderResult.Failure(it.binario.app.data.provider.ProviderError(ProviderErrorKind.NOT_FOUND, "nessuna"))
        }
    }

    /** Tram 8 Casaletto – Piazza Mancini through "Belli" (two platforms 40 m apart), and a metro A run elsewhere. */
    private fun cityPack(): ByteArray {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nOP1,Atac,https://www.atac.roma.it,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon,parent_station\n" +
                "70001,CASALETTO,41.8650,12.4480,\n70002,BELLI,41.8890,12.4720,\n70012,BELLI,41.8893,12.4722,\n" +
                "70003,ARENULA,41.8940,12.4760,\n70004,PIAZZA MANCINI,41.9300,12.4650,\n70011,CASALETTO,41.8650,12.4480,\n" +
                "M1,ANAGNINA,41.8430,12.5860,\nM2,TERMINI,41.9010,12.5010,\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n8,OP1,8,,0\nMEA,OP1,MEA,,1\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n8,ALL,1#720-2,PIAZZA MANCINI\n8,ALL,1#720-3,CASALETTO\nMEA,ALL,2#100-1,BATTISTINI\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "1#720-2,08:58:00,08:58:00,70001,1\n1#720-2,09:00:00,09:00:00,70002,2\n1#720-2,09:03:00,09:03:00,70003,3\n1#720-2,09:20:00,09:20:00,70004,4\n" +
                "1#720-3,09:01:00,09:01:00,70004,1\n1#720-3,09:05:00,09:05:00,70012,2\n1#720-3,09:12:00,09:12:00,70011,3\n" +
                "2#100-1,09:10:00,09:10:00,M1,1\n2#100-1,09:30:00,09:30:00,M2,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nALL,1,1,1,1,1,1,1,20260101,20261231\n"
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            files.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray())
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
