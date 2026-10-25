package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Swiss station identities usable with transport.opendata.ch.
 *
 * The curated catalogue stores Swiss stations with a Trenitalia location id `85` + `00` + the five-digit Swiss
 * station number (for example Bellinzona `850005213` = UIC `8505213`); stations picked from the embedded Swiss
 * GTFS pack carry the UIC as GTFS stop id (`gtfs:ch-otd-gtfs:8505213:0:1`). Trenitalia's `85 009xxxx` ids are
 * aggregated city locations (Basilea, Ginevra, Zurigo), not stations, and are not mapped.
 */
internal object SwissStationIds {
    private val gtfsStop = Regex("^gtfs:ch-otd-gtfs:(?:Parent)?(85\\d{5})(?::.*)?$")
    private val trenitaliaSwiss = Regex("^8500([0-8]\\d{4})$")
    private val uic = Regex("^85\\d{5}$")

    fun fromRequest(request: StationBoardRequest): String? =
        gtfsStop.matchEntire(request.stationId.value)?.groupValues?.get(1)
            ?: request.trenitaliaLocationId?.trim()?.let { trenitaliaSwiss.matchEntire(it) }?.let { "85${it.groupValues[1]}" }

    /** True when the catalogue says the station is Swiss but carries no directly usable UIC. */
    fun isSwissWithoutUic(request: StationBoardRequest): Boolean =
        request.trenitaliaLocationId?.trim()?.startsWith("85") == true && fromRequest(request) == null

    fun isUic(value: String): Boolean = uic.matches(value)
}

/**
 * Public realtime station boards for Swiss stations from transport.opendata.ch (documented public API, no token,
 * fair-use limits). Best effort: every value comes from the response; a prognosis is used only when it belongs to
 * the requested board side and lies in a plausible window around the scheduled time, never otherwise.
 *
 * Identity: [StationBoardEntry.publicNumber] is the journey number (`name` without padding, e.g. `25839`), the
 * same number the embedded Swiss GTFS (`trip_short_name`) and the Italian boards publish, so the aggregator and
 * the route reconciler merge rows instead of duplicating them; the commercial line (`RE 80`) is [StationBoardEntry.lineLabel].
 */
