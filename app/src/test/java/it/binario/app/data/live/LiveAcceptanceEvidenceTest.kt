package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RealtimeRouteDataSource
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File
import java.io.IOException
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
        assertEquals(emptyList<LiveAcceptanceEvidence.RfiRun>(), LiveAcceptanceEvidence.rfiReconcilableRuns(
            LiveAcceptanceEvidence.rfiRows(departures()), LiveAcceptanceEvidence.rfiRows(arrivals), query.departureTime))
        assertEquals(mapOf("label:$placeholder" to 4, "minutes" to 1), LiveAcceptanceEvidence.rfiFormats(LiveAcceptanceEvidence.rfiRows(departures())))
        assertEquals(mapOf("text:RITARDO" to 3, "label:$placeholder" to 4), LiveAcceptanceEvidence.rfiFormats(LiveAcceptanceEvidence.rfiRows(arrivals)))
    }

    @Test fun `every RFI departure the evidence finds is verified by the route source`() = runBlocking {
        for (cell in listOf("4" to null, "" to "\"6\"", "RITARDO" to "7", "0" to null)) {
            val html = departures(cell)
            val evidence = LiveAcceptanceEvidence.rfiReconcilableRuns(LiveAcceptanceEvidence.rfiRows(html), LiveAcceptanceEvidence.rfiRows(arrivals), query.departureTime)
            assertEquals("$cell", listOf("20383"), evidence.map { it.number })
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

    // The multi-route checks of LiveRouteChecks, on the same synthetic shapes: what each branch does.

    /** A route source that returns what the real one does, with [change] applied to every service: an adapter gone wrong. */
    private class Altered(private val real: RealtimeRouteDataSource, private val change: (TrainService) -> TrainService) : RealtimeRouteDataSource by real {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
            when (val result = real.loadRoute(route, direction, query)) {
                is DataResult.Success -> result.copy(snapshot = result.snapshot.copy(services = result.snapshot.services.map(change)))
                else -> result
            }
    }
    private val unverified: (TrainService) -> TrainService = { it.copy(operationalStatusVerified = false, operationalSourceIds = emptySet()) }
    private val oneMinuteOff: (TrainService) -> TrainService = { if (it.operationalStatusVerified) it.copy(officialDelayMinutes = (it.officialDelayMinutes ?: 0) + 1) else it }

    /** What [block] throws, which has to be a [T]: any other outcome (a skip included) fails the test. */
    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, the check passed")
    }

    // RFI place ids: Appiano and Monza from the (synthetic) catalogue, the others from the adapter's own map.
    private val rfiCatalogue = "<select id='ElencoLocalita'><option value='3162'>Appiano</option><option value='1325'>Monza</option></select>"
    private class RfiPages(private val catalogue: String, private val pages: Map<Pair<String, Boolean>, String>) {
        val asked = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val fetcher = RfiMonitorFetcher { url ->
            if (url == RfiStationCatalog.URL) return@RfiMonitorFetcher catalogue
            val place = Regex("placeId=(\\d+)").find(url)!!.groupValues[1]
            asked += place
            pages[place to url.endsWith("arrivals=True")] ?: throw IOException("no page for $url")
        }
    }
    private fun rfiPages(appianoDepartures: String = departures(), ciampinoDepartures: String = board(), monzaDepartures: String = board()) =
        RfiPages(rfiCatalogue, mapOf(("3162" to false) to appianoDepartures, ("2379" to true) to arrivals,
            ("1106" to false) to ciampinoDepartures, ("2416" to true) to board(row("4400", "20:58", "", placeholder)),
            ("1325" to false) to monzaDepartures, ("1728" to true) to board(row("2534", "21:02", "", placeholder))))

    private fun rfiRegional(pages: RfiPages, log: MutableList<String> = mutableListOf(), change: ((TrainService) -> TrainService)? = null) = runBlocking {
        LiveRouteChecks.rfiRegional(pages.fetcher, LiveRouteChecks.regionalRoutes, query, clock, log::add) { provider ->
            RfiLiveRouteDataSource(provider, clock).let { real -> change?.let { Altered(real, it) } ?: real }
        }
    }

    @Test fun `RFI regional check is skipped only after every route publishes nothing to reconcile`() {
        val pages = rfiPages()
        val log = mutableListOf<String>()
        val skipped = thrown<AssumptionViolatedException> { rfiRegional(pages, log) }
        assertTrue(skipped.message!!, skipped.message!!.contains("label:$placeholder=4"))
        assertTrue("Every route sampled: ${pages.asked}", pages.asked.containsAll(listOf("3162", "2379", "1106", "2416", "1325", "1728")))
        assertTrue(log.single(), log.single().startsWith("LIVE_ACCEPTANCE skipped: RFI regional journey"))
    }

    @Test fun `RFI regional check passes on the first route whose boards publish a delay the source verifies`() {
        val passed = rfiRegional(rfiPages(ciampinoDepartures = board(row("4400", "20:40", "5", null))))
        assertEquals("live-ciampino", passed.route.id)
        assertEquals(listOf("4400+5"), passed.services.filter { it.operationalStatusVerified }.map { it.number + "+" + it.officialDelayMinutes })
    }

    @Test fun `RFI regional check fails when the boards publish a delay the source does not verify or changes`() {
        val pages = rfiPages(appianoDepartures = departures("4" to null), ciampinoDepartures = board(row("4400", "20:40", "5", null)))
        val missed = thrown<AssertionError> { rfiRegional(pages, change = unverified) }
        assertTrue(missed.message!!, missed.message!!.contains("[20383 20:34+4 → 20:48] but the route source verified none"))
        assertFalse("A deciding route ends the sampling", "1106" in pages.asked)
        val differing = thrown<AssertionError> { rfiRegional(rfiPages(appianoDepartures = departures("4" to null)), change = oneMinuteOff) }
        assertTrue(differing.message!!, differing.message!!.contains("delays differ"))
    }

    @Test fun `RFI regional check fails on numbers it cannot read and on a board it could not read`() {
        val unread = thrown<AssertionError> { rfiRegional(rfiPages(appianoDepartures = departures("ca. 5'" to null))) }
        assertTrue(unread.message!!, unread.message!!.contains("cannot read as minutes"))
        val pages = RfiPages(rfiCatalogue, mapOf(("3162" to false) to departures()))
        val missing = thrown<AssertionError> { rfiRegional(pages) }
        assertTrue(missing.message!!, missing.message!!.contains("NetworkUnavailable"))
    }

    // ViaggiaTreno: each origin's board and each run's detail by number.
    private fun vtRun(number: Int, from: Pair<String, String>, to: Pair<String, String>, departure: String, delay: Int?, detectedSecondsAgo: Long?,
        nonPartito: Boolean = false, reversed: Boolean = false): JSONObject {
        val first = stop(from.first, from.second, at(departure))
        val second = stop(to.first, to.second, at(departure) + 14 * 60_000L)
        return JSONObject().put("numeroTreno", number).put("idOrigine", "S08001").put("dataPartenzaTreno", runDate).put("categoria", "REG")
            .put("ritardo", delay ?: JSONObject.NULL).put("nonPartito", nonPartito)
            .put("oraUltimoRilevamento", detectedSecondsAgo?.let { now.minusSeconds(it).toEpochMilli() } ?: JSONObject.NULL)
            .put("fermate", JSONArray().put(stop("S08001", "ORIGINE", at("19:05"))).put(if (reversed) second else first).put(if (reversed) first else second))
    }
    private val appiano = "S08328" to "APPIANO"
    private val ostiense = "S08406" to "ROMA OSTIENSE"
    private val ciampino = "S08650" to "CIAMPINO"
    private val termini = "S08409" to "ROMA TERMINI"

    private fun vtFetcher(runsByOrigin: Map<String, List<JSONObject>>, details: (JSONObject) -> String = JSONObject::toString): ViaggiaTrenoRealtimeFetcher {
        val byNumber = runsByOrigin.values.flatten().associateBy { it.getInt("numeroTreno").toString() }
        return ViaggiaTrenoRealtimeFetcher { path ->
            if (path.startsWith("partenze/")) JSONArray().apply {
                runsByOrigin[path.split('/')[1]].orEmpty().forEach { run ->
                    val boarding = (0 until run.getJSONArray("fermate").length()).map(run.getJSONArray("fermate")::getJSONObject)
                        .first { it.getString("id") == path.split('/')[1] }
                    put(JSONObject().put("numeroTreno", run.getInt("numeroTreno")).put("codOrigine", "S08001").put("dataPartenzaTreno", runDate)
                        .put("orarioPartenza", boarding.getLong("partenza_teorica")).put("ritardo", run.opt("ritardo")))
                }
            }.toString() else details(byNumber.getValue(path.split('/')[2]))
        }
    }
    private fun viaggiaTrenoRegional(fetcher: ViaggiaTrenoRealtimeFetcher, log: MutableList<String> = mutableListOf(),
        change: ((TrainService) -> TrainService)? = null) = runBlocking {
        LiveRouteChecks.viaggiaTrenoRegional(fetcher, LiveRouteChecks.regionalRoutes, query, clock, log::add) { capturing ->
            ViaggiaTrenoRealtimeAdapter(capturing, clock).let { real -> change?.let { Altered(real, it) } ?: real }
        }
    }
    // As at 21:20 on 2026-11-03: the next run not yet left its first station, another one detected but going the other way.
    private val appianoEvening = listOf(vtRun(20388, appiano, ostiense, "20:53", null, null, nonPartito = true),
        vtRun(23274, appiano, ostiense, "20:41", -1, 98, reversed = true))

    @Test fun `ViaggiaTreno regional check is skipped only after every route has no run that left with a recent detection`() {
        val log = mutableListOf<String>()
        val skipped = thrown<AssumptionViolatedException> {
            viaggiaTrenoRegional(vtFetcher(mapOf("S08328" to appianoEvening, "S08650" to listOf(vtRun(4400, ciampino, termini, "20:40", 2, 480)))), log)
        }
        assertTrue(skipped.message!!, skipped.message!!.contains("20388@20:53 ritardo=null detectedAgoSeconds=null nonPartito=true"))
        assertTrue(skipped.message!!, skipped.message!!.contains("Monza → Milano Centrale: boardRuns=0"))
        assertTrue(log.single(), log.single().startsWith("LIVE_ACCEPTANCE skipped: ViaggiaTreno regional"))
    }

    @Test fun `ViaggiaTreno regional check passes on the first route with a run the source verifies`() {
        val passed = viaggiaTrenoRegional(vtFetcher(mapOf("S08328" to appianoEvening, "S08650" to listOf(vtRun(4400, ciampino, termini, "20:40", 2, 60)))))
        assertEquals("live-ciampino", passed.route.id)
        assertEquals(listOf("4400+2"), passed.services.filter { it.operationalStatusVerified }.map { it.number + "+" + it.officialDelayMinutes })
    }

    @Test fun `ViaggiaTreno regional check fails when a run that left with a recent detection is not verified or gets another delay`() {
        val recent = mapOf("S08328" to appianoEvening + vtRun(20383, appiano, ostiense, "20:34", 3, 60))
        val missed = thrown<AssertionError> { viaggiaTrenoRegional(vtFetcher(recent), change = unverified) }
        assertTrue(missed.message!!, missed.message!!.contains("were not verified: [20383 20:34+3"))
        val differing = thrown<AssertionError> { viaggiaTrenoRegional(vtFetcher(recent), change = oneMinuteOff) }
        assertTrue(differing.message!!, differing.message!!.contains("published delay 3 differs for 20383: 4"))
        val unreadable = thrown<AssertionError> { viaggiaTrenoRegional(vtFetcher(recent) { "<html>" }) }
        assertTrue(unreadable.message!!, unreadable.message!!.contains("no run detail was read"))
    }

    private fun accuracy(pages: RfiPages, log: MutableList<String> = mutableListOf(),
        parse: ((String, String) -> List<TrainService>)? = null) = runBlocking {
        val boards = LiveRouteChecks.rfiAccuracyBoards(3162)
        if (parse == null) LiveRouteChecks.rfiAccuracy(pages.fetcher, boards, log::add) else LiveRouteChecks.rfiAccuracy(pages.fetcher, boards, log::add, parse)
    }
    private fun accuracyPages(termini: String = board(row("9601", "20:45", "", placeholder))) =
        RfiPages(rfiCatalogue, mapOf(("3162" to false) to departures().replace(">2</td><td id=\"RBinario\">", "></td><td id=\"RBinario\">"),
            ("2416" to false) to termini, ("1728" to true) to board(row("9532", "20:50", "", placeholder))))

    private fun boardPages(
        milanoDepartures: String = board(row("9601", "20:45", "", placeholder)),
        milanoArrivals: String = board(row("9532", "20:50", "", placeholder)),
        terminiDepartures: String = board(row("9603", "20:55", "", placeholder)),
        terminiArrivals: String = board(row("9534", "21:05", "", placeholder))
    ) = RfiPages(rfiCatalogue, mapOf(("1728" to false) to milanoDepartures, ("1728" to true) to milanoArrivals,
        ("2416" to false) to terminiDepartures, ("2416" to true) to terminiArrivals))

    private fun rfiBoards(pages: RfiPages, log: MutableList<String> = mutableListOf(),
        read: (suspend (RfiMonitorFetcher, StationBoardRequest) -> ProviderResult<StationBoard>)? = null) = runBlocking {
        if (read == null) LiveRouteChecks.rfiBoards(pages.fetcher, clock, log = log::add)
        else LiveRouteChecks.rfiBoards(pages.fetcher, clock, log = log::add, read = read)
    }

    @Test fun `RFI board check reads both Milano boards, passes on an observation and is skipped while every train is on time`() {
        val log = mutableListOf<String>()
        val onTime = boardPages()
        val skipped = thrown<AssumptionViolatedException> { rfiBoards(onTime, log) }
        assertTrue(skipped.message!!, skipped.message!!.contains("Roma Termini arrivi: rows=1"))
        assertEquals(listOf("1728", "1728", "2416", "2416"), onTime.asked.toList())
        assertTrue(log.single(), log.single().startsWith("LIVE_ACCEPTANCE skipped: RFI board"))
        val late = boardPages(milanoDepartures = board(row("9601", "20:45", "7", null)))
        val passed = rfiBoards(late)
        assertEquals("Milano Centrale partenze", passed.board)
        assertEquals(1, passed.operational)
        assertEquals(listOf("1728", "1728"), late.asked.toList())
    }

    @Test fun `RFI board check fails on a run published with minutes but reported without an observation, or on an empty board`() {
        val pages = boardPages(terminiDepartures = board(row("9603", "20:55", "7", null)))
        val missed = thrown<AssertionError> { rfiBoards(pages) { fetcher, request ->
            when (val result = RfiStationBoardAdapter(fetcher = fetcher, clock = clock).getStationBoard(request)) {
                is ProviderResult.Success -> ProviderResult.Success(
                    result.value.copy(entries = result.value.entries.map { it.copy(expectedTime = null, status = null) }), result.meta)
                else -> result
            }
        } }
        assertTrue(missed.message!!, missed.message!!.contains("9603: visible=7"))
        val empty = thrown<AssertionError> { rfiBoards(boardPages(milanoArrivals = board())) }
        assertTrue(empty.message!!, empty.message!!.contains("Milano Centrale arrivi: no parseable runs"))
    }

    @Test fun `RFI accuracy is skipped only after every sampled board publishes no minutes and passes on the first that does`() {
        val log = mutableListOf<String>()
        val skipped = thrown<AssumptionViolatedException> { accuracy(accuracyPages(), log) }
        assertTrue(skipped.message!!, skipped.message!!.contains("Milano Centrale arrivi: rows=1"))
        assertTrue(log.single(), log.single().startsWith("LIVE_ACCEPTANCE skipped: RFI accuracy"))
        val passed = accuracy(accuracyPages(termini = board(row("9601", "20:45", "7", null))))
        assertEquals("Roma Termini partenze", passed.board)
        assertEquals(1, passed.compared)
    }

    @Test fun `RFI accuracy fails when the parser misses a published delay or a number cannot be read`() {
        val withMinutes = accuracyPages(termini = board(row("9601", "20:45", "7", null)))
        val missed = thrown<AssertionError> { accuracy(withMinutes) { html, name ->
            RfiStationBoardAdapter().parseDepartures(html.replace(">7</td>", "></td>"), name, "", route, Direction.OUTBOUND)
        } }
        assertTrue(missed.message!!, missed.message!!.contains("9601: published=7, parsed=null"))
        val unread = thrown<AssertionError> { accuracy(accuracyPages(termini = board(row("9601", "20:45", "ca. 7'", null)))) }
        assertTrue(unread.message!!, unread.message!!.contains("cannot read as minutes"))
    }
}
