package it.binario.app.data.live

import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.gtfs.BoundedGtfsZipScanner
import it.binario.app.data.gtfs.GtfsCompactIndexLimits
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What a live acceptance check reads straight from a source's own payload, without the adapter under test, so that a
 * failure says whether the source published anything to verify at that moment (RFI, ViaggiaTreno: diagnostics only,
 * their assertions are unchanged) and so that the STA identity check looks only at the part of the feed the rail-only
 * pack covers. Every rule is a sufficient condition: whatever it finds, the adapter must verify as well (offline cases
 * in LiveAcceptanceEvidenceTest).
 */
internal object LiveAcceptanceEvidence {
    /** Minutes as passengers read them in an RFI delay cell: `5`, `+5`, `5'`, `5 min`, `Ritardo 5`. */
    private val MINUTES = Regex("^(?:ritardo\\s*)?\\+?(\\d{1,3})\\s*(?:min(?:uti)?\\.?|['′])?$", RegexOption.IGNORE_CASE)
    private val CANCELLED = Regex("\\b(?:soppress\\w*|cancellat\\w*)\\b", RegexOption.IGNORE_CASE)
    /** The carriers whose run identity the RFI route source reconciles (it drops rows of any other carrier). */
    private val RECONCILED_CARRIER = Regex("trenitalia|italo|ntv|trenord|frecc", RegexOption.IGNORE_CASE)

    /** One row of an RFI monitor as published: train, clock time, carrier and the delay cell (visible text, accessible label). */
    data class RfiRow(val number: String, val time: LocalTime?, val carrier: String, val visible: String, val aria: String, val cancelled: Boolean) {
        /** Minutes the cell itself shows. */
        val visibleDelay: Int? get() = MINUTES.matchEntire(visible)?.groupValues?.get(1)?.toIntOrNull()
        /** Minutes in the accessible label of a cell that shows no number (RFI also quotes it: `"6"`). */
        val labelDelay: Int? get() = if (visible.any(Char::isDigit)) null
            else MINUTES.matchEntire(aria.removeSurrounding("\"").trim())?.groupValues?.get(1)?.toIntOrNull()
        /** The published delay this check compares, or null when the cell publishes none (empty, `"Nessuno"`, a bare `RITARDO`). */
        val publishedDelay: Int? get() = visibleDelay ?: labelDelay
        /** Digits in the cell that are not minutes this check can read: a format the comparison would be blind to. */
        val unreadNumber: Boolean get() = publishedDelay == null && (visible.any(Char::isDigit) || aria.any(Char::isDigit))
        /** How the cell was published, for the diagnostics. */
        val format: String get() = when {
            visibleDelay != null -> "minutes"
            labelDelay != null -> "label-minutes"
            unreadNumber -> "unread-number"
            visible.isBlank() && aria.isBlank() -> "empty"
            visible.isBlank() -> "label:$aria"
            aria.isBlank() -> "text:$visible"
            else -> "text:$visible|label:$aria"
        }
    }

    fun rfiRows(html: String): List<RfiRow> = Jsoup.parse(html).select("tr[name=treno]").mapNotNull { row ->
        val number = row.selectFirst("#RTreno")?.text()?.trim().orEmpty()
        if (number.isEmpty()) return@mapNotNull null
        val cell = row.selectFirst("#RRitardo")
        RfiRow(number, clockTime(row.selectFirst("#ROrario")?.text().orEmpty()), row.selectFirst("#RVettore img")?.attr("alt")?.trim().orEmpty(),
            cell?.text()?.trim().orEmpty(), cell?.attr("aria-label")?.trim().orEmpty(), CANCELLED.containsMatchIn(row.text()))
    }

    fun rfiFormats(rows: List<RfiRow>): Map<String, Int> = rows.groupingBy { it.format }.eachCount()

