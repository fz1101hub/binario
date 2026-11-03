package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.StationBoardRequest
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwissOpenTransportStationBoardAdapterTest {
    // The departures fixture was captured at 20:09:27+01:00; its rows leave Lugano from 21:25+01:00.
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T20:10:00Z"))
    private val departures = sourceFixture("opendata-ch-stationboard-lugano-departures.json")

    private fun adapter(fetcher: PublicSourceFetcher) = SwissOpenTransportStationBoardAdapter(fetcher, clock, 40, 0)

    private fun request(
        id: String = "curated:NC000225",
        name: String = "Lugano",
        trenitaliaId: String? = "850005300",
        kind: BoardKind = BoardKind.DEPARTURES,
        coordinates: GeoPoint? = GeoPoint(46.005499, 8.946841)
    ) = StationBoardRequest(
        stationId = StationId(id), stationName = name, from = clock.now.minus(Duration.ofMinutes(10)),
        to = clock.now.plus(Duration.ofHours(6)), kind = kind, trenitaliaLocationId = trenitaliaId, coordinates = coordinates
    )

    @Test fun `maps the real Lugano board to journey numbers, line labels, platforms and trusted prognoses`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { departures }
        val result = adapter(fetcher).getStationBoard(request()) as ProviderResult.Success
        val url = fetcher.urls.single()
        assertTrue(url, url.startsWith("https://transport.opendata.ch/v1/stationboard?id=8505300&limit=40"))
        assertTrue(url.contains("transportations%5B%5D=train"))
        assertTrue(!url.contains("type=arrival") && !url.contains("datetime="))

        val entries = result.value.entries
        assertEquals(listOf("25138", "29", "28"), entries.map { it.publicNumber })
        assertEquals(listOf("S 10", "EC 29", "EC 28"), entries.map { it.lineLabel })
        assertEquals(listOf("Biasca", "Como S. Giovanni", "Zürich HB"), entries.map { it.destinationOrOriginLabel })
        assertEquals(listOf("3", "2", "3"), entries.map { it.platform?.value })
        assertTrue(entries.all { it.operatorId?.value == "sbb" })
        assertEquals(StationId("curated:NC000225"), result.value.stationId)

        val delayed = entries.last()
        assertEquals(ZonedDateTime.parse("2026-10-25T21:30+01:00").toInstant(), delayed.scheduledTime.toInstant())
        assertEquals(ZonedDateTime.parse("2026-10-25T21:31+01:00").toInstant(), delayed.expectedTime?.value?.toInstant())
        assertEquals(RunStatus.DELAYED, delayed.status?.value)
        assertEquals(RunStatus.ON_TIME, entries.first().status?.value)
        // Every row carries the bogus arrival prognosis 20:09:27 of the origin row: it never becomes an expected time.
        assertTrue(entries.none { it.expectedTime?.value?.toLocalTime()?.toString()?.startsWith("20:09") == true })
    }

    @Test fun `a prognosis on the wrong side or outside the window is dropped without inventing a delay`() = runBlocking {
        val root = JSONObject(departures)
        val row = root.getJSONArray("stationboard").getJSONObject(0)
        val stop = row.getJSONObject("stop")
        stop.put("delay", JSONObject.NULL)
        stop.getJSONObject("prognosis").put("departure", "2026-10-25T20:09:27+0100")
        val second = root.getJSONArray("stationboard").getJSONObject(1).getJSONObject("stop")
        second.put("delay", JSONObject.NULL)
        second.getJSONObject("prognosis").put("departure", "2026-10-26T09:30:00+0100")
        val result = adapter(RecordingPublicSourceFetcher { root.toString() }).getStationBoard(request()) as ProviderResult.Success
        val (early, farLate) = result.value.entries.take(2)
        assertNull(early.expectedTime)
        assertNull(early.status)
        assertNull(farLate.expectedTime)
        assertNull(farLate.status)
    }

    @Test fun `the real type=arrival answer holds only departure rows and yields no invented arrivals`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { sourceFixture("opendata-ch-stationboard-lugano-arrival-request.json") }
        val result = adapter(fetcher).getStationBoard(request(kind = BoardKind.ARRIVALS)) as ProviderResult.Success
        assertTrue(fetcher.urls.single().contains("&type=arrival"))
        assertTrue(result.value.entries.isEmpty())
    }

    @Test fun `empty boards, malformed bodies and another station are typed results`() = runBlocking {
        val empty = """{"station":{"id":"8505300","name":"Lugano"},"stationboard":[]}"""
        assertTrue((adapter(RecordingPublicSourceFetcher { empty }).getStationBoard(request()) as ProviderResult.Success).value.entries.isEmpty())
        for (body in listOf("<html>maintenance</html>", departures.take(900), """{"station":{"id":"8503000"},"stationboard":[]}""", """{"stationboard":[]}""")) {
            val result = adapter(RecordingPublicSourceFetcher { body }).getStationBoard(request())
            assertEquals(body.take(40), ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
        }
        val down = adapter(RecordingPublicSourceFetcher { throw IOException("offline") }).getStationBoard(request())
        assertEquals(ProviderErrorKind.NETWORK, (down as ProviderResult.Failure).error.kind)
    }

    @Test fun `Italian stations are not supported and cause no request`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { error("nessuna richiesta attesa") }
        val result = adapter(fetcher).getStationBoard(request("curated:S01700", "Milano Centrale", "830001700", coordinates = GeoPoint(45.486, 9.204)))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (result as ProviderResult.Failure).error.kind)
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test fun `GTFS stop ids and Swiss catalogue ids resolve to the UIC without a lookup`() = runBlocking {
        assertEquals("8505213", SwissStationIds.fromRequest(request("gtfs:ch-otd-gtfs:8505213:0:1", "Bellinzona", null)))
        assertEquals("8505300", SwissStationIds.fromRequest(request("gtfs:ch-otd-gtfs:8505300", "Lugano", null)))
        assertEquals("8507100", SwissStationIds.fromRequest(request("curated:S01250", "Thun", "850007100")))
        // Trenitalia's 85 009xxxx entries are city aggregates (Basilea), not stations.
        assertNull(SwissStationIds.fromRequest(request("curated:NC000035", "Basilea", "850090001")))
        assertNull(SwissStationIds.fromRequest(request("gtfs:ch-otd-gtfs:8301003", "Domodossola (I)", null)))
    }

    @Test fun `a near-border station is resolved once by name, accepting only a nearby Swiss train station`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { url ->
            if (url.contains("/locations?")) sourceFixture("opendata-ch-locations-chiasso.json")
            else departures.replace("\"8505300\"", "\"8505307\"")
        }
        val adapter = adapter(fetcher)
        val chiasso = request("curated:S01301", "Chiasso", "830001301", coordinates = GeoPoint(45.830736, 9.03421))
        assertTrue(adapter.getStationBoard(chiasso) is ProviderResult.Success)
        clock.advance(Duration.ofMinutes(2))
        assertTrue(adapter.getStationBoard(chiasso) is ProviderResult.Success)
        assertEquals(1, fetcher.urls.count { it.contains("/locations?query=Chiasso&type=station") })
        assertTrue(fetcher.urls.filter { it.contains("/stationboard?") }.all { it.contains("id=8505307") })

        // Como San Giovanni is in the border region but 4 km from the Swiss train station the lookup returns.
        val como = adapter.getStationBoard(request("curated:S01307", "Como S. Giovanni", "830001307", coordinates = GeoPoint(45.808946, 9.072331)))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (como as ProviderResult.Failure).error.kind)
    }

    @Test fun `location results that are buses or not Swiss are refused`() {
        val adapter = adapter(RecordingPublicSourceFetcher { "" })
        val candidates = adapter.parseLocations(sourceFixture("opendata-ch-locations-chiasso.json"))
        assertEquals("train", candidates.first().icon)
        assertEquals(null, adapter.selectLocation(candidates.filter { it.icon != "train" }, "Chiasso, Stazione", GeoPoint(45.832433, 9.031734)))
        val italian = candidates.map { it.copy(id = "8301301") }
        assertEquals(null, adapter.selectLocation(italian, "Chiasso", GeoPoint(45.830736, 9.03421)))
        assertEquals("8505307", adapter.selectLocation(candidates, "Chiasso", null))
    }

    @Test fun `boards are cached for their TTL and a failed refresh serves the stale board as partial`() = runBlocking {
        var fail = false
        val fetcher = RecordingPublicSourceFetcher { if (fail) throw IOException("offline") else departures }
        val adapter = adapter(fetcher)
        adapter.getStationBoard(request())
        adapter.getStationBoard(request())
        assertEquals(1, fetcher.urls.size)
        clock.advance(Duration.ofMinutes(2))
        fail = true
        val stale = adapter.getStationBoard(request()) as ProviderResult.Partial
        assertEquals(setOf(ProviderWarning.STALE_SOURCE), stale.warnings)
        // The stale board keeps its timestamps: its observations have already expired for the UI and reconciler.
        assertTrue(stale.value.entries.all { it.expectedTime?.source?.expiresAt?.isBefore(clock.now) != false })
    }
}
