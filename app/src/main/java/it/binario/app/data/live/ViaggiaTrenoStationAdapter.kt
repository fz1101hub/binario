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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale

fun interface ViaggiaTrenoStationFetcher {
    suspend fun fetch(stationName: String): String
}

internal class HttpViaggiaTrenoStationFetcher : ViaggiaTrenoStationFetcher {
    override suspend fun fetch(stationName: String): String = withContext(Dispatchers.IO) {
        val form = "stazione=${URLEncoder.encode(stationName, StandardCharsets.UTF_8.name())}&lang=IT"
        val connection = (URL("http://www.viaggiatreno.it/vt_pax_internet/mobile/stazione").openConnection() as? HttpURLConnection)
            ?: throw IOException("ViaggiaTreno non usa una connessione HTTP")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("ViaggiaTreno ha risposto HTTP $status")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Tabellone live Trenitalia da ViaggiaTreno per la stazione di origine.
 * Restituisce soltanto dati mostrati dalla pagina pubblica e non inventa
 * itinerari: la destinazione richiesta viene applicata come filtro quando
 * presente nel tabellone, altrimenti viene segnalato il tabellone completo.
 */
class ViaggiaTrenoStationAdapter(
    private val fetcher: ViaggiaTrenoStationFetcher = HttpViaggiaTrenoStationFetcher(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cacheTtlMillis: Long = 60_000L
) : TrainDataSource {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedSnapshot>()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val key = "${origin.id}:${direction.name}"
        val now = clock.millis()
        mutex.withLock {
            cache[key]?.takeIf { now < it.expiresAtEpochMillis }?.let { return DataResult.Success(it.snapshot) }
        }

        return try {
            val html = fetcher.fetch(origin.name)
            val services = parseStationBoard(html, origin.name, route, direction)
            if (services.isEmpty()) {
                return DataResult.Failure("ViaggiaTreno non mostra partenze utilizzabili da ${origin.name} in questo momento.")
            }
            val exactMatches = services.filter { stationMatches(it.destination, destination.name) }
            val selected = if (exactMatches.isNotEmpty()) exactMatches else services
            val snapshot = RouteSnapshot(
                route = route,
                direction = direction,
                services = selected,
                providerName = "Trenitalia — ViaggiaTreno live",
                retrievedAtEpochMillis = now,
                expiresAtEpochMillis = now + cacheTtlMillis,
                freshness = Freshness.FRESH,
                sourceObservedAtEpochMillis = now,
                confidence = DataConfidence.OPERATOR_PRIMARY,
                notice = if (exactMatches.isNotEmpty()) {
                    "Dati live ViaggiaTreno: partenze da ${origin.name} verso ${destination.name}."
                } else {
                    "Dati live ViaggiaTreno: tabellone partenze da ${origin.name}; nessuna corrispondenza diretta per ${destination.name} nel monitor corrente."
                }
            )
            mutex.withLock { cache[key] = CachedSnapshot(snapshot, now + cacheTtlMillis) }
            DataResult.Success(snapshot)
        } catch (error: IOException) {
            DataResult.Failure("ViaggiaTreno non è raggiungibile: ${error.message ?: "errore di rete"}.")
        } catch (error: Exception) {
            DataResult.Failure("ViaggiaTreno ha restituito dati non leggibili: ${error.message ?: "errore di parsing"}.")
        }
    }

    internal fun parseStationBoard(
        html: String,
        origin: String,
        route: SavedRoute,
        direction: Direction
    ): List<TrainService> {
        val document = Jsoup.parse(html)
        return document.select("div.bloccorisultato").mapNotNull { block ->
            val heading = block.selectFirst("h2")?.text()?.compact().orEmpty()
            val number = Regex("\\d{1,6}").find(heading)?.value.orEmpty()
            val content = block.selectFirst("div.bloccotreno")?.text()?.compact().orEmpty()
            val destination = Regex("\\bPer\\s+(.+?)\\s+\\bDelle ore\\b", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.getOrNull(1)?.compact()?.stationDisplayName().orEmpty()
            val scheduled = Regex("\\bDelle ore\\s+(\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.getOrNull(1).orEmpty()
            if (number.isBlank() || destination.isBlank() || scheduled.isBlank()) return@mapNotNull null

            val delay = Regex("\\britardo\\s+(\\d{1,3})\\s+minuti", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val status = when {
                content.contains("soppress", ignoreCase = true) || content.contains("cancell", ignoreCase = true) -> ServiceStatus.CANCELLED
                delay > 0 -> ServiceStatus.DELAYED
                else -> ServiceStatus.ON_TIME
            }
            val platform = Regex("\\bBinario\\s+Reale:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.getOrNull(1)
            val expected = if (status == ServiceStatus.CANCELLED) scheduled else addDelay(scheduled, delay)
            TrainService(
                id = "viaggiatreno:${route.id}:${direction.name}:$number:$scheduled",
                number = number,
                operator = operatorFrom(heading),
                trainCategory = categoryFrom(heading),
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

    private fun operatorFrom(heading: String): RailOperator = when {
        heading.startsWith("FR", ignoreCase = true) -> RailOperator.TRENITALIA_AV
        heading.startsWith("IC", ignoreCase = true) -> RailOperator.TRENITALIA_LONG_DISTANCE
        heading.startsWith("REG", ignoreCase = true) || heading.startsWith("RV", ignoreCase = true) -> RailOperator.TRENITALIA_REGIONALE
        else -> RailOperator.TRENITALIA_LONG_DISTANCE
    }

    private fun categoryFrom(heading: String): TrainCategory = when {
        heading.startsWith("FR", ignoreCase = true) -> TrainCategory.FRECCE
        heading.startsWith("IC", ignoreCase = true) -> TrainCategory.INTERCITY
        heading.startsWith("RV", ignoreCase = true) -> TrainCategory.REGIONALE_VELOCE
        heading.startsWith("REG", ignoreCase = true) -> TrainCategory.REGIONALE
        else -> TrainCategory.OTHER
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

private fun String.compact(): String = replace(Regex("\\s+"), " ").trim()
private fun String.normalizedStationName(): String = uppercase(Locale.ROOT)
    .replace("S. ", "SAN ")
    .replace(Regex("[^A-Z0-9]"), "")

private fun String.stationDisplayName(): String = lowercase(Locale.ROOT)
    .split(" ")
    .filter(String::isNotBlank)
    .joinToString(" ") { token -> token.replaceFirstChar { it.titlecase(Locale.ROOT) } }
