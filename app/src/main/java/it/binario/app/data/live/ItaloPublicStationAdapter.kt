package it.binario.app.data.live

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.formatTime
import it.binario.app.data.timeToMinutes
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
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
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.util.Locale

fun interface ItaloPublicStationFetcher {
    suspend fun fetch(slug: String): String
}

internal class HttpItaloPublicStationFetcher : ItaloPublicStationFetcher {
    override suspend fun fetch(slug: String): String = withContext(Dispatchers.IO) {
        val connection = URL("${ItaloPublicStationAdapter.BASE_URL}/it/stazione/$slug").openConnection() as? HttpURLConnection
            ?: throw IOException("Italo in Viaggio non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
        try {
            if (connection.responseCode !in 200..299) throw IOException("Italo in Viaggio ha risposto HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Tabellone pubblico Italo in Viaggio. Non utilizza area personale, vendita,
 * token BIG né endpoint delle app ufficiali. L’HTML dinamico è best-effort:
 * un risultato vuoto lascia che il repository prosegua con altri provider.
 */
class ItaloPublicStationAdapter(
    private val fetcher: ItaloPublicStationFetcher = HttpItaloPublicStationFetcher(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cacheTtlMillis: Long = 60_000L
) : TrainDataSource, StationBoardProvider {
    override val descriptor = ProviderDescriptor(
        id = SourceId("italo-status"),
        displayName = "Italo in Viaggio",
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Tabelloni pubblici delle stazioni Italo configurate"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMillis(cacheTtlMillis)),
        schemaVersion = 1
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedSnapshot>()

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<it.binario.app.data.core.StationBoard> {
        if (request.kind != BoardKind.DEPARTURES) {
            return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Italo: sono supportate solo le partenze."))
        }
        val slug = ItaloStationSlugs.slug(request.stationId.value)
            ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Stazione Italo non configurata."))
        val stationName = request.stationName?.takeIf(String::isNotBlank) ?: request.stationId.value
        val fetchedAt = clock.instant()
        return try {
            val html = fetcher.fetch(slug)
            val syntheticRoute = SavedRoute(
                id = "station-board:\${request.stationId.value}",
                name = stationName,
                outwardOrigin = Station(request.stationId.value, stationName, stationName),
                outwardDestination = Station("station-board-target", "Destinazione", "")
            )
            val services = parseStationBoard(html, stationName, syntheticRoute, Direction.OUTBOUND)
            val board = LegacyStationBoardInterop.build(
                sourceId = descriptor.id,
                confidence = Confidence.OPERATOR_PRIMARY,
                stationId = request.stationId,
                kind = request.kind,
                from = request.from,
                to = request.to,
                services = services,
                fetchedAt = fetchedAt,
                ttl = descriptor.ratePolicy.defaultTimeToLive
            )
            ProviderResult.Success(
                board,
                ProviderResponseMeta(
                    providerId = descriptor.id,
                    fetchedAt = fetchedAt,
                    observedAt = fetchedAt,
                    expiresAt = fetchedAt.plus(descriptor.ratePolicy.defaultTimeToLive)
                )
            )
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Italo in Viaggio non è raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Italo in Viaggio non è leggibile.", error::class.java.name))
        }
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = clock.instant().atZone(clock.zone)
        return loadRoute(route, direction, JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0)))
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        if (query.serviceDate != clock.instant().atZone(clock.zone).toLocalDate()) {
            return DataResult.Failure("Italo in Viaggio espone stato operativo corrente: la fonte live viene usata solo per la data odierna.")
        }
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val slug = ItaloStationSlugs.slug(origin.id)
            ?: return DataResult.Failure("Italo in Viaggio non è ancora configurato per ${origin.name}.")
        val now = clock.millis()
        val key = "${origin.id}:${destination.id}:${direction.name}"
        mutex.withLock {
            cache[key]?.takeIf { now < it.expiresAtEpochMillis }?.let { return DataResult.Success(it.snapshot) }
        }
        return try {
            val html = fetcher.fetch(slug)
            val services = parseStationBoard(html, origin.name, route, direction)
            if (services.isEmpty()) {
                return DataResult.Failure("Italo in Viaggio non mostra corse Italo leggibili da ${origin.name} in questo momento.")
            }
            val exactMatches = services.filter { stationMatches(it.destination, destination.name) }
            if (exactMatches.isEmpty()) {
                return DataResult.Failure("Italo in Viaggio non mostra una partenza diretta da ${origin.name} verso ${destination.name} nel tabellone corrente.")
            }
            val snapshot = RouteSnapshot(
                route = route,
                direction = direction,
                services = exactMatches,
                providerName = "Italo in Viaggio — tabellone pubblico",
                retrievedAtEpochMillis = now,
                expiresAtEpochMillis = now + cacheTtlMillis,
                freshness = Freshness.FRESH,
                sourceObservedAtEpochMillis = now,
                confidence = DataConfidence.OPERATOR_PRIMARY,
                notice = "Dati live Italo: partenze da ${origin.name} verso ${destination.name}."
            )
            mutex.withLock { cache[key] = CachedSnapshot(snapshot, now + cacheTtlMillis) }
            DataResult.Success(snapshot)
        } catch (error: IOException) {
            DataResult.Failure("Italo in Viaggio non è raggiungibile: ${error.message ?: "errore di rete"}.")
        } catch (error: Exception) {
            DataResult.Failure("Italo in Viaggio ha restituito dati non leggibili: ${error.message ?: "errore di parsing"}.")
        }
    }

    internal fun parseStationBoard(
        html: String,
        origin: String,
        route: SavedRoute,
        direction: Direction
    ): List<TrainService> {
        val document = Jsoup.parse(html)
        val candidates = document.select("[data-train-number], [data-train], article, tr, li")
            .filter { element ->
                val text = element.text().compactItalo()
                element.hasAttr("data-train-number") || element.hasAttr("data-train") || text.contains("italo", ignoreCase = true)
            }
        return candidates.mapNotNull { element -> serviceFromElement(element, origin, route, direction) }
            .distinctBy { it.id }
    }

    private fun serviceFromElement(element: Element, origin: String, route: SavedRoute, direction: Direction): TrainService? {
        val text = element.text().compactItalo()
        val number = element.attr("data-train-number").ifBlank { element.attr("data-train") }
            .ifBlank { Regex("\\b(?:italo\\s*)?(\\d{3,5})\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1).orEmpty() }
        val destination = element.attr("data-destination").ifBlank {
            Regex("\\b(?:per|destinazione)\\s*[:\\-]?\\s*([A-Za-zÀ-ÿ' .-]+?)(?=\\s+(?:ore|orario|partenza|arrivo|binario|ritardo)\\b|$)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.compactItalo().orEmpty()
        }
        val scheduled = element.attr("data-scheduled-time").ifBlank {
            Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(text)?.value.orEmpty()
        }
        if (number.isBlank() || destination.isBlank() || scheduled.isBlank()) return null
        val delay = Regex("\\britardo\\s*(?:di)?\\s*(\\d{1,3})\\s*(?:minuti|min)?", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val status = when {
            Regex("soppress|cancell", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ServiceStatus.CANCELLED
            delay > 0 -> ServiceStatus.DELAYED
            else -> ServiceStatus.ON_TIME
        }
        val platform = element.attr("data-platform").ifBlank {
            Regex("\\bbinario\\s*[:#-]?\\s*([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
        }.takeIf(String::isNotBlank)
        val expected = if (status == ServiceStatus.CANCELLED) scheduled else addDelay(scheduled, delay)
        return TrainService(
            id = "italo:${route.id}:${direction.name}:$number:$scheduled",
            number = number,
            operator = RailOperator.ITALO,
            trainCategory = TrainCategory.AV_ITALO,
            origin = origin,
            destination = destination,
            scheduledDeparture = scheduled,
            expectedDeparture = expected,
            platform = platform,
            delayMinutes = delay,
            officialDelayMinutes = delay,
            status = status,
            stops = listOf(
                TrainStop(origin, scheduled, expected, platform, delay, completed = false),
                TrainStop(destination, "—", "—", null, delay, completed = false)
            ),
            transferCount = 0
        )
    }

    private fun addDelay(time: String, delay: Int): String = runCatching { formatTime(timeToMinutes(time) + delay) }.getOrDefault(time)
    private fun stationMatches(actual: String, expected: String): Boolean {
        val normalizedActual = actual.normalizedItaloStation()
        val normalizedExpected = expected.normalizedItaloStation()
        return normalizedActual == normalizedExpected || normalizedActual.contains(normalizedExpected) || normalizedExpected.contains(normalizedActual)
    }
    private data class CachedSnapshot(val snapshot: RouteSnapshot, val expiresAtEpochMillis: Long)

    companion object {
        const val BASE_URL = "https://italoinviaggio.italotreno.com"
    }
}

internal object ItaloStationSlugs {
    private val values = mapOf(
        "roma-termini" to "roma-termini",
        "roma-tiburtina" to "roma-tiburtina",
        "napoli-centrale" to "napoli-centrale",
        "firenze-smn" to "firenze-santa-maria-novella",
        "milano-centrale" to "milano-centrale",
        "torino-pn" to "torino-porta-nuova",
        "venezia-sl" to "venezia-santa-lucia"
    )
    fun slug(stationId: String): String? = values[stationId]
}

private fun String.compactItalo(): String = replace(Regex("\\s+"), " ").trim()
private fun String.normalizedItaloStation(): String = uppercase(Locale.ROOT).replace("S. ", "SAN ").replace(Regex("[^A-Z0-9]"), "")
