package it.binario.app.data.planned

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.matchesPublishedName
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.util.Locale

fun interface RfiProgrammedTimetableFetcher {
    suspend fun fetch(url: String): String
}

internal class HttpRfiProgrammedTimetableFetcher : RfiProgrammedTimetableFetcher {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Il quadro orario RFI non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.20 (personal rail timetable client)")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("RFI ha risposto HTTP $status")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

internal object RfiProgrammedStationIds {
    private val byLegacyId = mapOf(
        "roma-termini" to 2247,
        "roma-tiburtina" to 2217,
        "roma-ostiense" to 2211,
        "milano-centrale" to 1603,
        "napoli-centrale" to 1757,
        "bologna-centrale" to 635,
        "firenze-smn" to 1325,
        "torino-pn" to 2672,
        "torino-ps" to 25350269,
        "venezia-sl" to 2796,
        "verona-pn" to 2812,
        "genova-pp" to 248,
        "bari-centrale" to 550,
        "salerno" to 2434
    )

    private val byName = mapOf(
        key("Roma Termini") to 2247,
        key("Roma Tiburtina") to 2217,
        key("Roma Ostiense") to 2211,
        key("Milano Centrale") to 1603,
        key("Napoli Centrale") to 1757,
        key("Bologna Centrale") to 635,
        key("Firenze S.M.N.") to 1325,
        key("Firenze Santa Maria Novella") to 1325,
        key("Torino Porta Nuova") to 2672,
        key("Torino Porta Susa") to 25350269,
        key("Venezia Santa Lucia") to 2796,
        key("Venezia S. Lucia") to 2796,
        key("Verona Porta Nuova") to 2812,
        key("Genova Piazza Principe") to 248,
        key("Genova P. Principe") to 248,
        key("Bari Centrale") to 550,
        key("Salerno") to 2434
    )

    fun timetableId(station: Station): Int? = byLegacyId[station.id]
        ?: (listOf(station.name) + station.aliases).firstNotNullOfOrNull { byName[key(it)] }

    private fun key(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace("S. ", "SAN ")
        .replace(Regex("[^A-Z0-9]"), "")
}

/**
 * National planned journey source backed by RFI's official station timetable.
 * This is schedule data only: operational state is reconciled separately.
 */
class RfiProgrammedTimetableAdapter(
    private val fetcher: RfiProgrammedTimetableFetcher = HttpRfiProgrammedTimetableFetcher(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cacheTtlMillis: Long = 6L * 60L * 60L * 1000L
) : TrainDataSource {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedTimetable>()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = clock.instant().atZone(clock.zone)
        return loadRoute(route, direction, JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0)))
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val stationId = RfiProgrammedStationIds.timetableId(origin)
            ?: return DataResult.Failure("Il quadro orario programmato RFI non è ancora mappato per ${origin.name}.")

        val key = "${origin.id}:${query.serviceDate}"
        val now = clock.millis()
        val parsed = mutex.withLock {
            cache[key]?.takeIf { now < it.expiresAtEpochMillis }?.timetable
        } ?: run {
            val url = "https://prm.rfi.it/qo_prm/QO_Partenze_SiPMR.aspx?Id=$stationId&lin=it&dalle=00.00&alle=23.59&ora=00.00"
            val html = try {
                fetcher.fetch(url)
            } catch (error: IOException) {
                return DataResult.Failure("Il quadro orario programmato RFI non è raggiungibile: ${error.message ?: "errore di rete"}.")
            } catch (error: Exception) {
                return DataResult.Failure("Il quadro orario programmato RFI non è leggibile: ${error.message ?: "errore di parsing"}.")
            }
            val timetable = parseTimetable(html, origin, route, direction)
            mutex.withLock { cache[key] = CachedTimetable(timetable, now + cacheTtlMillis) }
            timetable
        }

        parsed.validity?.let { validity ->
            if (query.serviceDate !in validity.start..validity.end) {
                return DataResult.Failure(
                    "Il quadro orario RFI disponibile è valido dal ${validity.start} al ${validity.end}, non per ${query.serviceDate}."
                )
            }
        }