class SwissOpenTransportStationBoardAdapter internal constructor(
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock,
    private val boardLimit: Int,
    retryDelayMillis: Long
) : StationBoardProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(
        HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = MAX_BOARD_BYTES)), clock, DEFAULT_LIMIT, 750
    )

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "transport.opendata.ch — tabelloni svizzeri",
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(
            countryCodes = setOf("CH", "IT"),
            description = "Stazioni svizzere e transfrontaliere con numero UIC svizzero (85…)"
        ),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = BOARD_TTL),
        schemaVersion = 1
    )

    private val boards = ResilientSourceCache<ParsedBoard>(BOARD_TTL, Duration.ofMinutes(10), clock, maxEntries = 24, retryDelayMillis = retryDelayMillis)
    private val locations = ResilientSourceCache<List<SwissLocation>>(Duration.ofDays(7), Duration.ofDays(7), clock, maxEntries = 64, retryDelayMillis = retryDelayMillis)

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> =
        withContext(Dispatchers.Default) { board(request) }

    private suspend fun board(request: StationBoardRequest): ProviderResult<StationBoard> {
        val uic = SwissStationIds.fromRequest(request) ?: resolveByName(request)
            ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Stazione senza numero UIC svizzero."))
        val url = boardUrl(uic, request)
        val read = boards.get(url) { parseBoard(fetcher.get(url), uic, request.kind) }
        return when (read) {
            is SourceRead.Failed -> ProviderResult.Failure(read.failure.toProviderError(UNAVAILABLE, UNREADABLE))
            is SourceRead.Fresh -> ProviderResult.Success(
                read.value.toStationBoard(request, read.fetchedAt),
                ProviderResponseMeta(SOURCE_ID, read.fetchedAt, observedAt = read.fetchedAt, expiresAt = read.expiresAt, fromCache = read.fromCache)
            )
            is SourceRead.Stale -> ProviderResult.Partial(
                read.value.toStationBoard(request, read.fetchedAt),
                setOf(ProviderWarning.STALE_SOURCE),
                ProviderResponseMeta(SOURCE_ID, read.fetchedAt, observedAt = read.fetchedAt, fromCache = true)
            )
        }
    }

    /**
     * Name lookup, once per station and cached: only for stations the catalogue marks as Swiss without a usable
     * UIC, or near-border stations with coordinates. Only `icon == "train"` results with a Swiss `85…` id are
     * accepted, and when coordinates are known the result must lie within [MAX_LOCATION_DISTANCE_KM].
     */
    private suspend fun resolveByName(request: StationBoardRequest): String? {
        val name = request.stationName?.trim()?.takeIf { it.length in 2..80 } ?: return null
        val swiss = SwissStationIds.isSwissWithoutUic(request)
        val nearBorder = request.coordinates?.let(::inBorderRegion) == true
        if (!swiss && !nearBorder) return null
        if (!swiss && request.coordinates == null) return null
        val url = "$BASE_URL/locations?query=${URLEncoder.encode(name, "UTF-8")}&type=station"
        val candidates = when (val read = locations.get(url) { parseLocations(fetcher.get(url)) }) {
            is SourceRead.Fresh -> read.value
            is SourceRead.Stale -> read.value
            is SourceRead.Failed -> return null
        }
        return selectLocation(candidates, name, request.coordinates)
    }

    internal fun selectLocation(candidates: List<SwissLocation>, name: String, coordinates: GeoPoint?): String? =
        candidates.asSequence()
            .filter { it.icon == "train" && SwissStationIds.isUic(it.id) }
            .firstOrNull { candidate ->
                val place = candidate.coordinates
                if (coordinates != null) place != null && distanceKm(coordinates, place) <= MAX_LOCATION_DISTANCE_KM
                else normalizeName(candidate.name) == normalizeName(name)
            }?.id

    internal fun parseLocations(body: String): List<SwissLocation> {
        val stations = JSONObject(body).optJSONArray("stations") ?: throw PublicSourceFormatException("Elenco località assente")
        return (0 until minOf(stations.length(), 50)).mapNotNull { index ->
            val item = stations.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("id") ?: return@mapNotNull null
            SwissLocation(id, item.text("name").orEmpty(), item.text("icon"), item.optJSONObject("coordinate")?.geoPoint())
        }
    }

    internal fun parseBoard(body: String, requestedUic: String, kind: BoardKind): ParsedBoard {
        val root = JSONObject(body)
        val station = root.optJSONObject("station") ?: throw PublicSourceFormatException("Stazione assente nella risposta")
        if (station.text("id") != requestedUic) throw PublicSourceFormatException("La risposta riguarda un'altra stazione")
        val rows = root.optJSONArray("stationboard") ?: throw PublicSourceFormatException("Tabellone assente")
        val arrival = kind == BoardKind.ARRIVALS
        val parsed = (0 until minOf(rows.length(), MAX_ROWS)).mapNotNull { index ->
            rows.optJSONObject(index)?.let { parseRow(it, requestedUic, arrival) }
        }
        return ParsedBoard(requestedUic, kind, parsed)
    }

    private fun parseRow(row: JSONObject, requestedUic: String, arrival: Boolean): BoardRow? {
        val category = row.text("category")?.uppercase(Locale.ROOT)?.take(8)
        if (category != null && category in NON_RAIL_CATEGORIES) return null
        val stop = row.optJSONObject("stop") ?: return null
        // On an arrival board only rows with a scheduled arrival exist; departure-only rows are not arrivals.
        val scheduled = (if (arrival) stop.dateTime("arrival") else stop.dateTime("departure")) ?: return null
        // `name` is the journey number (zero padded); `number` is the line for S/RE/IC rows, so it is no identity.
        val journeyNumber = digitsOrNull(row.text("name")) ?: return null
        val endpoint = if (arrival) originLabel(row, requestedUic) else row.text("to")
        if (endpoint.isNullOrBlank()) return null
        val prognosis = stop.optJSONObject("prognosis")
        val trustedPrognosis = prognosis?.dateTime(if (arrival) "arrival" else "departure")
            ?.takeIf { plausible(it, scheduled, arrival) }
        val delay = stop.intOrNull("delay")?.takeIf { it in (if (arrival) -MAX_EARLY_ARRIVAL_MINUTES else -1)..MAX_DELAY_MINUTES }
        val expected = trustedPrognosis ?: delay?.let { scheduled.plusMinutes(it.toLong()) }
        val lineNumber = row.text("number")?.let { digitsOrNull(it) ?: it.take(8) }
        val lineLabel = category?.takeIf { it.isNotBlank() }?.let { cat -> listOfNotNull(cat, lineNumber).joinToString(" ") }
        return BoardRow(
            journeyNumber = journeyNumber,
            lineLabel = lineLabel,
            operator = row.text("operator")?.let(::operatorId),
            endpoint = endpoint.take(120),
            scheduled = scheduled,
            expected = expected,
            platform = (prognosis?.text("platform") ?: stop.text("platform"))?.take(12)
        )
    }

    private fun originLabel(row: JSONObject, requestedUic: String): String? {
        val passList = row.optJSONArray("passList") ?: return null
        val first = passList.optJSONObject(0)?.optJSONObject("station") ?: return null
        if (first.text("id") == requestedUic) return null
        return first.text("name")
    }

    /** A prognosis on the requested side within a sane window: departures never leave early, arrivals at most a little. */
    private fun plausible(prognosis: ZonedDateTime, scheduled: ZonedDateTime, arrival: Boolean): Boolean {
        val minutes = Duration.between(scheduled, prognosis).toMinutes()
        val earliest = if (arrival) -MAX_EARLY_ARRIVAL_MINUTES.toLong() else -1L
        return minutes in earliest..MAX_DELAY_MINUTES.toLong()
    }

    private fun ParsedBoard.toStationBoard(request: StationBoardRequest, fetchedAt: Instant): StationBoard {
        val expiresAt = fetchedAt.plus(BOARD_TTL)
        val boardSource = SourceRef(
            sourceId = SOURCE_ID, nativeId = uic, observedAt = fetchedAt, fetchedAt = fetchedAt, expiresAt = expiresAt,
            confidence = Confidence.OPEN_DATA, licenseId = LICENSE, traceId = "${SOURCE_ID.value}:board:$uic:${fetchedAt.toEpochMilli()}"
        )
        val entries = rows.asSequence()
            .filter { !it.scheduled.toInstant().isBefore(request.from) && !it.scheduled.toInstant().isAfter(request.to) }
            .map { row ->
                val source = boardSource.copy(nativeId = "$uic:${row.journeyNumber}:${row.scheduled.toEpochSecond()}",
                    traceId = "${SOURCE_ID.value}:$uic:${row.journeyNumber}:${row.scheduled.toEpochSecond()}")
                val expected = row.expected
                StationBoardEntry(
                    runId = TrainRunId("${SOURCE_ID.value}:${row.journeyNumber}:${row.scheduled.toLocalDate()}"),
                    scheduledTime = row.scheduled,
                    expectedTime = expected?.let { SourcedValue(it, source) },
                    destinationOrOrigin = StationId("ch-published:${normalizeName(row.endpoint).lowercase(Locale.ROOT).replace(" ", "")}"),
                    destinationOrOriginLabel = row.endpoint,
                    publicNumber = row.journeyNumber,
                    operatorId = row.operator?.let(::OperatorId),
                    platform = row.platform?.takeIf(String::isNotBlank)?.let { SourcedValue(it, source) },
                    status = expected?.let {
                        SourcedValue(if (it.isAfter(row.scheduled.plusSeconds(59))) RunStatus.DELAYED else RunStatus.ON_TIME, source)
                    },
                    nativeRunIds = mapOf(SOURCE_ID to "$uic:${row.journeyNumber}:${row.scheduled.toEpochSecond()}"),
                    lineLabel = row.lineLabel
                )
            }
            .sortedBy { it.scheduledTime.toInstant() }
            .toList()
        return StationBoard(
            stationId = request.stationId, kind = request.kind, from = request.from, to = request.to, entries = entries,
            snapshot = SnapshotMeta(
                schemaVersion = 1, generatedAt = fetchedAt, validUntil = expiresAt,
                freshness = if (clock.instant().isAfter(expiresAt)) Freshness.STALE else Freshness.FRESH,
                completeness = Completeness.PARTIAL, contributingSources = setOf(boardSource)
            )
        )
    }

    private fun boardUrl(uic: String, request: StationBoardRequest): String {
        val now = clock.instant()
        val current = !request.from.isBefore(now.minus(Duration.ofMinutes(45))) && !request.from.isAfter(now.plus(Duration.ofMinutes(5)))
        return buildString {
            append("$BASE_URL/stationboard?id=").append(uic).append("&limit=").append(boardLimit)
            append("&transportations%5B%5D=train")
            if (request.kind == BoardKind.ARRIVALS) append("&type=arrival")
            if (!current) {
                val local = request.from.atZone(SWISS_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
                append("&datetime=").append(URLEncoder.encode(local, "UTF-8").replace("+", "%20"))
            }
        }
    }

    private fun operatorId(raw: String): String? {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            value.isBlank() -> null
            "tilo" in value -> "tilo"
            Regex("(^|[^a-z])sbb([^a-z]|$)").containsMatchIn(value) -> "sbb"
            "bls" in value -> "bls"
            value == "rhb" || "rhätische" in value || "rhaetische" in value -> "rhb"
            "fart" in value || "ssif" in value -> "fart-ssif"
            "trenord" in value -> "trenord"
            "trenitalia" in value -> "trenitalia"
            else -> value.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { null }
        }
    }

    private fun inBorderRegion(point: GeoPoint): Boolean =
        point.latitude in 45.80..47.85 && point.longitude in 5.95..10.50

    private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun JSONObject.geoPoint(): GeoPoint? {
        // transport.opendata.ch publishes WGS84 latitude as `x` and longitude as `y`.
        val lat = if (has("x") && !isNull("x")) optDouble("x") else return null
        val lon = if (has("y") && !isNull("y")) optDouble("y") else return null
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }

    private fun JSONObject.dateTime(key: String): ZonedDateTime? = text(key)?.let { raw ->
        runCatching { OffsetDateTime.parse(raw, OFFSET_FORMAT).atZoneSameInstant(SWISS_ZONE) }.getOrNull()
    }

    private fun JSONObject.text(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.intOrNull(key: String): Int? = opt(key)?.takeIf { it != JSONObject.NULL }?.let {
        when (it) {
            is Number -> it.toInt()
            else -> it.toString().trim().toIntOrNull()
        }
    }

    private fun digitsOrNull(value: String?): String? =
        value?.takeIf { it.matches(Regex("\\d{1,8}")) }?.trimStart('0')?.ifEmpty { null }

    internal data class SwissLocation(val id: String, val name: String, val icon: String?, val coordinates: GeoPoint?)

    internal data class BoardRow(
        val journeyNumber: String,
        val lineLabel: String?,
        val operator: String?,
        val endpoint: String,
        val scheduled: ZonedDateTime,
        val expected: ZonedDateTime?,
        val platform: String?
    )

    internal data class ParsedBoard(val uic: String, val kind: BoardKind, val rows: List<BoardRow>)

    companion object {
        val SOURCE_ID = SourceId("ch-transport-opendata")
        internal const val BASE_URL = "https://transport.opendata.ch/v1"
        internal const val LICENSE = "transport.opendata.ch — API pubblica, uso secondo le condizioni del servizio"
        private val BOARD_TTL: Duration = Duration.ofSeconds(60)
        private const val DEFAULT_LIMIT = 40
        private const val MAX_ROWS = 200
        private const val MAX_BOARD_BYTES = 3 * 1024 * 1024
        private const val MAX_DELAY_MINUTES = 360
        private const val MAX_EARLY_ARRIVAL_MINUTES = 15
        private const val MAX_LOCATION_DISTANCE_KM = 1.5
        private const val UNAVAILABLE = "Il tabellone transport.opendata.ch non è raggiungibile."
        private const val UNREADABLE = "Il tabellone transport.opendata.ch non è leggibile."
        private val SWISS_ZONE: ZoneId = ZoneId.of("Europe/Zurich")
        private val NON_RAIL_CATEGORIES = setOf(
            "B", "BUS", "NFB", "KB", "EXB", "BN", "BP", "CAR", "T", "TRAM", "NFT", "M", "MET", "FUN", "PB", "GB",
            "LB", "SL", "BAT", "FAE", "KAT", "SCH", "TX", "ALT"
        )
        private val OFFSET_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .toFormatter(Locale.ROOT)

        internal fun normalizeName(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()
    }
}
