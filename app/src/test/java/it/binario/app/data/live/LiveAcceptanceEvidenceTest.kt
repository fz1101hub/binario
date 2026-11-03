package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.TrainRunId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The evidence rules of the live acceptance checks, on payloads shaped like the run of 2026-11-03 20:30 Europe/Rome
 * (run 41703080945), and always against the adapter itself: what a rule finds the adapter verifies, and when the
 * adapter verifies nothing the rule finds nothing, so the diagnostics of a failed live check point at the right side.
 */
class LiveAcceptanceEvidenceTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-03T19:30:51Z")
    private val clock = Clock.fixed(now, zone)
    private val query = JourneyQuery(LocalDate.of(2026, 11, 3), LocalTime.of(20, 30))
    private val route = SavedRoute("live-regional", "Appiano → Roma Ostiense",
        Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")),
        Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406"))

    // Synthetic rows in the shapes that run printed, not a copy of the remote page: departures with an empty cell and
    // the quoted "Nessuno" placeholder, arrivals with the bare label RITARDO.
    private fun row(number: String, time: String, text: String, aria: String?, info: String = "") =
        "<tr name=\"treno\"><td id=\"RVettore\"><img alt=\"Trenitalia\"/></td><td id=\"RCategoria\"><img alt=\"Categoria REG\"/></td>" +
            "<td id=\"RTreno\">$number</td><td id=\"RStazione\"><div>ROMA TIBURTINA</div></td><td id=\"ROrario\">$time</td>" +
            "<td id=\"RRitardo\"" + (aria?.let { " aria-label='$it'" } ?: "") + ">$text</td><td id=\"RBinario\"><div>2</div></td>" +
            "<td class=\"testoinfoaggiuntive\">$info</td></tr>"
    private fun board(vararg rows: String) = "<html><body><table>${rows.joinToString("")}</table></body></html>"
    private val placeholder = "\"Nessuno\""

    private fun departures(cell2383: Pair<String, String?> = "" to placeholder, info2383: String = "") = board(
        row("20434", "20:10", "", placeholder), row("20381", "20:19", "", placeholder),
        // Left at 20:27 with its published two minutes: gone before the 20:30 search.
        row("20389", "20:25", "2", null),
        row("20383", "20:34", cell2383.first, cell2383.second, info2383), row("20385", "20:49", "", placeholder))
    private val arrivals = board(
        row("20729", "20:13", "RITARDO", null), row("20539", "20:14", "RITARDO", null), row("21405", "20:14", "RITARDO", null),
        row("20381", "20:33", "", placeholder), row("20389", "20:39", "", placeholder),
        row("20383", "20:48", "", placeholder), row("20385", "21:03", "", placeholder))

    private suspend fun rfiRoute(departures: String): DataResult {
        val catalogue = "<select id='ElencoLocalita'><option value='3162'>Appiano</option><option value='2379'>Roma Ostiense</option></select>"
        val boards = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            when {
                url == RfiStationCatalog.URL -> catalogue
                url.endsWith("arrivals=True") -> arrivals
                else -> departures
            }
        }, clock = clock)
        return RfiLiveRouteDataSource(boards, clock).loadRoute(route, Direction.OUTBOUND, query)
    }

    private fun DataResult.verifiedNumbers(): List<String> = (this as? DataResult.Success)?.snapshot?.services.orEmpty()
        .filter { it.operationalStatusVerified && "rfi-station-board" in it.operationalSourceIds }.map { it.number }

    @Test fun `RFI boards with placeholders only reconcile nothing and the evidence agrees`() = runBlocking {
        val result = rfiRoute(departures())
        assertTrue("$result", result is DataResult.Failure)
        assertEquals(emptyList<String>(), LiveAcceptanceEvidence.rfiReconcilableRuns(
            LiveAcceptanceEvidence.rfiRows(departures()), LiveAcceptanceEvidence.rfiRows(arrivals), query.departureTime))
        assertEquals(mapOf("label:$placeholder" to 4, "minutes" to 1), LiveAcceptanceEvidence.rfiFormats(LiveAcceptanceEvidence.rfiRows(departures())))
        assertEquals(mapOf("text:RITARDO" to 3, "label:$placeholder" to 4), LiveAcceptanceEvidence.rfiFormats(LiveAcceptanceEvidence.rfiRows(arrivals)))
    }

    @Test fun `every RFI departure the evidence finds is verified by the route source`() = runBlocking {
        for (cell in listOf("4" to null, "" to "\"6\"", "RITARDO" to "7", "0" to null)) {
            val html = departures(cell)
            val evidence = LiveAcceptanceEvidence.rfiReconcilableRuns(LiveAcceptanceEvidence.rfiRows(html), LiveAcceptanceEvidence.rfiRows(arrivals), query.departureTime)
            assertEquals("$cell", listOf("20383"), evidence.map { it.substringBefore(' ') })
            assertEquals("$cell", listOf("20383"), rfiRoute(html).verifiedNumbers())
        }
        val cancelled = departures(info2383 = "Treno soppresso")
        assertEquals(1, LiveAcceptanceEvidence.rfiReconcilableRuns(LiveAcceptanceEvidence.rfiRows(cancelled), LiveAcceptanceEvidence.rfiRows(arrivals), query.departureTime).size)
        assertEquals(listOf("20383"), rfiRoute(cancelled).verifiedNumbers())
    }

    @Test fun `RFI delay cells compare only minutes the board publishes and flag numbers the check cannot read`() {
        val adapter = RfiStationBoardAdapter()
        val cells = mapOf(("" to placeholder) to null, ("RITARDO" to null) to null, ("" to null) to null, ("" to "\"6\"") to 6,
            ("RITARDO" to "7") to 7, ("12 minuti" to "12 minuti") to 12, ("+3" to null) to 3, ("5'" to null) to 5, ("Ritardo 5" to null) to 5)
        cells.forEach { (cell, minutes) ->
            val html = board(row("20383", "20:34", cell.first, cell.second))
            val published = LiveAcceptanceEvidence.rfiRows(html).single()
            assertEquals("$cell", minutes, published.publishedDelay)
            assertFalse("$cell", published.unreadNumber)
            val parsed = adapter.parseDepartures(html, "Appiano", "", route, Direction.OUTBOUND).single()
            if (minutes != null) assertEquals("$cell", minutes, parsed.officialDelayMinutes) else assertNull("$cell", parsed.officialDelayMinutes)
        }
        val unread = LiveAcceptanceEvidence.rfiRows(board(row("20383", "20:34", "ca. 5'", null))).single()
        assertNull(unread.publishedDelay)
        assertTrue(unread.unreadNumber)
    }

    private val runDate = LocalDate.of(2026, 11, 3).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(time: String) = LocalDate.of(2026, 11, 3).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun run(number: Int, appiano: String, delay: Int?, detectedSecondsAgo: Long?, nonPartito: Boolean = false) = JSONObject()
        .put("numeroTreno", number).put("idOrigine", "S08001").put("dataPartenzaTreno", runDate).put("categoria", "REG")
        .put("ritardo", delay ?: JSONObject.NULL).put("nonPartito", nonPartito)
        .put("oraUltimoRilevamento", detectedSecondsAgo?.let { now.minusSeconds(it).toEpochMilli() } ?: JSONObject.NULL)
        .put("fermate", JSONArray()
            .put(stop("S08001", "VITERBO PORTA FIORENTINA", at("19:05")))
            .put(stop("S08328", "APPIANO", at(appiano)))
            .put(stop("S08406", "ROMA OSTIENSE", at(appiano) + 14 * 60_000L)))
    private fun stop(id: String, name: String, millis: Long) = JSONObject().put("id", id).put("stazione", name).put("actualFermataType", 0)
        .put("partenza_teorica", millis).put("arrivo_teorico", millis)

    private fun viaggiaTreno(vararg runs: JSONObject): Triple<DataResult, Map<String, JSONObject>, List<String>> {
        val details = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val boards = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val board = JSONArray().apply {
            runs.forEach { run -> put(JSONObject().put("numeroTreno", run.getInt("numeroTreno")).put("codOrigine", "S08001")
                .put("dataPartenzaTreno", runDate).put("orarioPartenza", run.getJSONArray("fermate").getJSONObject(1).getLong("partenza_teorica"))
                .put("ritardo", run.opt("ritardo"))) }
        }.toString()
        val byNumber = runs.associateBy { it.getInt("numeroTreno").toString() }
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { path ->
            (if (path.startsWith("partenze/")) board else byNumber.getValue(path.split('/')[2]).toString()).also {
                if (path.startsWith("partenze/")) boards += it else details[path] = JSONObject(it)
            }
        }, clock)
        return Triple(runBlocking { adapter.loadRoute(route, Direction.OUTBOUND, query) }, details, boards.toList())
    }

    private fun verifiable(details: Map<String, JSONObject>) = LiveAcceptanceEvidence.viaggiaTrenoVerifiableRuns(details, "S08328", "S08406",
        query.requestedAt.atZone(zone).toInstant(), now.minusSeconds(270), now.plusSeconds(60))

    @Test fun `ViaggiaTreno runs without a recent detection are verified by nobody`() {
        // The next train last detected eight minutes ago, the one after not yet left its first station.
        val (result, details, boards) = viaggiaTreno(run(20383, "20:34", 3, 480), run(20385, "20:49", 0, null, nonPartito = true))
        val services = (result as DataResult.Success).snapshot.services
        assertEquals(listOf("20383", "20385"), services.map { it.number })
        assertTrue(services.none { it.operationalStatusVerified })
        assertEquals(emptyList<String>(), verifiable(details))
        assertEquals(2, LiveAcceptanceEvidence.viaggiaTrenoUpcomingRuns(boards, query.requestedAt.atZone(zone).toInstant()))
        assertEquals("20383@20:34 ritardo=3 detectedAgoSeconds=480 nonPartito=false provvedimento=null",
            LiveAcceptanceEvidence.viaggiaTrenoRunSummary(details, "S08328", now).first())
    }

    @Test fun `every ViaggiaTreno run the evidence finds is verified by the route source`() {
        val (result, details, _) = viaggiaTreno(run(20383, "20:34", 3, 60), run(20385, "20:49", 0, null, nonPartito = true))
        // Left on time at 20:25, freshly detected: not offered to a 20:30 search, so no evidence either.
        val gone = details + ("andamentoTreno/S08001/20389/$runDate" to run(20389, "20:25", 0, 30))
        assertEquals(listOf("20383"), verifiable(gone).map { it.substringBefore(' ') })
        val verified = (result as DataResult.Success).snapshot.services.filter { it.operationalStatusVerified }
        assertEquals(listOf("20383"), verified.map { it.number })
        assertEquals(3, verified.single().officialDelayMinutes)
    }

    private fun update(trip: String, stop: String) = RealtimeStopUpdate(TrainRunId("sta:$trip"), trip, stop, 4, observedAt = now,
        source = SourceRef(SourceId("alto-adige-sta-realtime"), trip, now, now, now.plusSeconds(300), Confidence.OPEN_DATA, traceId = "sta:$trip"))

    @Test fun `STA updates count against the rail pack only on its routes or stops`() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val pack = LiveAcceptanceEvidence.GtfsPackIdentity.read(File(assets, "gtfs/alto-adige-sta-gtfs.zip").readBytes())
        assertTrue(pack.tripIds.isNotEmpty())
        // The trip id names its route for every trip of the pack: the route rule below rests on it.
        assertTrue(pack.tripRoutes.all { (trip, route) -> LiveAcceptanceEvidence.staRouteOfTrip(trip) == route })
        // The bus trips and stops the failed run sampled are outside the rail pack: no evidence either way.
        val buses = listOf(update("12.TA.85-313-26a-2.7.R", "it:22021:1376:0:1443"), update("19.TA.82-181-26a-2.1.H", "it:22021:900:0:1011"))
        assertEquals(emptyList<RealtimeStopUpdate>(), LiveAcceptanceEvidence.onPackNetwork(buses, pack, LiveAcceptanceEvidence::staRouteOfTrip))
        // A rail update under a trip id the pack lacks is evidence of an identity mismatch, by its stop or by its route.
        val (trip, routeId) = pack.tripRoutes.entries.first()
        val railStop = pack.stopIds.first { it.startsWith("it:") }
        val renamed = trip.replaceFirst(".TA.", ".T0.")
        assertFalse(renamed in pack.tripIds)
        assertEquals(1, LiveAcceptanceEvidence.onPackNetwork(listOf(update(renamed, railStop)), pack) { null }.size)
        assertEquals(1, LiveAcceptanceEvidence.onPackNetwork(listOf(update(renamed, "it:22021:1376:0:1443")), pack,
            LiveAcceptanceEvidence::staRouteOfTrip).size)
        assertEquals(routeId, LiveAcceptanceEvidence.staRouteOfTrip(renamed))
    }
}
