package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RealtimeRouteDataSource
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.provider.StationBoardProvider
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The RFI and ViaggiaTreno live checks of LiveProviderNetworkTest, with fetchers and sources passed in so that
 * LiveAcceptanceEvidenceTest runs the very same decisions offline. Each check samples its routes (or boards) in order
 * and stops at the first one that decides:
 * - the adapter verified a train, with the delay the payload publishes: the check passes;
 * - the payload publishes something verifiable ([LiveAcceptanceEvidence]) and the adapter did not verify it, or gives
 *   another delay: the check fails;
 * - the payload publishes nothing verifiable at this moment: the next route.
 * When no route decides, the check is skipped (an assumption, never a pass) after one LIVE_ACCEPTANCE line with what
 * each route published. A board or run the adapter could not read fails the check, and so do numbers the evidence rules
 * cannot read on a board that would otherwise be skipped.
 */
internal object LiveRouteChecks {
    /** Production default of the ViaggiaTreno route detail wait, passed explicitly so the check knows when it ran out. */
    private const val VIAGGIATRENO_DETAILS_TIMEOUT_MILLIS = 8_000L

    /**
     * Appiano → Roma Ostiense (the reported 23263) first, then two busy lines from small stations, where the next
     * departures mostly serve the destination: few requests for a good chance of a train with a recent observation.
     */
    val regionalRoutes: List<SavedRoute> = listOf(
        SavedRoute("live-regional", "Appiano → Roma Ostiense",
            Station("curated:S08328", "Appiano Proba Petronia", "Roma", railwayCode = "S08328", aliases = listOf("APPIANO")),
            Station("curated:S08406", "Roma Ostiense", "Roma", railwayCode = "S08406")),
        SavedRoute("live-ciampino", "Ciampino → Roma Termini",
            Station("curated:S08650", "Ciampino", "Ciampino", railwayCode = "S08650"),
            Station("curated:S08409", "Roma Termini", "Roma", railwayCode = "S08409")),
        SavedRoute("live-monza", "Monza → Milano Centrale",
            Station("curated:S01322", "Monza", "Monza", railwayCode = "S01322"),
            Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700")))

    /** The route that decided, and its services. */
    data class Passed(val route: SavedRoute, val services: List<TrainService>)

    suspend fun rfiRegional(
        fetcher: RfiMonitorFetcher,
        routes: List<SavedRoute>,
        query: JourneyQuery,
        clock: Clock,
        log: (String) -> Unit = ::println,
        routeSource: (StationBoardProvider) -> RealtimeRouteDataSource = { RfiLiveRouteDataSource(it, clock) }
    ): Passed {
        val boards = ConcurrentHashMap<String, List<LiveAcceptanceEvidence.RfiRow>>()
        val provider = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { url ->
            fetcher.fetch(url).also { html ->
                if (url.contains("/Monitor?")) boards[if (url.endsWith("arrivals=True")) "arrivals" else "departures"] = LiveAcceptanceEvidence.rfiRows(html)
            }
        }, clock = clock)
        val source = routeSource(provider)
        val seen = mutableListOf<String>()
        for (route in routes) {
            boards.clear()
            val result = source.loadRoute(route, Direction.OUTBOUND, query)
            val departures = boards["departures"]
            val arrivals = boards["arrivals"]
            val summary = "${route.name}: departures=${departures?.let(::rfiSummary)}, arrivals=${arrivals?.let(::rfiSummary)}"
            // A board that was not read, or that the adapter could not read, is a failure whatever the boards publish.
            if ((result as? DataResult.Failure)?.reason == RouteFailureReason.NetworkUnavailable || departures == null || arrivals == null) {
                throw AssertionError("RFI regional journey $summary; $result")
            }
            val services = (result as? DataResult.Success)?.snapshot?.services.orEmpty()
            val verified = services.filter { it.operationalStatusVerified && "rfi-station-board" in it.operationalSourceIds }
            val evidence = LiveAcceptanceEvidence.rfiReconcilableRuns(departures, arrivals, query.departureTime)
            if (verified.isNotEmpty()) {
                val differing = evidence.filter { run -> run.delay != null && verified.any { it.number == run.number && it.officialDelayMinutes != run.delay } }
                if (differing.isNotEmpty()) throw AssertionError("RFI regional journey $summary: delays differ from the boards for $differing; " +
                    "verified=${verified.map { it.number + "+" + it.officialDelayMinutes }}")
                return Passed(route, services)
            }
            if (evidence.isNotEmpty()) throw AssertionError("RFI regional journey $summary: the boards publish $evidence but the route source " +
                "verified none; programmed=${services.take(3).map { it.number + ":" + it.scheduledDeparture }}; $result")
            unreadNumbers("RFI regional journey", summary, departures)
            seen += summary
        }
        skip("RFI regional journey: no sampled route has a train on both boards with a delay in minutes or a cancellation; ${seen.joinToString(" | ")}", log)
    }

    suspend fun viaggiaTrenoRegional(
        fetcher: ViaggiaTrenoRealtimeFetcher,
        routes: List<SavedRoute>,
        query: JourneyQuery,
        clock: Clock,
        log: (String) -> Unit = ::println,
        routeSource: (ViaggiaTrenoRealtimeFetcher) -> RealtimeRouteDataSource = {
            ViaggiaTrenoRealtimeAdapter(it, clock, routeDetailsTimeoutMillis = VIAGGIATRENO_DETAILS_TIMEOUT_MILLIS)
        }
    ): Passed {
        val requested = query.requestedAt.atZone(RailTime.defaultZone).toInstant()
        val seen = mutableListOf<String>()
        for (route in routes) {
            val origin = requireNotNull(route.outwardOrigin.railwayCode)
            val destination = requireNotNull(route.outwardDestination.railwayCode)
            val details = ConcurrentHashMap<String, JSONObject>()
            val boards = ConcurrentLinkedQueue<String>()
            // A new source per route: its response cache must not hide a route's requests from the evidence.
            val source = routeSource(ViaggiaTrenoRealtimeFetcher { path ->
                fetcher.fetch(path).also {
                    if (path.startsWith("andamentoTreno/") && it.trim().startsWith("{")) details[path] = JSONObject(it)
                    if (path.startsWith("partenze/")) boards += it
                }
            })
            val started = clock.instant()
            val startedNanos = System.nanoTime()
            val result = source.loadRoute(route, Direction.OUTBOUND, query)
            val waitedForEveryDetail = (System.nanoTime() - startedNanos) / 1_000_000 < VIAGGIATRENO_DETAILS_TIMEOUT_MILLIS
            val upcoming = LiveAcceptanceEvidence.viaggiaTrenoUpcomingRuns(boards, requested)
            val summary = "${route.name}: boardRuns=$upcoming; runs=${LiveAcceptanceEvidence.viaggiaTrenoRunSummary(details, origin, started)}"
            if ((result as? DataResult.Failure)?.reason == RouteFailureReason.NetworkUnavailable) throw AssertionError("ViaggiaTreno regional $summary; $result")
            val services = (result as? DataResult.Success)?.snapshot?.services.orEmpty()
            val verified = services.filter { it.operationalStatusVerified && it.status != ServiceStatus.CANCELLED }
            verified.forEach { service ->
                val raw = details[detailPath(service)] ?: throw AssertionError("ViaggiaTreno regional $summary: no run detail for the verified ${service.number}")
                if (raw.optInt("ritardo", Int.MIN_VALUE) != service.officialDelayMinutes) {
                    throw AssertionError("ViaggiaTreno regional $summary: published delay ${raw.opt("ritardo")} differs for ${service.number}: ${service.officialDelayMinutes}")
                }
            }
            if (verified.isNotEmpty()) return Passed(route, services)
            if (upcoming > 0 && details.isEmpty() && waitedForEveryDetail) {
                throw AssertionError("ViaggiaTreno regional $summary: the board lists $upcoming runs still to leave but no run detail was read; $result")
            }
            // After the detail wait ran out, only the runs the source finished reading are its to verify.
            val read = if (waitedForEveryDetail) details else details.filterKeys { path -> services.any { detailPath(it) == path } }
            val evidence = LiveAcceptanceEvidence.viaggiaTrenoVerifiableRuns(read, origin, destination, requested,
                started.minusSeconds(270), started.plusSeconds(60))
            if (evidence.isNotEmpty()) throw AssertionError("ViaggiaTreno regional $summary: runs that left with a published delay and a recent " +
                "detection were not verified: $evidence; $result")
            seen += summary
        }
        skip("ViaggiaTreno regional: no sampled route has a run between its stations, in its direction, that left and was detected in " +
            "the last five minutes; ${seen.joinToString(" | ")}", log)
    }

    /** The accuracy boards: Appiano departures (the reported 23263), then two busy boards read by the same parser. */
    fun rfiAccuracyBoards(appianoPlaceId: Int): List<Pair<String, String>> = listOf(
        "Appiano partenze" to monitorUrl(appianoPlaceId, arrivals = false),
        "Roma Termini partenze" to monitorUrl(requireNotNull(RfiStationIds.placeId("roma-termini")), arrivals = false),
        "Milano Centrale arrivi" to monitorUrl(requireNotNull(RfiStationIds.placeId("milano-centrale")), arrivals = true))

    fun monitorUrl(placeId: Int, arrivals: Boolean): String =
        "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=$placeId&arrivals=${if (arrivals) "True" else "False"}"

    /** The board that decided: how many published delays it compared, its cell formats, the 23263 when listed. */
    data class AccuracyPassed(val board: String, val compared: Int, val formats: Map<String, Int>, val reportedTrain: String)

    private val accuracyRoute = SavedRoute("accuracy", "Appiano", Station("origin", "Appiano", "Roma"), Station("destination", "Roma Ostiense", "Roma"))

    /** Every delay in minutes a board publishes, against what the parser makes of the same row. */
    suspend fun rfiAccuracy(
        fetcher: RfiMonitorFetcher,
        boards: List<Pair<String, String>>,
        log: (String) -> Unit = ::println,
        parse: (String, String) -> List<TrainService> = { html, name ->
            RfiStationBoardAdapter().parseDepartures(html, name, "", accuracyRoute, Direction.OUTBOUND)
        }
    ): AccuracyPassed {
        val seen = mutableListOf<String>()
        var reportedTrain = "23263 not present on the sampled boards"
        for ((name, url) in boards) {
            val html = fetcher.fetch(url)
            val rows = LiveAcceptanceEvidence.rfiRows(html)
            val services = parse(html, name)
            var compared = 0
            val mismatches = mutableListOf<String>()
            rows.forEach { row ->
                val service = services.singleOrNull { it.number == row.number }
                if (row.number == "23263") reportedTrain = "23263 on $name scheduled=${service?.scheduledDeparture}, visible=${row.visible}, " +
                    "aria=${row.aria}, parsed=${service?.officialDelayMinutes}, verified=${service?.operationalStatusVerified}"
                val delay = row.publishedDelay ?: return@forEach
                compared++
                if (service == null || service.officialDelayMinutes != delay || !service.operationalStatusVerified) {
                    mismatches += "${row.number}: published=$delay, parsed=${service?.officialDelayMinutes}"
                }
            }
            val summary = "$name: ${rfiSummary(rows)}"
            if (mismatches.isNotEmpty()) throw AssertionError("RFI delay mismatch against the published board $summary: ${mismatches.take(10)}")
            if (compared > 0) return AccuracyPassed(name, compared, LiveAcceptanceEvidence.rfiFormats(rows), reportedTrain)
            unreadNumbers("RFI accuracy", summary, rows)
            seen += summary
        }
        skip("RFI accuracy: no sampled board publishes a delay in minutes at this moment; $reportedTrain; ${seen.joinToString(" | ")}", log)
    }

    private fun rfiSummary(rows: List<LiveAcceptanceEvidence.RfiRow>) = "rows=${rows.size} formats=${LiveAcceptanceEvidence.rfiFormats(rows)}"

    /** A board about to be skipped must not hide numbers this check cannot read: a format the evidence would be blind to. */
    private fun unreadNumbers(check: String, summary: String, rows: List<LiveAcceptanceEvidence.RfiRow>) {
        val unread = rows.filter { it.unreadNumber }
        if (unread.isNotEmpty()) throw AssertionError("$check $summary: delay cells with numbers this check cannot read as minutes: " +
            unread.take(10).map { "${it.number}: visible=${it.visible}, aria=${it.aria}" })
    }

    private fun detailPath(service: TrainService): String = service.id.split(':').let { "andamentoTreno/${it[1]}/${it[2]}/${it[3]}" }

    private fun skip(message: String, log: (String) -> Unit): Nothing {
        log("LIVE_ACCEPTANCE skipped: $message")
        throw AssumptionViolatedException(message)
    }
}
