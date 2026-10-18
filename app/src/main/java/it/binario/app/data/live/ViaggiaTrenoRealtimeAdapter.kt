package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.RunStatus
import it.binario.app.data.repository.TrainNumberSearchProvider
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberMatch
import it.binario.app.data.repository.StationBoardRunDetailProvider
import it.binario.app.data.repository.StationBoardRunDetails
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
        require(path.startsWith("partenze/") || path.startsWith("arrivi/") || path.startsWith("andamentoTreno/") || path.startsWith("cercaNumeroTrenoTrenoAutocomplete/"))
        var url = URL("https://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path")
        val requestedPath = url.path
        repeat(4) { attempt ->
            val connection = openConnection(url)
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept", if (path.startsWith("cercaNumeroTrenoTrenoAutocomplete/")) "text/plain" else "application/json")
            connection.setRequestProperty("User-Agent", "Binario/0.20 (personal realtime client)")
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("ViaggiaTreno redirect senza destinazione")
                    var target = try { URL(url, location) } catch (_: java.net.MalformedURLException) {
                        throw IOException("ViaggiaTreno redirect non leggibile")
                    }
                    // The public legacy API is served over HTTP. The exception is
                    // limited to the exact requested resource on its official www host.
                    val publicLegacyApi = target.protocol == "http" && target.host == "www.viaggiatreno.it" &&
                        target.port in setOf(-1, 80) && target.userInfo == null && target.path == requestedPath &&
                        target.query == null && target.ref == null
                    if (target.protocol == "http" && target.host == "www.viaggiatreno.it" &&
                        target.port in setOf(-1, 80) && target.userInfo == null &&
                        url.host == "www.viaggiatreno.it" && !publicLegacyApi) {
                        target = URL("https", "viaggiatreno.it", url.file)
                    }
                    val knownHttps = target.protocol == "https" &&
                        target.host.lowercase(Locale.ROOT) in setOf("www.viaggiatreno.it", "viaggiatreno.it") &&
                        target.port in setOf(-1, 443) && target.userInfo == null && target.path == requestedPath &&
                        target.query == null && target.ref == null
                    if (!publicLegacyApi && !knownHttps) {
                        throw IOException("ViaggiaTreno redirect non consentito da ${url.host}: ${target.protocol}://${target.host}${target.path.take(220)}")
                    }
                    if (attempt == 3) throw IOException("ViaggiaTreno: troppi reindirizzamenti")
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
) : RealtimeRouteDataSource, StationBoardProvider, StationBoardRunDetailProvider, TrainNumberSearchProvider {
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

    private suspend fun json(path: String): String = cache.getOrLoad(path, expireFromLoadStart = true) {
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
            // Ask for the selected time first. A source may return a bounded board;
            // starting two hours earlier must not hide the user's upcoming trains.
            val boardRows = departures(originCode, requested).toMutableList()
            if (!requested.isAfter(fetched.plusSeconds(600))) {
                val earlier = maxOf(requested.minusSeconds(2 * 3600), query.serviceDate.atStartOfDay(zone).toInstant())
                if (earlier < requested) {
                    try { boardRows.addAll(departures(originCode, earlier)) }
                    catch (error: CancellationException) { throw error }
                    catch (_: IOException) { /* Optional lookback must not erase the current board. */ }
                    catch (_: org.json.JSONException) { }
                }
            }
            val rows = boardRows.distinctBy { Triple(it.text("codOrigine"), it.text("numeroTreno"), it.millis("dataPartenzaTreno") ?: it.millis("millisDataPartenza")) }
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
        if (stationCode == null) return ProviderResult.Failure(
            ProviderError(ProviderErrorKind.NOT_SUPPORTED, "ViaggiaTreno richiede un codice stazione."))
        val fetched = clock.instant()
        return try {
            val arrival = request.kind == BoardKind.ARRIVALS
            val rows = movements(stationCode, request.from, request.kind)
            val services = rows.mapNotNull { row ->
                val scheduled = row.millis(if (arrival) "orarioArrivo" else "orarioPartenza")?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
                val number = row.text("numeroTreno") ?: return@mapNotNull null
                val observed = row.millis("ultimoRilev")?.let(Instant::ofEpochMilli)?.takeIf { recent(it, fetched) }
                val delay = row.intOrNull("ritardo")
                val cancelled = row.intOrNull("provvedimento") == 1
                val verified = cancelled || (observed != null && delay != null && !row.optBoolean("nonPartito", false))
                TrainService(
                    id = "viaggiatreno:$stationCode:$number:${scheduled.toEpochMilli()}", number = number,
                    operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.OTHER,
                    origin = request.stationName.orEmpty(), destination = row.text(if (arrival) "origine" else "destinazione").orEmpty(),
                    scheduledDeparture = time(scheduled), expectedDeparture = time(scheduled.plusSeconds(if (verified) (delay ?: 0) * 60L else 0)),
                    platform = row.text(if (arrival) "binarioEffettivoArrivoDescrizione" else "binarioEffettivoPartenzaDescrizione"), delayMinutes = if (verified) delay ?: 0 else 0,
                    officialDelayMinutes = if (verified) delay else null,
                    status = if (cancelled) ServiceStatus.CANCELLED else if (verified && (delay ?: 0) > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
                    stops = emptyList(), operationalStatusVerified = verified, scheduledDepartureDateTime = scheduled.atZone(zone).toLocalDateTime(),
                    operationalObservedAtEpochMillis = if (verified) observed?.toEpochMilli() ?: fetched.toEpochMilli() else null
                )
            }
            val identities = rows.mapNotNull { row ->
                val number = row.text("numeroTreno") ?: return@mapNotNull null
                val origin = code(row.text("codOrigine")) ?: return@mapNotNull null
                val date = row.millis("dataPartenzaTreno") ?: row.millis("millisDataPartenza") ?: return@mapNotNull null
                val scheduled = row.millis(if (arrival) "orarioArrivo" else "orarioPartenza") ?: return@mapNotNull null
                "$number:$scheduled" to "$origin:$number:$date"
            }.toMap()
            val board = LegacyStationBoardInterop.build(descriptor.id, Confidence.OPERATOR_PRIMARY, request.stationId, request.kind,
                request.from, request.to, services, fetched, Duration.ofSeconds(30))
            ProviderResult.Success(
                board.copy(entries = board.entries.map { entry ->
                    entry.copy(nativeRunIds = identities["${entry.publicNumber}:${entry.scheduledTime.toInstant().toEpochMilli()}"]
                        ?.let { mapOf(descriptor.id to it) }.orEmpty())
                }),
                ProviderResponseMeta(descriptor.id, fetched, expiresAt = fetched.plusSeconds(30))
            )
        } catch (error: CancellationException) { throw error }
        catch (_: IOException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "ViaggiaTreno non è raggiungibile.")) }
        catch (_: org.json.JSONException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "ViaggiaTreno non è leggibile.")) }
    }

    private suspend fun departures(stationCode: String, from: Instant): List<JSONObject> = movements(stationCode, from, BoardKind.DEPARTURES)

    private suspend fun movements(stationCode: String, from: Instant, kind: BoardKind): List<JSONObject> {
        val local = from.atZone(zone).withSecond(0).withNano(0)
        val date = local.format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.ENGLISH))
        val resource = if (kind == BoardKind.ARRIVALS) "arrivi" else "partenze"
        val body = json("$resource/$stationCode/${URLEncoder.encode(date, "UTF-8").replace("+", "%20")}")
        if (body.trim() == "null") return emptyList()
        val array = JSONArray(body)
        return (0 until minOf(array.length(), 2000)).mapNotNull(array::optJSONObject)
    }

    override suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>> = withContext(Dispatchers.Default) {
        val fetched = clock.instant()
        val meta = ProviderResponseMeta(descriptor.id, fetched, expiresAt = fetched.plusSeconds(30))
        val number = query.numeric?.takeIf { it.matches(Regex("[0-9]{1,6}")) }
            ?: return@withContext ProviderResult.Success(emptyList(), meta)
        try {
            val references = json("cercaNumeroTrenoTrenoAutocomplete/$number").lineSequence().take(200)
                .mapNotNull { line -> Regex("([0-9]{1,6})-(S[0-9]{5})-([0-9]{10,16})")
                    .matchEntire(line.substringAfter('|', "").trim()) }
                .filter { query.matches(it.groupValues[1]) }.map { it.groupValues.drop(1) }.distinct().take(24).toList()
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val matches = coroutineScope {
                references.map { (publicNumber, origin, nativeDate) -> async {
                    val date = nativeDate.toLong()
                    val serviceDay = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                    if (serviceDay < query.date.minusDays(2) || serviceDay > query.date) return@async null
                    try {
                        val run = JSONObject(json("andamentoTreno/$origin/$publicNumber/$date"))
                        val rows = run.optJSONArray("fermate") ?: return@async null
                        val first = rows.optJSONObject(0) ?: return@async null
                        val scheduled = (first.millis("partenza_teorica") ?: first.millis("programmata"))?.let(Instant::ofEpochMilli) ?: return@async null
                        val last = rows.optJSONObject(rows.length() - 1) ?: return@async null
                        val end = (last.millis("arrivo_teorico") ?: last.millis("programmata"))?.let(Instant::ofEpochMilli) ?: scheduled
                        if (scheduled.atZone(zone).toLocalDate() > query.date || end.atZone(zone).toLocalDate() < query.date) return@async null
                        val firstCode = first.text("id") ?: return@async null
                        val entry = StationBoardEntry(it.binario.app.data.core.TrainRunId("viaggiatreno:$origin:$publicNumber:$date"),
                            scheduled.atZone(zone), destinationOrOrigin = it.binario.app.data.core.StationId("viaggiatreno:${last.text("id")}"),
                            destinationOrOriginLabel = last.text("stazione"), publicNumber = publicNumber,
                            nativeRunIds = mapOf(descriptor.id to "$origin:$publicNumber:$date"))
                        val detail = parseBoardRun(run, origin, publicNumber, date, firstCode, BoardKind.DEPARTURES, entry, fetched) ?: return@async null
                        val observed = run.millis("oraUltimoRilevamento")?.let(Instant::ofEpochMilli)?.takeIf { recent(it, fetched) }
                        val cancelled = run.intOrNull("provvedimento") == 1 || run.text("tipoTreno") == "ST"
                        val delay = run.intOrNull("ritardo")
                        val verified = cancelled || (observed != null && delay != null && !run.optBoolean("nonPartito", false))
                        val category = run.text("categoria").orEmpty().uppercase(Locale.ROOT)
                        val brand = run.text("compImpresa")
                        val service = TrainService(
                            id = entry.runId.value, number = publicNumber,
                            operator = if (brand?.contains("trenord", true) == true) RailOperator.TRENORD else if (category.startsWith("IC")) RailOperator.TRENITALIA_LONG_DISTANCE
                                else if (category.startsWith("F")) RailOperator.TRENITALIA_AV else RailOperator.TRENITALIA_REGIONALE,
                            trainCategory = if (category.startsWith("IC")) TrainCategory.INTERCITY else if (category.startsWith("F")) TrainCategory.FRECCE else TrainCategory.REGIONALE,
                            origin = detail.origin, destination = detail.destination, scheduledDeparture = time(scheduled),
                            expectedDeparture = detail.entry.expectedTime?.value?.toInstant()?.let(::time) ?: time(scheduled),
                            platform = detail.entry.platform?.value, delayMinutes = if (verified) delay ?: 0 else 0,
                            officialDelayMinutes = delay.takeIf { verified },
                            status = if (cancelled) ServiceStatus.CANCELLED else if (verified && (delay ?: 0) > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
                            stops = detail.stops, operatorDisplayName = brand, operationalStatusVerified = verified,
                            scheduledDepartureDateTime = scheduled.atZone(zone).toLocalDateTime(),
                            operationalSourceIds = if (verified) setOf(descriptor.id.value) else emptySet(),
                            operationalObservedAtEpochMillis = observed?.toEpochMilli().takeIf { verified }
                        )
                        TrainNumberMatch.create(service, descriptor.displayName, DataConfidence.OPERATOR_PRIMARY, fetched.toEpochMilli(), fetched.plusSeconds(30).toEpochMilli())
                    } catch (error: CancellationException) { throw error }
                    catch (_: IOException) { failed.set(true); null }
                    catch (_: org.json.JSONException) { failed.set(true); null }
                } }.awaitAll().filterNotNull()
            }
            if (failed.get()) ProviderResult.Partial(matches, setOf(ProviderWarning.INCOMPLETE_COVERAGE), meta)
            else ProviderResult.Success(matches, meta)
        } catch (error: CancellationException) { throw error }
        catch (_: IOException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Ricerca ViaggiaTreno non raggiungibile.")) }
        catch (_: org.json.JSONException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Risposta ViaggiaTreno non leggibile.")) }
    }

    override suspend fun getStationBoardRun(station: Station, kind: BoardKind, entry: StationBoardEntry): ProviderResult<StationBoardRunDetails> =
        withContext(Dispatchers.Default) {
            fun unavailable() = ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Dettagli della corsa non disponibili dalla fonte."))
            val stationCode = code(station.railwayCode) ?: return@withContext unavailable()
            if (entry.operatorId?.value?.lowercase(Locale.ROOT)?.let { it !in setOf("trenitalia", "trenord", "tilo") } == true) return@withContext unavailable()
            try {
                val native = entry.nativeRunIds[descriptor.id] ?: movements(stationCode, entry.scheduledTime.toInstant().minusSeconds(60), kind)
                    .singleOrNull { row ->
                        row.text("numeroTreno") == entry.publicNumber &&
                            row.millis(if (kind == BoardKind.ARRIVALS) "orarioArrivo" else "orarioPartenza") == entry.scheduledTime.toInstant().toEpochMilli()
                    }?.let { row ->
                        val origin = code(row.text("codOrigine")) ?: return@let null
                        val date = row.millis("dataPartenzaTreno") ?: row.millis("millisDataPartenza") ?: return@let null
                        "$origin:${row.text("numeroTreno")}:$date"
                    } ?: return@withContext unavailable()
                val identity = Regex("(S[0-9]{5}):([0-9]{1,6}):([0-9]{10,16})").matchEntire(native) ?: return@withContext unavailable()
                val (origin, number, date) = identity.destructured
                if (number != entry.publicNumber) return@withContext unavailable()
                val fetched = clock.instant()
                val run = JSONObject(json("andamentoTreno/$origin/$number/$date"))
                val details = parseBoardRun(run, origin, number, date.toLong(), stationCode, kind, entry, fetched)
                    ?: return@withContext unavailable()
                ProviderResult.Success(details, ProviderResponseMeta(descriptor.id, fetched, details.observedAt, fetched.plusSeconds(30)))
            } catch (error: CancellationException) { throw error }
            catch (_: IOException) { unavailable() }
            catch (_: org.json.JSONException) { unavailable() }
        }

    internal fun parseBoardRun(run: JSONObject, origin: String, number: String, date: Long, stationCode: String,
        kind: BoardKind, entry: StationBoardEntry, fetched: Instant): StationBoardRunDetails? {
        if (run.text("numeroTreno") != number || run.text("idOrigine") != origin ||
            run.millis("dataPartenzaTreno")?.let { it != date } == true) return null
        val calls = run.optJSONArray("fermate") ?: return null
        val rows = (0 until calls.length()).mapNotNull(calls::optJSONObject)
        val arrival = kind == BoardKind.ARRIVALS
        val selected = rows.singleOrNull { row ->
            row.text("id") == stationCode &&
                (row.millis(if (arrival) "arrivo_teorico" else "partenza_teorica") ?: row.millis("programmata")) == entry.scheduledTime.toInstant().toEpochMilli()
        } ?: return null
        val observed = run.millis("oraUltimoRilevamento")?.let(Instant::ofEpochMilli)?.takeIf { recent(it, fetched) }
        val cancelled = run.intOrNull("provvedimento") == 1 || run.text("tipoTreno") == "ST"
        val delay = run.intOrNull("ritardo")
        val verified = observed != null && delay != null && !run.optBoolean("nonPartito", false)
        val source = SourceRef(sourceId = descriptor.id, nativeId = "$origin:$number:$date", observedAt = observed,
            fetchedAt = fetched, expiresAt = fetched.plusSeconds(30), confidence = Confidence.OPERATOR_PRIMARY, traceId = "viaggiatreno:detail:$origin:$number:$date")
        val actual = selected.millis(if (arrival) "arrivoReale" else "partenzaReale")?.let(Instant::ofEpochMilli)
        val updatedExpected = actual ?: if (verified && !cancelled) entry.scheduledTime.toInstant().plusSeconds(delay!! * 60L) else null
        val platform = selected.text(if (arrival) "binarioEffettivoArrivoDescrizione" else "binarioEffettivoPartenzaDescrizione")
        val stops = rows.mapIndexedNotNull { index, row ->
            val scheduled = (if (index == 0) row.millis("partenza_teorica") else row.millis("arrivo_teorico"))
                ?: row.millis("programmata") ?: return@mapIndexedNotNull null
            val actualStop = row.millis(if (index == 0) "partenzaReale" else "arrivoReale")?.let(Instant::ofEpochMilli)
            val at = Instant.ofEpochMilli(scheduled)
            val stopVerified = actualStop != null || (verified && !cancelled)
            TrainStop(station = row.text("stazione") ?: return@mapIndexedNotNull null,
                scheduled = time(at), expected = time(actualStop ?: at.plusSeconds(if (stopVerified) (delay ?: 0) * 60L else 0)),
                platform = row.text(if (index == 0) "binarioEffettivoPartenzaDescrizione" else "binarioEffettivoArrivoDescrizione"),
                delayMinutes = actualStop?.let { Duration.between(at, it).toMinutes().toInt() } ?: if (stopVerified) delay ?: 0 else 0,
                completed = actualStop != null, operationalStatusVerified = stopVerified,
                routeChange = if (row.intOrNull("actualFermataType") in setOf(2, 3)) StopRouteChange.SKIPPED else StopRouteChange.NONE)
        }
        if (stops.isEmpty()) return null
        return StationBoardRunDetails(
            entry.copy(expectedTime = updatedExpected?.let { SourcedValue(it.atZone(zone), source) },
                platform = platform?.let { SourcedValue(it, source) } ?: entry.platform,
                status = if (cancelled) SourcedValue(RunStatus.CANCELLED, source) else if (verified || actual != null)
                    SourcedValue(if (updatedExpected != null && updatedExpected > entry.scheduledTime.toInstant()) RunStatus.DELAYED else RunStatus.ON_TIME, source) else null),
            stops.first().station, stops.last().station, stops, descriptor.displayName, observed
        )
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
                delayMinutes = actual?.let { Duration.between(at, Instant.ofEpochMilli(it)).toMinutes().toInt() } ?: visibleDelay,
                completed = actual != null, operationalStatusVerified = actual != null || (verified && !cancelled)
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