        val requestedMinutes = query.departureTime.hour * 60 + query.departureTime.minute
        val services = parsed.services.asSequence()
            .filter { service -> runsOnDate(service.periodicity, query.serviceDate) }
            .filter { service -> timeMinutes(service.service.scheduledDeparture) >= requestedMinutes }
            .filter { service ->
                service.service.stops.drop(1).any { destination.matchesPublishedName(it.station) } ||
                    destination.matchesPublishedName(service.service.destination)
            }
            .map { parsedService ->
                val targetIndex = parsedService.service.stops.indexOfFirst { destination.matchesPublishedName(it.station) }
                val relevantStops = if (targetIndex >= 1) {
                    parsedService.service.stops.take(targetIndex + 1)
                } else {
                    parsedService.service.stops
                }
                parsedService.service.copy(
                    stops = relevantStops,
                    scheduledDepartureDateTime = LocalDateTime.of(
                        query.serviceDate,
                        parseTime(parsedService.service.scheduledDeparture) ?: query.departureTime
                    )
                )
            }
            .sortedBy { it.scheduledDepartureDateTime }
            .take(24)
            .toList()

        if (services.isEmpty()) {
            return DataResult.Failure(
                "RFI non mostra corse programmate dirette da ${origin.name} verso ${destination.name} dopo ${query.departureTime}."
            )
        }

        return DataResult.Success(
            RouteSnapshot(
                route = route,
                direction = direction,
                services = services,
                providerName = "RFI — Quadro orario programmato",
                retrievedAtEpochMillis = now,
                expiresAtEpochMillis = now + cacheTtlMillis,
                freshness = Freshness.AGING,
                sourceObservedAtEpochMillis = now,
                confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
                notice = "Orario programmato ufficiale RFI; ritardi, binari reali e variazioni operative vengono riconciliati separatamente con le fonti realtime."
            )
        )
    }

    internal fun parseTimetable(
        html: String,
        origin: Station,
        route: SavedRoute,
        direction: Direction
    ): ParsedTimetable {
        val document = Jsoup.parse(html)
        val validity = parseValidity(document.text())
        val services = buildList {
            document.select("table").forEach { table ->
                val rows = table.select("tr")
                if (rows.isEmpty()) return@forEach
                val headerRow = rows.firstOrNull { row -> row.select("th").isNotEmpty() }
                    ?: rows.firstOrNull { row -> row.text().contains("Fermate successive", ignoreCase = true) }
                val headers = headerRow?.select("th,td")?.map { normalizeHeader(elementText(it)) }.orEmpty()
                val indexes = HeaderIndexes.from(headers)
                rows.forEach { row -> parseRow(row, indexes, origin, route, direction)?.let(::add) }
            }
        }.distinctBy { "${it.service.number}:${it.service.scheduledDeparture}:${it.service.destination}" }

        return ParsedTimetable(validity, services)
    }

    private fun parseRow(
        row: Element,
        indexes: HeaderIndexes,
        origin: Station,
        route: SavedRoute,
        direction: Direction
    ): ParsedService? {
        val cells = row.select("td")
        if (cells.size < 2) return null

        fun cell(index: Int?, selectors: String): Element? =
            index?.let(cells::getOrNull) ?: row.selectFirst(selectors)

        val trainCell = cell(indexes.train, "[id*=Treno],[class*=treno],[headers*=treno]")
            ?: cells.firstOrNull { Regex("\\b\\d{2,5}\\b").containsMatchIn(elementText(it)) }
            ?: return null
        val trainText = elementText(trainCell)
        val number = Regex("\\b(\\d{2,5})\\b").find(trainText)?.groupValues?.getOrNull(1) ?: return null

        val timeCell = cell(indexes.time, "[id*=Orario],[class*=orario],[headers*=orario]")
        val scheduled = normalizeTime(elementText(timeCell))
            .ifBlank { cells.map { normalizeTime(elementText(it)) }.firstOrNull { it.isNotBlank() }.orEmpty() }
        if (scheduled.isBlank()) return null

        val destinationCell = cell(indexes.destination, "[id*=Destin],[class*=destin],[headers*=destin]")
        val destinationText = elementText(destinationCell)
        val destination = destinationText.substringBefore("(").trim().ifBlank { "Destinazione non indicata" }

        val stopsCell = cell(indexes.stops, "[id*=Fermate],[class*=fermate],[headers*=fermate]")
        val successiveStops = parseStops(elementText(stopsCell))
        val finalDestination = successiveStops.lastOrNull()?.station?.ifBlank { null }
            ?: destination.takeIf { it != "Destinazione non indicata" }
            ?: return null

        val periodicity = normalizeHeader(elementText(cell(indexes.periodicity, "[id*=Period],[class*=period],[headers*=period]")))
        val carrier = elementText(cell(indexes.carrier, "[id*=Impresa],[class*=impresa],[headers*=impresa]"))
        val categoryText = "$trainText $carrier"
        val operator = operatorFrom(categoryText)
        val category = categoryFrom(categoryText, operator)

        val stops = buildList {
            add(TrainStop(origin.name, scheduled, scheduled, null, 0, completed = false))
            if (successiveStops.isNotEmpty()) {
                successiveStops.forEach { stop ->
                    add(TrainStop(stop.station, stop.time, stop.time, null, 0, completed = false))
                }
            } else if (!origin.matchesPublishedName(finalDestination)) {
                add(TrainStop(finalDestination, "—", "—", null, 0, completed = false))
            }
        }

        return ParsedService(
            service = TrainService(
                id = "rfi-programmed:${route.id}:${direction.name}:$number:$scheduled",
                number = number,
                operator = operator,
                trainCategory = category,
                origin = origin.name,
                destination = finalDestination,
                scheduledDeparture = scheduled,
                expectedDeparture = scheduled,
                platform = null,
                delayMinutes = 0,
                officialDelayMinutes = null,
                status = ServiceStatus.ON_TIME,
                stops = stops,
                transferCount = 0,
                operationalStatusVerified = false
            ),
            periodicity = periodicity
        )
    }