    /**
     * Departures the RFI route source has to reconcile: a published delay in minutes or a cancellation on the origin's
     * departures, the same train of the same reconciled carrier exactly once on the destination's arrivals within ten
     * hours, and an expected departure from [requested] to eight hours after it (a train already gone is not offered).
     */
    fun rfiReconcilableRuns(departures: List<RfiRow>, arrivals: List<RfiRow>, requested: LocalTime): List<String> =
        departures.mapNotNull { departure ->
            val time = departure.time ?: return@mapNotNull null
            val delay = departure.publishedDelay
            if (delay == null && !departure.cancelled) return@mapNotNull null
            if (!RECONCILED_CARRIER.containsMatchIn(departure.carrier)) return@mapNotNull null
            val expected = if (departure.cancelled || delay == null) time else time.plusMinutes(delay.toLong())
            if (minutesForward(requested, expected) > 8 * 60) return@mapNotNull null
            val arrival = arrivals.singleOrNull { it.number == departure.number && it.carrier.equals(departure.carrier, ignoreCase = true) }
                ?: return@mapNotNull null
            val arrivalTime = arrival.time ?: return@mapNotNull null
            if (minutesForward(time, arrivalTime) !in 1..10 * 60) return@mapNotNull null
            "${departure.number} $time${if (departure.cancelled) " soppresso" else "+$delay"} → $arrivalTime"
        }

    /**
     * Run details (`andamentoTreno/<origin>/<number>/<date>` → JSON) that the ViaggiaTreno route source has to verify:
     * the run is the one asked for, calls at [origin] and later at [destination] with no skipped stop between, is not
     * cancelled, has left its first station, publishes `ritardo`, its last detection lies in [recentFrom]..[recentUntil]
     * and its expected departure from [origin] is from [requested] to eight hours after it.
     */
    fun viaggiaTrenoVerifiableRuns(details: Map<String, JSONObject>, origin: String, destination: String,
        requested: Instant, recentFrom: Instant, recentUntil: Instant): List<String> = details.mapNotNull { (path, run) ->
        val (_, runOrigin, number, date) = path.split('/').takeIf { it.size == 4 } ?: return@mapNotNull null
        if (run.text("numeroTreno") != number || run.text("idOrigine") != runOrigin) return@mapNotNull null
        if (run.text("dataPartenzaTreno")?.let { it != date } == true) return@mapNotNull null
        if (run.text("provvedimento") == "1" || run.text("tipoTreno") == "ST" || run.optBoolean("nonPartito", false)) return@mapNotNull null
        val delay = run.text("ritardo")?.toIntOrNull() ?: return@mapNotNull null
        val observed = run.text("oraUltimoRilevamento")?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
        if (observed.isBefore(recentFrom) || observed.isAfter(recentUntil)) return@mapNotNull null
        val stops = run.optJSONArray("fermate").objects()
        val start = stops.indexOfFirst { it.text("id") == origin }
        val end = stops.indexOfFirst { it.text("id") == destination }
        if (start < 0 || end <= start) return@mapNotNull null
        if (stops.subList(start, end + 1).any { it.text("actualFermataType") in setOf("2", "3") }) return@mapNotNull null
        val boarding = stops[start]
        val scheduled = (boarding.text("partenza_teorica") ?: boarding.text("programmata"))?.toLongOrNull()?.let(Instant::ofEpochMilli)
            ?: return@mapNotNull null
        val expected = boarding.text("partenzaReale")?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: scheduled.plusSeconds(delay * 60L)
        if (expected.isBefore(requested) || expected.isAfter(requested.plusSeconds(8 * 3600))) return@mapNotNull null
        "$number ${scheduled.atZone(ROME).toLocalTime()}+$delay observed=${observed.atZone(ROME).toLocalTime()}"
    }

