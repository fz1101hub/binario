package it.binario.app.data.live

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
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
import java.util.Locale

fun interface RfiMonitorFetcher {
    suspend fun fetch(url: String): String
}

internal class HttpRfiMonitorFetcher : RfiMonitorFetcher {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Il monitor RFI non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("RFI ha risposto HTTP $status")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Mappa degli identificativi pubblici RFI per le stazioni già offerte dalla UI.
 * Le stazioni non incluse non attivano richieste speculative: la UI restituisce
 * un errore chiaro fino a quando l'identificativo è verificato e aggiunto qui.
 */
internal object RfiStationIds {
    private val byBinarioId = mapOf(
        "roma-termini" to 2416,
        "roma-tiburtina" to 2385,
        "roma-ostiense" to 2379,
        "roma-trastevere" to 2387,
        "roma-san-pietro" to 2383,
        "roma-tuscolana" to 2388,
        "fiumicino-aeroporto" to 1327,
        "ciampino" to 1106,
        "civitavecchia" to 1123,
        "frascati" to 1370,
        "velletri" to 3006,
        "orte" to 1959,
        "latina" to 1539,
        "frosinone" to 1377,
        "cassino" to 955,
        "napoli-centrale" to 1888,
        "bologna-centrale" to 683,
        "milano-centrale" to 1728,
        "genova-pp" to 257,
        "torino-pn" to 2876,
        "bari-centrale" to 595,
        "palermo-centrale" to 2018,
        "cagliari" to 787
    )

