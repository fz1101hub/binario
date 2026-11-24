package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/swiss-station-board-v1.json: the Swiss boards of transport.opendata.ch as Android and the
 * iPhone read them (station numbers, name lookups, rows, addresses asked), from the same file in both test suites.
 */
class SwissStationBoardContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val cases = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/swiss-station-board-v1.json").readText(Charsets.UTF_8))

    @After fun clear() = SourcePauses.clearForTest()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun body(item: JSONObject): String =
        if (item.has("file")) File(repositoryRoot, item.getString("file")).readText(Charsets.UTF_8) else item.getString("json")

    private fun request(station: JSONObject, window: JSONObject) = StationBoardRequest(
        stationId = StationId(station.getString("id")), stationName = station.getString("name"),
        from = Instant.ofEpochSecond(window.getLong("from")), to = Instant.ofEpochSecond(window.getLong("to")),
        kind = BoardKind.DEPARTURES, trenitaliaLocationId = station.textOrNull("trenitaliaLocationId"),
        coordinates = if (station.isNull("latitude")) null else GeoPoint(station.getDouble("latitude"), station.getDouble("longitude")),
        lateSince = window.longOrNull("lateSince")?.let(Instant::ofEpochSecond)
    )

    private fun adapter(now: Long, fetcher: PublicSourceFetcher) =
        SwissOpenTransportStationBoardAdapter(fetcher, MutablePublicSourceClock(Instant.ofEpochSecond(now)), 40, 0)

    @Test fun `the rows of a board and the address asked`() = runBlocking {
        for (case in cases.getJSONArray("boards").objects()) {
            val name = case.getString("name")
            val fetcher = RecordingPublicSourceFetcher { body(case) }
            val result = adapter(case.getLong("now"), fetcher).getStationBoard(request(case.getJSONObject("station"), case.getJSONObject("window")))
            assertEquals(name, listOf(case.getString("url")), fetcher.urls.toList())
            if (case.optBoolean("unreadable")) {
                assertEquals(name, ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
                continue
            }
            val entries = (result as ProviderResult.Success).value.entries
            val rows = case.getJSONArray("rows").objects()
            assertEquals(name, rows.size, entries.size)
            rows.zip(entries).forEach { (row, entry) ->
                assertEquals(name, row.getString("number"), entry.publicNumber)
                assertEquals(name, row.textOrNull("lineLabel"), entry.lineLabel)
                assertEquals(name, row.getString("destination"), entry.destinationOrOriginLabel)
                assertEquals(name, row.textOrNull("operator"), entry.operatorId?.value)
                assertEquals(name, row.getLong("scheduled"), entry.scheduledTime.toEpochSecond())
                assertEquals(name, row.longOrNull("expected"), entry.expectedTime?.value?.toEpochSecond())
                assertEquals(name, row.textOrNull("platform"), entry.platform?.value)
                assertEquals(name, row.textOrNull("scheduledPlatform"), entry.scheduledPlatform?.value)
                assertEquals(name, row.textOrNull("status"), entry.status?.value?.name)
            }
        }
    }

    @Test fun `the station numbers`() {
        val window = JSONObject().put("from", 0).put("to", 3600).put("lateSince", JSONObject.NULL)
        for (case in cases.getJSONArray("uic").objects()) {
            val station = JSONObject().put("id", case.getString("stationId")).put("name", "x")
                .put("trenitaliaLocationId", case.opt("trenitaliaLocationId") ?: JSONObject.NULL).put("latitude", JSONObject.NULL)
            val request = request(station, window)
            assertEquals(case.toString(), case.textOrNull("uic"), SwissStationIds.fromRequest(request))
            assertEquals(case.toString(), case.getBoolean("swissWithoutUic"), SwissStationIds.isSwissWithoutUic(request))
        }
    }

    @Test fun `the train station a name lookup may use`() {
        val adapter = adapter(0, RecordingPublicSourceFetcher { "" })
        for (case in cases.getJSONArray("locations").objects()) {
            val all = adapter.parseLocations(File(repositoryRoot, case.getString("file")).readText(Charsets.UTF_8))
            val candidates = when (case.getString("keep")) {
                "not-train" -> all.filter { it.icon != "train" }
                "italian-ids" -> all.map { it.copy(id = "8301301") }
                else -> all
            }
            val coordinates = if (case.isNull("latitude")) null else GeoPoint(case.getDouble("latitude"), case.getDouble("longitude"))
            assertEquals(case.toString(), case.textOrNull("selected"), adapter.selectLocation(candidates, case.getString("name"), coordinates))
        }
    }

    @Test fun `the name lookups of border and Swiss stations`() = runBlocking {
        for (case in cases.getJSONArray("lookups").objects()) {
            val name = case.getString("name")
            val answers = case.getJSONObject("answers")
            val fetcher = RecordingPublicSourceFetcher { url -> body(answers.getJSONObject(url)) }
            val result = adapter(case.getLong("now"), fetcher).getStationBoard(request(case.getJSONObject("station"), case.getJSONObject("window")))
            assertEquals(name, (0 until case.getJSONArray("asked").length()).map { case.getJSONArray("asked").getString(it) }, fetcher.urls.toList())
            if (case.isNull("rows")) {
                assertEquals(name, ProviderErrorKind.NOT_SUPPORTED, (result as ProviderResult.Failure).error.kind)
            } else {
                assertEquals(name, case.getInt("rows"), (result as ProviderResult.Success).value.entries.size)
            }
        }
    }

    @Test fun `the operators of the rows and the station names`() = runBlocking {
        val operators = cases.getJSONObject("operators")
        val result = adapter(operators.getLong("now"), RecordingPublicSourceFetcher { operators.getString("json") })
            .getStationBoard(request(operators.getJSONObject("station"), operators.getJSONObject("window")))
        val expected = operators.getJSONArray("expected")
        assertEquals((0 until expected.length()).map { if (expected.isNull(it)) null else expected.getString(it) },
            (result as ProviderResult.Success).value.entries.map { it.operatorId?.value })
        for (case in cases.getJSONArray("names").objects()) {
            assertEquals(case.getString("normalized"), SwissOpenTransportStationBoardAdapter.normalizeName(case.getString("name")))
        }
        assertTrue(cases.getJSONArray("names").length() > 0)
    }
}
