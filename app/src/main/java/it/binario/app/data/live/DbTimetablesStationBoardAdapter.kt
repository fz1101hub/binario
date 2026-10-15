package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
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
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element

interface DbTimetablesFetcher {
    suspend fun findStation(pattern: String): ByteArray
    suspend fun planned(eva: String, date: String, hour: String): ByteArray
    suspend fun fullChanges(eva: String): ByteArray
}

/** Credentials are supplied by caller/gateway and never read from app resources. */
class AuthorizedDbTimetablesFetcher(
    private val clientId: String,
    private val apiKey: String
) : DbTimetablesFetcher {
    init {
        require(clientId.isNotBlank()) { "DB Client ID mancante" }
        require(apiKey.isNotBlank()) { "DB API Key mancante" }
    }

    override suspend fun findStation(pattern: String): ByteArray =
        get("station/${URLEncoder.encode(pattern, StandardCharsets.UTF_8.name())}")

    override suspend fun planned(eva: String, date: String, hour: String): ByteArray =
        get("plan/$eva/$date/$hour")

    override suspend fun fullChanges(eva: String): ByteArray = get("fchg/$eva")

    private suspend fun get(path: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL("$BASE_URL/$path").openConnection() as? HttpURLConnection
            ?: throw IOException("DB Timetables non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("DB-Client-Id", clientId)
        connection.setRequestProperty("DB-Api-Key", apiKey)
        connection.setRequestProperty("Accept", "application/xml")
        connection.setRequestProperty("User-Agent", "Binario/0.20 authorized DB Timetables client")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("DB Timetables HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://apis.deutschebahn.com/db-api-marketplace/apis/timetables/v1"
    }
}

/**
 * Canonical station board backed by DB Timetables (Sollfahrplan + full changes).
 * Intended for authenticated/gateway use; not part of LocalOnlyRailDataPolicy
 * until credentials are provisioned securely.
 */
class DbTimetablesStationBoardAdapter(
    private val fetcher: DbTimetablesFetcher,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
) : StationBoardProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Deutsche Bahn — Timetables",
        capabilities = setOf(
            ProviderCapability.STATION_BOARD,
            ProviderCapability.TRAIN_RUN_STATIC,
            ProviderCapability.TRAIN_RUN_REALTIME
        ),
        coverage = CoverageDescriptor(
            countryCodes = setOf("DE", "AT", "CH", "IT"),
            description = "DB station timetables and known changes, including international trains visible at DB-served stations"
        ),
        accessMode = AccessMode.AGREEMENT_REQUIRED,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), maxConcurrentRequests = 2, defaultTimeToLive = Duration.ofSeconds(30)),
        schemaVersion = 1
    )

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
        val stationName = request.stationName?.takeIf(String::isNotBlank)
            ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.INVALID_REQUEST, "DB Timetables richiede il nome della stazione."))
        return try {
            val eva = resolveEva(stationName)
                ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Stazione non trovata nel catalogo DB Timetables."))
            val fromLocal = request.from.atZone(zone)
            val toLocal = request.to.atZone(zone)
            val hourSlices = generateSequence(fromLocal.withMinute(0).withSecond(0).withNano(0)) { it.plusHours(1) }
                .takeWhile { !it.isAfter(toLocal) }
                .toList()
            val plannedStops = hourSlices.flatMap { slice ->
                parseTimetable(
                    fetcher.planned(eva, "%02d%02d%02d".format(slice.year % 100, slice.monthValue, slice.dayOfMonth), "%02d".format(slice.hour)),
                    request.kind,
                    changes = false
                )
            }.associateBy { it.id }
            val changes = parseTimetable(fetcher.fullChanges(eva), request.kind, changes = true).associateBy { it.id }
            val now = clock.instant()
            val source = SourceRef(
                sourceId = SOURCE_ID,
                nativeId = eva,
                observedAt = now,
                fetchedAt = now,
                expiresAt = now.plusSeconds(30),
                confidence = Confidence.INFRASTRUCTURE_PRIMARY,
                licenseId = "CC BY 4.0 — Deutsche Bahn AG",
                traceId = "db:$eva:${now.epochSecond}"
            )
            val entries = plannedStops.values.mapNotNull { planned ->
                val changed = changes[planned.id]
                val scheduledTime = planned.time ?: return@mapNotNull null
                val effective = changed?.time ?: scheduledTime
                if (scheduledTime.toInstant().isBefore(request.from) || scheduledTime.toInstant().isAfter(request.to)) return@mapNotNull null
                val cancelled = changed?.statusCode == "c"
                val expectedValue = changed?.time?.takeIf { it != scheduledTime }?.let { SourcedValue(it, source.copy(nativeId = planned.id)) }
                val status = when {
                    cancelled -> RunStatus.CANCELLED
                    effective.isAfter(scheduledTime) -> RunStatus.DELAYED
                    changed != null -> RunStatus.ON_TIME
                    else -> RunStatus.SCHEDULED
                }
                val pathChanged = changed?.path?.takeIf { it.isNotEmpty() && it != planned.path }
                val routeChange = pathChanged?.let {
                    SourcedValue(
                        OperationalRouteChange(
                            kind = RouteChangeKind.DIVERTED,
                            summary = "DB segnala un percorso operativo differente dal Sollfahrplan.",
                            viaStopLabels = it.dropLast(1)
                        ),
                        source.copy(nativeId = planned.id)
                    )
                }
                val label = when (request.kind) {
                    BoardKind.DEPARTURES -> (pathChanged ?: planned.path).lastOrNull()
                    BoardKind.ARRIVALS -> (pathChanged ?: planned.path).firstOrNull()
                } ?: planned.line ?: "Destinazione/origine non disponibile"
                StationBoardEntry(
                    runId = TrainRunId("db:${planned.tripId}"),
                    scheduledTime = scheduledTime,
                    expectedTime = expectedValue,
                    destinationOrOrigin = StationId("db-published:${normalize(label)}"),
                    destinationOrOriginLabel = label,
                    publicNumber = planned.number,
                    operatorId = planned.owner?.takeIf(String::isNotBlank)?.let(::OperatorId) ?: OperatorId("db"),
                    platform = (changed?.platform ?: planned.platform)?.takeIf(String::isNotBlank)?.let { SourcedValue(it, source.copy(nativeId = planned.id)) },
                    status = if (status == RunStatus.SCHEDULED) null else SourcedValue(status, source.copy(nativeId = planned.id)),
                    routeChange = routeChange
                )
            }.sortedBy { it.scheduledTime }

            val board = StationBoard(
                stationId = request.stationId,
                kind = request.kind,
                from = request.from,
                to = request.to,
                entries = entries,
                snapshot = SnapshotMeta(
                    schemaVersion = 1,
                    generatedAt = now,
                    validUntil = now.plusSeconds(30),
                    freshness = Freshness.FRESH,
                    completeness = Completeness.PARTIAL,
                    contributingSources = setOf(source)
                )
            )
            ProviderResult.Success(
                board,
                ProviderResponseMeta(SOURCE_ID, now, observedAt = now, expiresAt = now.plusSeconds(30), completeness = Completeness.PARTIAL)
            )
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "DB Timetables non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Risposta DB Timetables non leggibile.", error::class.java.name))
        }
    }

    internal fun resolveEva(xml: ByteArray, requestedName: String): String? {
        val doc = parseXml(xml)
        val stations = doc.getElementsByTagName("station")
        val wanted = normalize(requestedName)
        val candidates = (0 until stations.length).mapNotNull { index ->
            val element = stations.item(index) as? Element ?: return@mapNotNull null
            val name = element.getAttribute("name")
            val eva = element.getAttribute("eva").takeIf(String::isNotBlank) ?: return@mapNotNull null
            Triple(eva, name, similarity(wanted, normalize(name)))
        }
        return candidates.maxByOrNull { it.third }?.takeIf { it.third > 0 }?.first
    }

    private suspend fun resolveEva(name: String): String? = resolveEva(fetcher.findStation(name), name)

    internal fun parseTimetable(xml: ByteArray, kind: BoardKind, changes: Boolean): List<DbStop> {
        val doc = parseXml(xml)
        val nodes = doc.getElementsByTagName("s")
        return (0 until nodes.length).mapNotNull { index ->
            val stop = nodes.item(index) as? Element ?: return@mapNotNull null
            val id = stop.getAttribute("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val tl = stop.getElementsByTagName("tl").item(0) as? Element
            val eventName = if (kind == BoardKind.DEPARTURES) "dp" else "ar"
            val event = stop.getElementsByTagName(eventName).item(0) as? Element ?: return@mapNotNull null
            val timeAttribute = if (changes) "ct" else "pt"
            val platformAttribute = if (changes) "cp" else "pp"
            val pathAttribute = if (changes) "cpth" else "ppth"
            val rawTime = event.getAttribute(timeAttribute).takeIf(String::isNotBlank)
            DbStop(
                id = id,
                tripId = id.substringBeforeLast('-'),
                number = tl?.getAttribute("n")?.takeIf(String::isNotBlank),
                owner = tl?.getAttribute("o")?.takeIf(String::isNotBlank),
                line = event.getAttribute("l").takeIf(String::isNotBlank),
                time = rawTime?.let(::parseDbTime),
                platform = event.getAttribute(platformAttribute).takeIf(String::isNotBlank),
                path = event.getAttribute(pathAttribute).split('|').map(String::trim).filter(String::isNotBlank),
                statusCode = event.getAttribute("cs").takeIf(String::isNotBlank)
            )
        }
    }

    private fun parseDbTime(value: String): ZonedDateTime? {
        if (!value.matches(Regex("\\d{10}"))) return null
        return runCatching {
            LocalDateTime.of(
                2000 + value.substring(0, 2).toInt(),
                value.substring(2, 4).toInt(),
                value.substring(4, 6).toInt(),
                value.substring(6, 8).toInt(),
                value.substring(8, 10).toInt()
            ).atZone(zone)
        }.getOrNull()
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        setExpandEntityReferences(false)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun normalize(value: String): String = value.lowercase()
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
        .replace(Regex("[^a-z0-9]"), "")

    private fun similarity(a: String, b: String): Int = when {
        a == b -> 100
        a.isNotBlank() && (a.contains(b) || b.contains(a)) -> 70
        else -> 0
    }

    internal data class DbStop(
        val id: String,
        val tripId: String,
        val number: String?,
        val owner: String?,
        val line: String?,
        val time: ZonedDateTime?,
        val platform: String?,
        val path: List<String>,
        val statusCode: String?
    )

    companion object {
        val SOURCE_ID = SourceId("db-timetables")
    }
}
