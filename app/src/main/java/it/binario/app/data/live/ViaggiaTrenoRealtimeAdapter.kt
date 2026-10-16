package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.planned.BoundedSingleFlightCache
import it.binario.app.data.planned.readBoundedRfiHtml
import it.binario.app.data.provider.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

fun interface ViaggiaTrenoRealtimeFetcher {
    suspend fun fetch(path: String): String
}

internal class HttpViaggiaTrenoRealtimeFetcher(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : ViaggiaTrenoRealtimeFetcher {
    override suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        require(path.startsWith("partenze/") || path.startsWith("andamentoTreno/"))
        var url = URL("https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path")
        repeat(4) { attempt ->
            val connection = openConnection(url)
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Binario/0.20 (personal realtime client)")
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("ViaggiaTreno redirect senza destinazione")
                    val target = try { URL(url, location) } catch (_: java.net.MalformedURLException) {
                        throw IOException("ViaggiaTreno redirect non leggibile")
                    }
                    if (target.protocol != "https" || target.host.lowercase(Locale.ROOT) !in setOf("www.viaggiatreno.it", "viaggiatreno.it") ||
                        target.port !in setOf(-1, 443) || target.userInfo != null ||
                        !target.path.startsWith("/infomobilita/resteasy/viaggiatreno/")) {
                        throw IOException("ViaggiaTreno redirect non consentito: ${target.protocol}://${target.host}${target.path.take(220)}")
                    }
                    if (attempt == 3) throw IOException("ViaggiaTreno: troppi reindirizzamenti HTTPS")
                    url = target
                } else {
                    if (status == 204) return@withContext "null"
                    if (status !in 200..299) throw IOException("ViaggiaTreno HTTP $status")
                    return@withContext connection.inputStream.use {
                        readBoundedRfiHtml(it, connection.contentLengthLong.takeIf { n -> n >= 0 })
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("ViaggiaTreno: destinazione non raggiunta")
    }
}

/** Personal best-effort source. Matches commercial stops by code, not the train's final destination. */
class ViaggiaTrenoRealtimeAdapter(
    private val fetcher: ViaggiaTrenoRealtimeFetcher = HttpViaggiaTrenoRealtimeFetcher(),
    private val clock: Clock = Clock.system(RailTime.defaultZone)
) : TrainDataSource, StationBoardProvider {
    override val descriptor = ProviderDescriptor(
        id = SourceId("viaggiatreno"), displayName = "Trenitalia — ViaggiaTreno realtime",
        capabilities = setOf(ProviderCapability.STATION_BOARD, ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Corse Trenitalia con codici stazione e fermate pubblicate"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofSeconds(30)), schemaVersion = 1
    )
    private val zone = RailTime.defaultZone
    // Cache failures for the cooldown too, without representing them as an empty board.
    private val cache = BoundedSingleFlightCache<Result<String>>(128, 30_000, clock::millis)
    private val requests = Semaphore(3)

    private suspend fun json(path: String): String = cache.getOrLoad(path) {
        try { Result.success(requests.withPermit { fetcher.fetch(path) }) }
        catch (error: CancellationException) { throw error }
        catch (error: IOException) { Result.failure(error) }
    }.value.getOrThrow()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult =
        withContext(Dispatchers.Default) { loadCurrentRoute(route, direction, query) }

    private suspend fun loadCurrentRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        if (query.serviceDate != clock.instant().atZone(zone).toLocalDate()) {
            return DataResult.Failure("ViaggiaTreno realtime è disponibile soltanto per la giornata corrente.", RouteFailureReason.RouteNotCovered)
        }
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val originCode = code(origin.railwayCode)
        val destinationCode = code(destination.railwayCode)
        if (originCode == null || destinationCode == null) return DataResult.Failure("Codici stazione ViaggiaTreno mancanti.", RouteFailureReason.RouteNotCovered)
        val fetched = clock.instant()
        return try {
            val requested = query.requestedAt.atZone(zone).toInstant()
            val rows = departures(originCode, maxOf(requested.minusSeconds(2 * 3600), query.serviceDate.atStartOfDay(zone).toInstant()))
                .filter { row -> row.millis("orarioPartenza")?.let { it + (row.intOrNull("ritardo") ?: 0).coerceAtLeast(0) * 60_000L >= requested.toEpochMilli() } ?: true }
                .sortedBy { it.millis("orarioPartenza") ?: Long.MAX_VALUE }
            val services = coroutineScope {
                rows.take(12).map { row -> async {
                    val number = row.text("numeroTreno")?.takeIf { it.matches(Regex("[0-9]{1,6}")) } ?: return@async null
                    val runOrigin = code(row.text("codOrigine")) ?: return@async null
                    val runDate = row.millis("dataPartenzaTreno") ?: row.millis("millisDataPartenza") ?: return@async null
                    try {
                        val detail = JSONObject(json("andamentoTreno/$runOrigin/$number/$runDate"))
                        parseRun(detail, number, runOrigin, runDate, route, direction, query, fetched)
                    } catch (error: CancellationException) { throw error }
                    catch (_: IOException) { null }
                    catch (_: org.json.JSONException) { null }
                } }.awaitAll().filterNotNull()
            }.distinctBy { it.id }.sortedBy { it.scheduledDepartureDateTime }
            if (services.isEmpty()) return DataResult.Failure("Nessuna corsa live verificabile per la tratta richiesta.")
            val observed = services.mapNotNull { it.operationalObservedAtEpochMillis }.maxOrNull()
            val snapshot = RouteSnapshot(
                route = route, direction = direction, services = services, providerName = descriptor.displayName,
                retrievedAtEpochMillis = fetched.toEpochMilli(), expiresAtEpochMillis = fetched.plusSeconds(30).toEpochMilli(),
                sourceObservedAtEpochMillis = observed ?: fetched.toEpochMilli(),
                freshness = if (observed != null) Freshness.FRESH else Freshness.AGING,
                confidence = DataConfidence.OPERATOR_PRIMARY,
                notice = "Dati ViaggiaTreno aggiornati dalla fonte. Gli orari attesi sono calcolati dal ritardo pubblicato; senza rilevazione recente lo stato operativo resta non verificato."
            )
            val quality = SnapshotQualityAssessor.assess(snapshot, clock.millis())
            DataResult.Success(snapshot.copy(qualityScore = quality.score, qualityBand = quality.band, qualityWarnings = quality.warnings))
        } catch (error: CancellationException) { throw error }
        catch (error: IOException) { DataResult.Failure("ViaggiaTreno non è raggiungibile: ${error.message}", RouteFailureReason.NetworkUnavailable) }
        catch (_: org.json.JSONException) { DataResult.Failure("ViaggiaTreno ha restituito dati non leggibili.") }
    }

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<it.binario.app.data.core.StationBoard> =
        withContext(Dispatchers.Default) { stationBoard(request) }

    private suspend fun stationBoard(request: StationBoardRequest): ProviderResult<it.binario.app.data.core.StationBoard> {
        val stationCode = code(request.railwayCode)
        if (stationCode == null || request.kind != BoardKind.DEPARTURES) return ProviderResult.Failure(
            ProviderError(ProviderErrorKind.NOT_SUPPORTED, "ViaggiaTreno richiede un codice stazione e un tabellone partenze."))
        val fetched = clock.instant()
        return try {
            val services = departures(stationCode, request.from).mapNotNull { row ->
                val scheduled = row.millis("orarioPartenza")?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
                val number = row.text("numeroTreno") ?: return@mapNotNull null
                val observed = row.millis("ultimoRilev")?.let(Instant::ofEpochMilli)?.takeIf { recent(it, fetched) }
                val delay = row.intOrNull("ritardo")
                val cancelled = row.intOrNull("provvedimento") == 1
                val verified = cancelled || (observed != null && delay != null && !row.optBoolean("nonPartito", false))
                TrainService(
                    id = "viaggiatreno:$stationCode:$number:${scheduled.toEpochMilli()}", number = number,
                    operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.OTHER,
                    origin = request.stationName.orEmpty(), destination = row.text("destinazione").orEmpty(),
                    scheduledDeparture = time(scheduled), expectedDeparture = time(scheduled.plusSeconds(if (verified) (delay ?: 0) * 60L else 0)),
                    platform = row.text("binarioEffettivoPartenzaDescrizione"), delayMinutes = if (verified) delay ?: 0 else 0,
                    officialDelayMinutes = if (verified) delay else null,
                    status = if (cancelled) ServiceStatus.CANCELLED else if (verified && (delay ?: 0) > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
                    stops = emptyList(), operationalStatusVerified = verified, scheduledDepartureDateTime = scheduled.atZone(zone).toLocalDateTime(),
                    operationalObservedAtEpochMillis = if (verified) observed?.toEpochMilli() ?: fetched.toEpochMilli() else null
                )
            }
            ProviderResult.Success(
                LegacyStationBoardInterop.build(descriptor.id, Confidence.OPERATOR_PRIMARY, request.stationId, request.kind,
                    request.from, request.to, services, fetched, Duration.ofSeconds(30)),
                ProviderResponseMeta(descriptor.id, fetched, expiresAt = fetched.plusSeconds(30))
            )
        } catch (error: CancellationException) { throw error }
        catch (_: IOException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "ViaggiaTreno non è raggiungibile.")) }
        catch (_: org.json.JSONException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "ViaggiaTreno non è leggibile.")) }
    }

    private suspend fun departures(stationCode: String, from: Instant): List<JSONObject> {
        val local = from.atZone(zone).withSecond(0).withNano(0)
        val date = local.format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.ENGLISH))
        val body = json("partenze/$stationCode/${URLEncoder.encode(date, "UTF-8").replace("+", "%20")}")
        if (body.trim() == "null") return emptyList()
        val array = JSONArray(body)
        return (0 until minOf(array.length(), 2000)).mapNotNull(array::optJSONObject)
    }

    internal fun parseRun(run: JSONObject, number: String, runOrigin: String, runDate: Long,
        route: SavedRoute, direction: Direction, query: JourneyQuery, fetched: Instant): TrainService? {
        if (run.text("numeroTreno") != number || run.text("idOrigine") != runOrigin) return null
        if (run.millis("dataPartenzaTreno")?.let { it != runDate } == true) return null
        val rows = run.optJSONArray("fermate") ?: return null
        val stops = (0 until rows.length()).mapNotNull(rows::optJSONObject)
        val start = stops.indexOfFirst { it.text("id") == route.origin(direction).railwayCode }
        val end = stops.indexOfFirst { it.text("id") == route.destination(direction).railwayCode }
        if (start < 0 || end <= start) return null
        val segment = stops.subList(start, end + 1)
        val cancelled = run.intOrNull("provvedimento") == 1 || run.text("tipoTreno") == "ST"
        if (!cancelled && segment.any { it.intOrNull("actualFermataType") == 2 || it.intOrNull("actualFermataType") == 3 }) return null
        val boarding = segment.first()
        val departure = (boarding.millis("partenza_teorica") ?: boarding.millis("programmata"))?.let(Instant::ofEpochMilli) ?: return null
        val requested = query.requestedAt.atZone(zone).toInstant()
        val observed = run.millis("oraUltimoRilevamento")?.let(Instant::ofEpochMilli)?.takeIf { recent(it, fetched) }
        val delay = run.intOrNull("ritardo")
        val verified = cancelled || (observed != null && delay != null && !run.optBoolean("nonPartito", false))
        val publishedDelay = if (verified) delay else null
        val visibleDelay = publishedDelay ?: 0
        val actualDeparture = boarding.millis("partenzaReale")?.let(Instant::ofEpochMilli)
        val expectedDeparture = actualDeparture ?: departure.plusSeconds(visibleDelay * 60L)
        if (expectedDeparture.isBefore(requested) || expectedDeparture.isAfter(requested.plusSeconds(8 * 3600))) return null
        val trainStops = segment.mapIndexed { index, stop ->
            val scheduled = (if (index == 0) stop.millis("partenza_teorica") else stop.millis("arrivo_teorico"))
                ?: stop.millis("programmata") ?: return null
            val actual = if (index == 0) stop.millis("partenzaReale") else stop.millis("arrivoReale")
            val at = Instant.ofEpochMilli(scheduled)
            TrainStop(
                station = if (index == 0) route.origin(direction).name else if (index == segment.lastIndex) route.destination(direction).name else stop.text("stazione") ?: return null,
                scheduled = time(at), expected = time(actual?.let(Instant::ofEpochMilli) ?: at.plusSeconds(visibleDelay * 60L)),
                platform = stop.text(if (index == 0) "binarioEffettivoPartenzaDescrizione" else "binarioEffettivoArrivoDescrizione"),
                delayMinutes = visibleDelay, completed = actual != null
            )
        }
        val category = run.text("categoria").orEmpty().uppercase(Locale.ROOT)
        return TrainService(
            id = "viaggiatreno:$runOrigin:$number:$runDate:${route.origin(direction).railwayCode}", number = number,
            operator = if (category.startsWith("IC")) RailOperator.TRENITALIA_LONG_DISTANCE else if (category.startsWith("FR")) RailOperator.TRENITALIA_AV else RailOperator.TRENITALIA_REGIONALE,
            trainCategory = if (category.startsWith("IC")) TrainCategory.INTERCITY else if (category.startsWith("FR")) TrainCategory.FRECCE else TrainCategory.REGIONALE,
            origin = route.origin(direction).name, destination = route.destination(direction).name,
            scheduledDeparture = time(departure), expectedDeparture = trainStops.first().expected,
            platform = trainStops.first().platform, delayMinutes = visibleDelay, officialDelayMinutes = publishedDelay,
            status = if (cancelled) ServiceStatus.CANCELLED else if (visibleDelay > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
            stops = trainStops, operationalStatusVerified = verified,
            scheduledDepartureDateTime = departure.atZone(zone).toLocalDateTime(),
            operationalSourceIds = if (verified) setOf(descriptor.id.value) else emptySet(),
            operationalObservedAtEpochMillis = if (verified) observed?.toEpochMilli() ?: fetched.toEpochMilli() else null
        )
    }

    private fun recent(observed: Instant, fetched: Instant) = !observed.isBefore(fetched.minusSeconds(300)) && !observed.isAfter(fetched.plusSeconds(60))
    private fun code(value: String?) = value?.takeIf { it.matches(Regex("S[0-9]{5}")) }
    private fun time(value: Instant) = value.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    private fun JSONObject.text(key: String): String? = opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    private fun JSONObject.millis(key: String): Long? = text(key)?.toLongOrNull()?.takeIf { it > 0 }
    private fun JSONObject.intOrNull(key: String): Int? = text(key)?.toIntOrNull()
}