    private fun parseStops(value: String): List<ProgrammedStop> {
        val normalized = compactText(value)
        if (normalized.isBlank()) return emptyList()
        val regex = Regex("([A-Za-zÀ-ÿ0-9 .'/’–-]{2,80}?)\\s*\\((\\d{1,2}[.:]\\d{2})\\)")
        return regex.findAll(normalized).mapNotNull { match ->
            val station = match.groupValues[1]
                .substringAfterLast(";")
                .substringAfterLast("|")
                .trim(' ', '-', '–', ',', ';')
            val time = match.groupValues[2].replace('.', ':').padStart(5, '0')
            station.takeIf { it.isNotBlank() }?.let { ProgrammedStop(it, time) }
        }.toList()
    }

    private fun parseValidity(text: String): TimetableValidity? {
        val match = Regex(
            "(?i)(\\d{1,2})\\s+([A-Za-zÀ-ÿ]+)\\s+(20\\d{2})\\s*[-–]\\s*(\\d{1,2})\\s+([A-Za-zÀ-ÿ]+)\\s+(20\\d{2})"
        ).find(text) ?: return null
        val startMonth = italianMonth(match.groupValues[2]) ?: return null
        val endMonth = italianMonth(match.groupValues[5]) ?: return null
        return runCatching {
            TimetableValidity(
                LocalDate.of(match.groupValues[3].toInt(), startMonth, match.groupValues[1].toInt()),
                LocalDate.of(match.groupValues[6].toInt(), endMonth, match.groupValues[4].toInt())
            )
        }.getOrNull()
    }

    internal fun runsOnDate(periodicity: String, date: LocalDate): Boolean {
        val value = normalizeHeader(periodicity)
        if (value.isBlank()) return true
        if (listOf("GIORNALIERO", "TUTTI I GIORNI", "QUOTIDIANO").any(value::contains)) return true
        val day = date.dayOfWeek
        if ("LUN-VEN" in value || "LUN VEN" in value || "FERIALE" in value) {
            return day.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value
        }
        if ("SAB" in value && day == DayOfWeek.SATURDAY) return true
        if (("DOM" in value || "FEST" in value) && day == DayOfWeek.SUNDAY) return true
        val tokens = mapOf(
            DayOfWeek.MONDAY to listOf("LUN", "LU"),
            DayOfWeek.TUESDAY to listOf("MAR", "MA"),
            DayOfWeek.WEDNESDAY to listOf("MER", "ME"),
            DayOfWeek.THURSDAY to listOf("GIO", "GI"),
            DayOfWeek.FRIDAY to listOf("VEN", "VE"),
            DayOfWeek.SATURDAY to listOf("SAB", "SA"),
            DayOfWeek.SUNDAY to listOf("DOM", "DO")
        )
        val hasAnyDayToken = tokens.values.flatten().any { token -> Regex("\\b$token\\b").containsMatchIn(value) }
        return if (hasAnyDayToken) tokens.getValue(day).any { token -> Regex("\\b$token\\b").containsMatchIn(value) } else true
    }

