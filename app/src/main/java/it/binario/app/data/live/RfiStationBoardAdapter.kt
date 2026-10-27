package it.binario.app.data.live

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.formatTime
import it.binario.app.data.timeToMinutes
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import java.util.Locale

fun interface RfiMonitorFetcher {
    suspend fun fetch(url: String): String
}

internal class HttpRfiMonitorFetcher : RfiMonitorFetcher {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Il monitor RFI non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.20 (personal rail information client)")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("RFI ha risposto HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("RFI board exceeds size limit")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Adapter live per i tabelloni pubblici RFI. È volutamente limitato alle
 * partenze della stazione di origine: RFI non viene presentato come motore di
 * pianificazione percorso. Ogni risposta è etichettata con provider e orario.
 */
class RfiStationBoardAdapter(
    private val fetcher: RfiMonitorFetcher = HttpRfiMonitorFetcher(),
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val cacheTtlMillis: Long = 60_000L
) : TrainDataSource, StationBoardProvider {
    override val descriptor = ProviderDescriptor(
        id = SourceId("rfi-station-board"),
        displayName = "RFI — Monitor Arrivi/Partenze",
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Tabelloni partenze delle stazioni RFI configurate"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofSeconds(20), defaultTimeToLive = Duration.ofMillis(cacheTtlMillis)),
        schemaVersion = 1
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<Int, LiveBoard>()
    private val lastAttempts = mutableMapOf<Int, Instant>()

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<it.binario.app.data.core.StationBoard> {
        if (request.kind != BoardKind.DEPARTURES) {
            return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "RFI: sono supportate solo le partenze."))
        }
        val stationName = request.stationName?.takeIf(String::isNotBlank) ?: request.stationId.value
        val placeId = withContext(Dispatchers.IO) {
            RfiStationIds.placeId(Station(request.stationId.value, stationName, "", aliases = request.stationAliases))
        }
            ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Stazione RFI non configurata."))
        return try {
            val live = liveBoard(placeId, stationName)
            val fetchedAt = live.fetchedAt
            val services = live.services
            val board = LegacyStationBoardInterop.build(
                sourceId = descriptor.id,
                confidence = Confidence.INFRASTRUCTURE_PRIMARY,
                stationId = request.stationId,
                kind = request.kind,
                from = request.from,
                to = request.to,
                services = services,
                fetchedAt = fetchedAt,
                ttl = descriptor.ratePolicy.defaultTimeToLive,
                observedAt = live.observedAt,
                expiresAt = live.expiresAt
            )
            ProviderResult.Success(
                board,
                ProviderResponseMeta(
                    providerId = descriptor.id,
                    fetchedAt = fetchedAt,
                    observedAt = live.observedAt,
                    expiresAt = live.expiresAt
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Il tabellone RFI non è raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Il tabellone RFI non è leggibile.", error::class.java.name))
        }
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = clock.instant().atZone(clock.zone)
        return loadRoute(route, direction, JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0)))
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        if (query.serviceDate != clock.instant().atZone(clock.zone).toLocalDate()) {
            return DataResult.Failure("Il monitor RFI espone stato operativo corrente: la fonte live viene usata solo per la data odierna.")
        }
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val placeId = withContext(Dispatchers.IO) { RfiStationIds.placeId(origin) }
            ?: return DataResult.Failure("Il tabellone RFI non è ancora configurato per ${origin.name}.")
        return try {
            val live = liveBoard(placeId, origin.name)
            val exactMatches = live.services.filter { service ->
                (destination.matchesRfiStop(service.destination) || service.stops.drop(1).any { destination.matchesRfiStop(it.station) }) &&
                    service.scheduledDepartureDateTime?.let { scheduled ->
                        scheduled.toLocalDate() == query.serviceDate &&
                            scheduled.plusMinutes(service.delayMinutes.toLong()).isAfter(query.requestedAt.minusMinutes(5))
                    } == true
            }
            if (exactMatches.isEmpty()) {
                return DataResult.Failure("RFI non mostra una partenza da ${origin.name} con fermata a ${destination.name} nel tabellone corrente.")
            }
            DataResult.Success(RouteSnapshot(
                route = route,
                direction = direction,
                services = exactMatches,
                providerName = "RFI — Monitor Arrivi/Partenze live",
                retrievedAtEpochMillis = live.fetchedAt.toEpochMilli(),
                expiresAtEpochMillis = live.expiresAt.toEpochMilli(),
                freshness = Freshness.FRESH,
                sourceObservedAtEpochMillis = live.observedAt.toEpochMilli(),
                confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
                notice = "Tabellone live RFI: corse da ${origin.name} con fermata a ${destination.name}. Le previsioni riguardano la partenza dalla stazione selezionata."
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            DataResult.Failure("Il tabellone RFI non è raggiungibile: ${error.message ?: "errore di rete"}.")
        } catch (error: Exception) {
            DataResult.Failure("Il tabellone RFI ha restituito dati non leggibili: ${error.message ?: "errore di parsing"}.")
        }
    }

    internal fun parseDepartures(
        html: String,
        origin: String,
        route: SavedRoute,
        direction: Direction,
        observedAt: Instant = clock.instant()
    ): List<TrainService> {
        val document = Jsoup.parse(html)
        return document.select("tr[name=treno]").mapNotNull { row ->
            val number = row.selectFirst("#RTreno")?.text()?.normalizeSpace().orEmpty()
            val destination = row.selectFirst("#RStazione")?.text()?.normalizeSpace().orEmpty()
            val scheduled = row.selectFirst("#ROrario")?.text()?.normalizeTime().orEmpty()
            if (number.isBlank() || destination.isBlank() || scheduled.isBlank()) return@mapNotNull null

            val delayLabel = row.selectFirst("#RRitardo")?.attr("aria-label").orEmpty()
            val delayText = row.selectFirst("#RRitardo")?.text().orEmpty()
            val publishedDelay = parseDelay(delayLabel.ifBlank { delayText })
            val delay = publishedDelay ?: 0
            val statusText = row.text()
            val cancelled = statusText.hasExplicitCancellationStatus()
            val operationalVerified = cancelled || publishedDelay != null
            val status = when {
                cancelled -> ServiceStatus.CANCELLED
                delay > 0 -> ServiceStatus.DELAYED
                else -> ServiceStatus.ON_TIME
            }
            val platform = row.selectFirst("#RBinario")?.text()?.normalizeSpace()?.takeIf {
                it.isNotBlank() && it.uppercase(Locale.ROOT) !in setOf("PF", "N/D", "-")
            }
            val carrier = row.selectFirst("#RVettore img")?.attr("alt").orEmpty()
            val category = row.selectFirst("#RCategoria img")?.attr("alt").orEmpty()
            val info = row.select(".testoinfoaggiuntive").joinToString(" ") { it.text().normalizeSpace() }
            val stopText = row.select(".testoinfoaggiuntive").firstOrNull { it.text().contains("FERMA A:", ignoreCase = true) }
                ?.text()?.substringAfter(":").orEmpty()
            val onwardStops = Regex("(?:^|\\s+-\\s+)(.+?)\\s*\\(([0-2]?\\d):([0-5]\\d)\\)")
                .findAll(stopText).mapNotNull { match ->
                    val hour = match.groupValues[2].toIntOrNull()?.takeIf { it in 0..23 } ?: return@mapNotNull null
                    val time = "%02d:%s".format(Locale.ROOT, hour, match.groupValues[3])
                    TrainStop(match.groupValues[1].trim(), time, "—", null, 0, completed = false)
                }.toList()
            val explicitDate = row.selectFirst("button[id^=btn_]")?.id()?.let {
                Regex("^btn_(\\d{8})").find(it)?.groupValues?.get(1)
            }?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
            val observedLocal = observedAt.atZone(RailTime.defaultZone).toLocalDateTime()
            var scheduledAt = (explicitDate ?: observedLocal.toLocalDate()).atTime(LocalTime.parse(scheduled))
            if (explicitDate == null && scheduledAt.isBefore(observedLocal.minusHours(12))) scheduledAt = scheduledAt.plusDays(1)
            val expected = if (status == ServiceStatus.CANCELLED) scheduled else addDelay(scheduled, delay)
            val routeChange = OperationalRouteChangeDetector.detect(info)
            TrainService(
                id = "rfi:${route.id}:${direction.name}:$number:$scheduledAt",
                number = number,
                operator = operatorFrom(carrier, category, number),
                trainCategory = categoryFrom(category, number),
                origin = origin,
                destination = destination,
                scheduledDeparture = scheduled,
                expectedDeparture = expected,
                platform = platform,
                delayMinutes = delay,
                officialDelayMinutes = publishedDelay,
                status = status,
                stops = listOf(
                    TrainStop(origin, scheduled, expected, platform, delay, completed = false),
                ) + onwardStops.ifEmpty { listOf(TrainStop(destination, "—", "—", null, 0, completed = false)) },
                transferCount = 0,
                operationalStatusVerified = operationalVerified,
                scheduledDepartureDateTime = scheduledAt,
                operationalSourceIds = setOf(descriptor.id.value),
                operationalObservedAtEpochMillis = observedAt.toEpochMilli(),
                routeChange = routeChange,
                routeChangeSourceIds = if (routeChange != null) setOf(descriptor.id.value) else emptySet()
            )
        }
    }

    private fun operatorFrom(carrier: String, category: String, number: String): RailOperator {
        val all = "$carrier $category $number".uppercase(Locale.ROOT)
        return when {
            "ITALO" in all || "NTV" in all -> RailOperator.ITALO
            "TRENORD" in all -> RailOperator.TRENORD
            "FRECC" in all -> RailOperator.TRENITALIA_AV
            "TRENITALIA" in all && ("REG" in all || "RV" in all) -> RailOperator.TRENITALIA_REGIONALE
            "TRENITALIA" in all -> RailOperator.TRENITALIA_LONG_DISTANCE
            else -> RailOperator.OTHER
        }
    }

    private fun categoryFrom(category: String, number: String): TrainCategory {
        val all = "$category $number".uppercase(Locale.ROOT)
        return when {
            "ITALO" in all -> TrainCategory.AV_ITALO
            "FRECC" in all -> TrainCategory.FRECCE
            "INTERCITY" in all || all.startsWith("IC") -> TrainCategory.INTERCITY
            "TRENORD" in all -> TrainCategory.TRENORD_REGIO
            "REGIONALE VELOCE" in all || all.startsWith("RV") -> TrainCategory.REGIONALE_VELOCE
            "REGIONALE" in all || all.startsWith("R") -> TrainCategory.REGIONALE
            else -> TrainCategory.OTHER
        }
    }

    private fun parseDelay(value: String): Int? {
        val normalized = value.uppercase(Locale.ROOT)
        if ("NESSUNO" in normalized || "IN ORARIO" in normalized) return 0
        return Regex("\\b(\\d{1,4})\\b").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..1_440 }
    }

    private fun addDelay(time: String, delay: Int): String = runCatching {
        formatTime(timeToMinutes(time) + delay)
    }.getOrDefault(time)

    private suspend fun liveBoard(placeId: Int, stationName: String): LiveBoard = withContext(Dispatchers.IO) { mutex.withLock {
        val startedAt = clock.instant()
        cache[placeId]?.takeIf { startedAt.isBefore(it.expiresAt) }?.let { return@withLock it }
        lastAttempts[placeId]?.let { attempt ->
            if (startedAt.isBefore(attempt.plus(descriptor.ratePolicy.minimumRequestInterval))) {
                throw IOException("RFI retry interval")
            }
        }
        lastAttempts.entries.removeAll { !startedAt.isBefore(it.value.plusSeconds(120)) }
        lastAttempts[placeId] = startedAt
        val html = fetcher.fetch("https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=$placeId&arrivals=False")
        val document = Jsoup.parse(html)
        val observedAt = parseObservedAt(document.text()) ?: throw IOException("RFI timestamp missing")
        val fetchedAt = clock.instant()
        if (observedAt.isAfter(fetchedAt.plusSeconds(60)) || !observedAt.plusSeconds(120).isAfter(fetchedAt)) {
            throw IOException("RFI board is not current")
        }
        val heading = document.selectFirst("h1")?.text().orEmpty()
        if (heading.isNotBlank() && RfiStationIds.placeId("", heading) != placeId) throw IOException("RFI station mismatch")
        val route = SavedRoute("rfi-board-$placeId", stationName, Station("rfi:$placeId", stationName, stationName), Station("rfi-board-target", "Destinazione", ""))
        val services = parseDepartures(html, stationName, route, Direction.OUTBOUND, observedAt)
        val expiresAt = minOf(fetchedAt.plusMillis(cacheTtlMillis), observedAt.plusSeconds(120))
        LiveBoard(services, observedAt, fetchedAt, expiresAt).also {
            // The cache is bounded even while browsing many stations.
            cache.entries.removeAll { entry -> !fetchedAt.isBefore(entry.value.expiresAt) }
            if (cache.size >= 16) cache.remove(cache.keys.first())
            cache[placeId] = it
        }
    } }

    private fun parseObservedAt(text: String): Instant? {
        val match = Regex("aggiornato il\\s+(\\d{2}/\\d{2}/\\d{4})\\s+alle ore\\s+(\\d{2}:\\d{2}:\\d{2})", RegexOption.IGNORE_CASE).find(text) ?: return null
        return runCatching {
            LocalDateTime.parse("${match.groupValues[1]} ${match.groupValues[2]}", DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss"))
                .atZone(RailTime.defaultZone).toInstant()
        }.getOrNull()
    }

    private data class LiveBoard(val services: List<TrainService>, val observedAt: Instant, val fetchedAt: Instant, val expiresAt: Instant)
}

private fun String.normalizeSpace(): String = replace(Regex("\\s+"), " ").trim()
private fun String.normalizeTime(): String = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(this)?.value ?: ""