    fun placeId(stationId: String): Int? = byBinarioId[stationId]
}

/**
 * Adapter live per i tabelloni pubblici RFI. È volutamente limitato alle
 * partenze della stazione di origine: RFI non viene presentato come motore di
 * pianificazione percorso. Ogni risposta è etichettata con provider e orario.
 */
class RfiStationBoardAdapter(
    private val fetcher: RfiMonitorFetcher = HttpRfiMonitorFetcher(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cacheTtlMillis: Long = 45_000L
) : TrainDataSource {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedSnapshot>()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val placeId = RfiStationIds.placeId(origin.id)
            ?: return DataResult.Failure("Il tabellone RFI non è ancora configurato per ${origin.name}.")
        val key = "${origin.id}:${direction.name}"
        val now = clock.millis()
        mutex.withLock {
            cache[key]?.takeIf { now < it.expiresAtEpochMillis }?.let { return DataResult.Success(it.snapshot) }
        }

        return try {
            val url = "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=$placeId&arrivals=False"
            val html = fetcher.fetch(url)
            val services = parseDepartures(html, origin.name, destination.name, route, direction)
            if (services.isEmpty()) {
                return DataResult.Failure("RFI non mostra partenze utilizzabili da ${origin.name} in questo momento.")
            }
            val exactMatches = services.filter { stationMatches(it.destination, destination.name) }
            val selected = if (exactMatches.isNotEmpty()) exactMatches else services
            val isFiltered = exactMatches.isNotEmpty()
            val snapshot = RouteSnapshot(
                route = route,
                direction = direction,
                services = selected,
                providerName = "RFI — Monitor Arrivi/Partenze live",
                retrievedAtEpochMillis = now,
                expiresAtEpochMillis = now + cacheTtlMillis,
                freshness = Freshness.FRESH,
                sourceObservedAtEpochMillis = now,
                confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
                notice = if (isFiltered) {
                    "Dati live RFI: partenze da ${origin.name} verso ${destination.name}."
                } else {
                    "Dati live RFI: tabellone partenze da ${origin.name}; nessuna corrispondenza diretta per ${destination.name} nel monitor corrente."
                }
            )
            mutex.withLock { cache[key] = CachedSnapshot(snapshot, now + cacheTtlMillis) }
            DataResult.Success(snapshot)
        } catch (error: IOException) {
            DataResult.Failure("Il tabellone RFI non è raggiungibile: ${error.message ?: "errore di rete"}.")
        } catch (error: Exception) {
            DataResult.Failure("Il tabellone RFI ha restituito dati non leggibili: ${error.message ?: "errore di parsing"}.")
        }
    }

    internal fun parseDepartures(
        html: String,
        origin: String,
        requestedDestination: String,
        route: SavedRoute,
        direction: Direction
    ): List<TrainService> {
        val document = Jsoup.parse(html)
        return document.select("tr[name=treno]").mapNotNull { row ->
            val number = row.selectFirst("#RTreno")?.text()?.normalizeSpace().orEmpty()
            val destination = row.selectFirst("#RStazione")?.text()?.normalizeSpace().orEmpty()
            val scheduled = row.selectFirst("#ROrario")?.text()?.normalizeTime().orEmpty()
            if (number.isBlank() || destination.isBlank() || scheduled.isBlank()) return@mapNotNull null

            val delayLabel = row.selectFirst("#RRitardo")?.attr("aria-label").orEmpty()
            val delayText = row.selectFirst("#RRitardo")?.text().orEmpty()
            val delay = parseDelay(delayLabel.ifBlank { delayText })
            val statusText = row.text().uppercase(Locale.ROOT)
            val status = when {
                statusText.contains("SOPPRESS") || statusText.contains("CANCELL") -> ServiceStatus.CANCELLED
                delay > 0 -> ServiceStatus.DELAYED
                else -> ServiceStatus.ON_TIME
            }
            val platform = row.selectFirst("#RBinario")?.text()?.normalizeSpace()?.takeIf {
                it.isNotBlank() && it.uppercase(Locale.ROOT) !in setOf("PF", "N/D", "-")
            }
            val carrier = row.selectFirst("#RVettore img")?.attr("alt").orEmpty()
            val category = row.selectFirst("#RCategoria img")?.attr("alt").orEmpty()
            val info = row.selectFirst(".testoinfoaggiuntive")?.text()?.normalizeSpace().orEmpty()
            val expected = if (status == ServiceStatus.CANCELLED) scheduled else addDelay(scheduled, delay)
            TrainService(
                id = "rfi:${route.id}:${direction.name}:$number:$scheduled",
                number = number,
                operator = operatorFrom(carrier, category, number),
                trainCategory = categoryFrom(category, number),
                origin = origin,
                destination = destination,
                scheduledDeparture = scheduled,
                expectedDeparture = expected,
                platform = platform,
                delayMinutes = delay,
                status = status,
                stops = listOf(
                    TrainStop(origin, scheduled, expected, platform, delay, completed = false),
                    TrainStop(destination, "—", "—", null, delay, completed = false)
                ),
                transferCount = 0
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

    private fun parseDelay(value: String): Int {
        val normalized = value.uppercase(Locale.ROOT)
        if (normalized.isBlank() || "NESSUNO" in normalized || "IN ORARIO" in normalized) return 0
        return Regex("(\\d{1,3})").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun addDelay(time: String, delay: Int): String = runCatching {
        formatTime(timeToMinutes(time) + delay)
    }.getOrDefault(time)

    private fun stationMatches(actual: String, expected: String): Boolean {
        val normalizedActual = actual.normalizedStationName()
        val normalizedExpected = expected.normalizedStationName()
        return normalizedActual == normalizedExpected || normalizedActual.contains(normalizedExpected) || normalizedExpected.contains(normalizedActual)
    }

    private data class CachedSnapshot(val snapshot: RouteSnapshot, val expiresAtEpochMillis: Long)
}

private fun String.normalizeSpace(): String = replace(Regex("\\s+"), " ").trim()
private fun String.normalizeTime(): String = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(this)?.value ?: ""
private fun String.normalizedStationName(): String = uppercase(Locale.ROOT)
    .replace("S. ", "SAN ")
    .replace(Regex("[^A-Z0-9]"), "")