    private fun operatorFrom(value: String): RailOperator {
        val all = value.uppercase(Locale.ROOT)
        return when {
            "ITALO" in all || "NTV" in all || Regex("\\bITA\\b").containsMatchIn(all) -> RailOperator.ITALO
            "TRENORD" in all -> RailOperator.TRENORD
            "FRECC" in all -> RailOperator.TRENITALIA_AV
            "INTERCITY" in all || Regex("\\bICN?\\b").containsMatchIn(all) -> RailOperator.TRENITALIA_LONG_DISTANCE
            "REGIONALE" in all || Regex("\\bRV?\\b").containsMatchIn(all) -> RailOperator.TRENITALIA_REGIONALE
            else -> RailOperator.OTHER
        }
    }

    private fun categoryFrom(value: String, operator: RailOperator): TrainCategory {
        val all = value.uppercase(Locale.ROOT)
        return when {
            operator == RailOperator.ITALO -> TrainCategory.AV_ITALO
            "FRECC" in all -> TrainCategory.FRECCE
            "INTERCITY" in all || Regex("\\bICN?\\b").containsMatchIn(all) -> TrainCategory.INTERCITY
            "REGIONALE VELOCE" in all || Regex("\\bRV\\b").containsMatchIn(all) -> TrainCategory.REGIONALE_VELOCE
            "REGIONALE" in all || Regex("\\bR\\b").containsMatchIn(all) -> TrainCategory.REGIONALE
            else -> TrainCategory.OTHER
        }
    }

    private fun elementText(element: Element?): String {
        if (element == null) return ""
        val imageLabels = element.select("img[alt]").mapNotNull { image ->
            image.attr("alt").takeIf { alt ->
                alt.isNotBlank() &&
                    !alt.startsWith("Stazione", ignoreCase = true) &&
                    !alt.contains("accessibil", ignoreCase = true)
            }
        }
        return compactText((imageLabels + element.text()).joinToString(" "))
    }

    private fun compactText(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeHeader(value: String): String = compactText(value).uppercase(Locale.ROOT)

    private fun normalizeTime(value: String): String = Regex("\\b([01]?\\d|2[0-3])[.:][0-5]\\d\\b")
        .find(value)?.value?.replace('.', ':')?.padStart(5, '0').orEmpty()

    private fun parseTime(value: String): LocalTime? = runCatching {
        val (hour, minute) = value.split(':').map(String::toInt)
        LocalTime.of(hour, minute)
    }.getOrNull()

    private fun timeMinutes(value: String): Int = parseTime(value)?.let { it.hour * 60 + it.minute } ?: Int.MAX_VALUE

    private fun italianMonth(value: String): Month? = when (value.trim().lowercase(Locale.ITALIAN).take(3)) {
        "gen" -> Month.JANUARY
        "feb" -> Month.FEBRUARY
        "mar" -> Month.MARCH
        "apr" -> Month.APRIL
        "mag" -> Month.MAY
        "giu" -> Month.JUNE
        "lug" -> Month.JULY
        "ago" -> Month.AUGUST
        "set" -> Month.SEPTEMBER
        "ott" -> Month.OCTOBER
        "nov" -> Month.NOVEMBER
        "dic" -> Month.DECEMBER
        else -> null
    }

    internal data class ParsedTimetable(
        val validity: TimetableValidity?,
        val services: List<ParsedService>
    )

    internal data class ParsedService(
        val service: TrainService,
        val periodicity: String
    )

    internal data class TimetableValidity(val start: LocalDate, val end: LocalDate)
    private data class ProgrammedStop(val station: String, val time: String)
    private data class CachedTimetable(val timetable: ParsedTimetable, val expiresAtEpochMillis: Long)

    private data class HeaderIndexes(
        val time: Int?,
        val train: Int?,
        val destination: Int?,
        val stops: Int?,
        val carrier: Int?,
        val periodicity: Int?
    ) {
        companion object {
            fun from(headers: List<String>): HeaderIndexes {
                fun index(vararg terms: String): Int? = headers.indexOfFirst { header -> terms.any(header::contains) }
                    .takeIf { it >= 0 }
                return HeaderIndexes(
                    time = index("ORARIO", "ORA"),
                    train = index("TRENO"),
                    destination = index("DESTINAZIONE", "STAZIONE DI ARRIVO", "LOCALITA DI ARRIVO", "LOCALITÀ DI ARRIVO"),
                    stops = index("FERMATE SUCCESSIVE"),
                    carrier = index("IMPRESA DI TRASPORTO", "IMPRESA"),
                    periodicity = index("PERIODICITA", "PERIODICITÀ")
                )
            }
        }
    }
}