    /** One line per fetched run, for the diagnostics: departure from [origin], `ritardo`, age of the last detection, `nonPartito`. */
    fun viaggiaTrenoRunSummary(details: Map<String, JSONObject>, origin: String, at: Instant): List<String> =
        details.entries.sortedBy { it.key }.map { (path, run) ->
            val boarding = run.optJSONArray("fermate").objects().firstOrNull { it.text("id") == origin }
            val scheduled = boarding?.text("partenza_teorica")?.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ROME).toLocalTime() }
            val observed = run.text("oraUltimoRilevamento")?.toLongOrNull()?.let { Duration.between(Instant.ofEpochMilli(it), at).seconds }
            "${path.split('/').getOrNull(2)}@$scheduled ritardo=${run.text("ritardo")} detectedAgoSeconds=$observed " +
                "nonPartito=${run.optBoolean("nonPartito", false)} provvedimento=${run.text("provvedimento")}"
        }

    /**
     * Runs on the `partenze` boards the route source reads whose details it asks for: identity published and not left
     * before [requested]. The source reads the board from the request and from two hours earlier: a run counts once.
     */
    fun viaggiaTrenoUpcomingRuns(boards: Collection<String>, requested: Instant): Int = boards.flatMap { body ->
        if (body.trim() == "null") emptyList() else JSONArray(body).objects().mapNotNull { row ->
            val number = row.text("numeroTreno")?.takeIf { it.matches(Regex("[0-9]{1,6}")) } ?: return@mapNotNull null
            val origin = row.text("codOrigine")?.takeIf { it.matches(Regex("S[0-9]{5}")) } ?: return@mapNotNull null
            val date = row.text("dataPartenzaTreno") ?: row.text("millisDataPartenza") ?: return@mapNotNull null
            val departure = row.text("orarioPartenza")?.toLongOrNull() ?: return@mapNotNull null
            val late = (row.text("ritardo")?.toIntOrNull() ?: 0).coerceAtLeast(0) * 60_000L
            "$origin/$number/$date".takeIf { departure + late >= requested.toEpochMilli() }
        }
    }.distinct().size

    /** The identities a static GTFS pack publishes: each trip with its route, and the stops. */
    class GtfsPackIdentity(val tripRoutes: Map<String, String>, val stopIds: Set<String>) {
        val tripIds: Set<String> get() = tripRoutes.keys
        val routeIds: Set<String> = tripRoutes.values.toSet()

        companion object {
            fun read(bytes: ByteArray): GtfsPackIdentity {
                val limits = GtfsCompactIndexLimits()
                val trips = HashMap<String, String>()
                val stops = HashSet<String>()
                BoundedGtfsZipScanner(limits).scan(bytes.inputStream(), setOf("trips.txt", "stops.txt")) { table, rows ->
                    if (table == "trips.txt") {
                        val trip = rows.header.indexOf("trip_id")
                        val route = rows.header.indexOf("route_id")
                        rows.forEachRow(limits.maxTrips) { row -> row.getOrNull(trip)?.let { trips[it] = row.getOrNull(route).orEmpty() } }
                    } else {
                        val stop = rows.header.indexOf("stop_id")
                        rows.forEachRow(limits.maxStops) { row -> row.getOrNull(stop)?.let(stops::add) }
                    }
                }
                return GtfsPackIdentity(trips, stops)
            }
        }
    }

    /**
     * Updates about the pack's own network: at one of its stops, or on one of its routes when the feed's trip id names
     * the route ([routeOfTrip]). Their trip ids have to be the pack's. Updates elsewhere (the STA buses beside the
     * rail-only pack) prove nothing about the pack either way.
     */
    fun onPackNetwork(updates: List<RealtimeStopUpdate>, pack: GtfsPackIdentity, routeOfTrip: (String) -> String?): List<RealtimeStopUpdate> =
        updates.filter { update ->
            update.sourceStopId?.let(pack.stopIds::contains) == true || routeOfTrip(update.sourceTripId)?.let(pack.routeIds::contains) == true
        }

    /** STA trip ids carry their route: `<n>.<calendar>.<route_id>.<n>.<H|R>`, as every trip of the embedded pack does. */
    fun staRouteOfTrip(tripId: String): String? = tripId.split('.').takeIf { it.size == 5 }?.get(2)?.takeIf(String::isNotBlank)

    private val ROME: ZoneId = ZoneId.of("Europe/Rome")

    /** Minutes from [from] forward to [to] on a 24-hour clock: a time just before [from] is almost a day ahead. */
    private fun minutesForward(from: LocalTime, to: LocalTime): Int = Math.floorMod(Duration.between(from, to).toMinutes().toInt(), 24 * 60)

    private fun clockTime(value: String): LocalTime? = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(value)
        ?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    private fun JSONObject.text(key: String): String? = opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
}
