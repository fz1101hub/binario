package it.binario.app.data.planned

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.isNamedBy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.util.Locale

fun interface RfiProgrammedTimetableFetcher {
    suspend fun fetch(url: String): String
}

internal const val RFI_PROGRAMMED_TIMETABLE_MAX_BYTES = 2 * 1024 * 1024

internal fun readBoundedRfiHtml(
    input: InputStream,
    declaredLength: Long?,
    maxBytes: Int = RFI_PROGRAMMED_TIMETABLE_MAX_BYTES
): String {
    require(maxBytes > 0) { "Il limite RFI deve essere positivo" }
    if (declaredLength != null && declaredLength > maxBytes) {
        throw IOException("Risposta RFI oltre limite: $declaredLength > $maxBytes byte")
    }
    val output = ByteArrayOutputStream(minOf(declaredLength?.toInt() ?: 8_192, maxBytes))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Risposta RFI oltre limite streaming: $total > $maxBytes byte")
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal class HttpRfiProgrammedTimetableFetcher : RfiProgrammedTimetableFetcher {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Il quadro orario RFI non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Binario/0.20 (personal rail timetable client)")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("RFI ha risposto HTTP $status")
            val declaredLength = connection.contentLengthLong.takeIf { it >= 0L }
            connection.inputStream.use { readBoundedRfiHtml(it, declaredLength) }
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

    /**
     * Curated railway codes are the stable key: catalogue names differ from RFI labels
     * ("Firenze S. M. Novella", "Bari ( Tutte Le Stazioni )") and the board path used to
     * receive only the display name (Opus audit N-M4).
     */
    private val byRailwayCode = mapOf(
        "S08409" to 2247, // Roma Termini
        "S08217" to 2217, // Roma Tiburtina
        "S08406" to 2211, // Roma Ostiense
        "S01700" to 1603, // Milano Centrale
        "S09218" to 1757, // Napoli Centrale
        "S05043" to 635, // Bologna Centrale
        "S06421" to 1325, // Firenze S. M. Novella
        "S00219" to 2672, // Torino Porta Nuova
        "S00035" to 25350269, // Torino Porta Susa
        "S02593" to 2796, // Venezia S. Lucia
        "S02430" to 2812, // Verona Porta Nuova
        "S04700" to 248, // Genova Piazza Principe
        "S11119" to 550, // Bari Centrale
        "S09818" to 2434 // Salerno
    )

    fun timetableId(station: Station): Int? = byLegacyId[station.id]
        ?: station.railwayCode?.let(byRailwayCode::get)
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
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val cacheTtlMillis: Long = 6L * 60L * 60L * 1000L,
    private val cacheMaxEntries: Int = 16,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onParsing: (() -> Unit)? = null
) : TrainDataSource {
    private val cache = BoundedSingleFlightCache<ParsedTimetable>(
        maxEntries = cacheMaxEntries,
        ttlMillis = cacheTtlMillis,
        clockMillis = clock::millis
    )

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = clock.instant().atZone(clock.zone)
        return loadRoute(route, direction, JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0)))
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val originId = RfiProgrammedStationIds.timetableId(origin)
        val arrivalId = if (originId == null) {
            RfiProgrammedStationIds.timetableId(destination)
                ?: SupplementalRfiProgrammedStationIds.timetableId(destination)
        } else null
        val stationId = originId ?: arrivalId
            ?: return DataResult.Failure(unmappedDirectionMessage(origin, destination), RouteFailureReason.RouteNotCovered)
        val arrivals = arrivalId != null

        val board = if (arrivals) "Arrivi" else "Partenze"
        val key = "${route.id}:${direction.name}:$stationId:$board"
        val cached = try {
            cache.getOrLoad(key) {
                val url = "https://prm.rfi.it/qo_prm/QO_${board}_SiPMR.aspx?Id=$stationId&lin=it&dalle=00.00&alle=23.59&ora=00.00"
                val html = try {
                    withContext(ioDispatcher) { fetcher.fetch(url) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    throw RfiLoadFailure(
                        "Il quadro orario programmato RFI non è raggiungibile: ${error.message ?: "errore di rete"}.",
                        error
                    )
                } catch (error: Exception) {
                    throw RfiLoadFailure(
                        "Il quadro orario programmato RFI non è leggibile: ${error.message ?: "errore di parsing"}.",
                        error
                    )
                }
                try {
                    withContext(parsingDispatcher) {
                        onParsing?.invoke()
                        parseTimetable(html, origin, route, direction, arrivals)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw RfiLoadFailure(
                        "Il quadro orario programmato RFI non è leggibile: ${error.message ?: "errore di parsing"}.",
                        error
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RfiLoadFailure) {
            return DataResult.Failure(error.message.orEmpty())
        }
        val parsed = cached.value

        parsed.validity?.let { validity ->
            if (query.serviceDate !in validity.start..validity.end) {
                return DataResult.Failure(
                    "Il quadro orario RFI disponibile è valido dal ${validity.start} al ${validity.end}, non per ${query.serviceDate}.",
                    RouteFailureReason.ScheduleExpired(validity.end)
                )
            }
        }

        val services = withContext(parsingDispatcher) {
            onParsing?.invoke()
            val requestedMinutes = query.departureTime.hour * 60 + query.departureTime.minute
            parsed.services.asSequence()
                .mapNotNull { service ->
                    ItalianRailServiceCalendar.rfiDepartureDate(
                        timeMinutes(service.service.scheduledDeparture),
                        query.serviceDate,
                        requestedMinutes
                    )?.takeIf { departureDate ->
                        val boardDate = departureDate.plusDays(service.boardDayOffset.toLong())
                        (parsed.validity?.let { boardDate in it.start..it.end } ?: true) &&
                            runsOnDate(service.periodicity, boardDate)
                    }?.let { service.copy(departureDate = it) }
                }
                .filter { service ->
                    service.service.stops.drop(1).any { destination.isNamedBy(it.station) } ||
                        destination.isNamedBy(service.service.destination)
                }
                .map { parsedService ->
                    val targetIndex = parsedService.service.stops.indexOfFirst { destination.isNamedBy(it.station) }
                    val relevantStops = if (targetIndex >= 1) {
                        parsedService.service.stops.take(targetIndex + 1)
                    } else {
                        parsedService.service.stops
                    }
                    parsedService.service.copy(
                        stops = relevantStops,
                        scheduledDepartureDateTime = LocalDateTime.of(
                            parsedService.departureDate ?: query.serviceDate,
                            parseTime(parsedService.service.scheduledDeparture) ?: query.departureTime
                        )
                    )
                }
                .sortedBy { it.scheduledDepartureDateTime }
                .take(24)
                .toList()
        }

        if (services.isEmpty()) {
            return DataResult.Failure(
                "RFI non mostra corse programmate dirette da ${origin.name} verso ${destination.name} dopo ${query.departureTime}.",
                if (parsed.services.any { row -> row.service.stops.drop(1).any { destination.isNamedBy(it.station) } }) {
                    RouteFailureReason.NoServiceAfter(query.serviceDate, query.departureTime)
                } else RouteFailureReason.RouteNotCovered
            )
        }

        return DataResult.Success(
            RouteSnapshot(
                route = route,
                direction = direction,
                services = services,
                providerName = "RFI — Quadro orario programmato",
                retrievedAtEpochMillis = cached.fetchedAtEpochMillis,
                expiresAtEpochMillis = cached.expiresAtEpochMillis,
                freshness = Freshness.AGING,
                sourceObservedAtEpochMillis = cached.fetchedAtEpochMillis,
                confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
                notice = "Orario programmato ufficiale RFI; ritardi, binari reali e variazioni operative vengono riconciliati separatamente con le fonti realtime."
            )
        )
    }

    internal fun parseTimetable(
        html: String,
        origin: Station,
        route: SavedRoute,
        direction: Direction,
        arrivals: Boolean = false
    ): ParsedTimetable {
        val document = Jsoup.parse(html)
        val validity = parseValidity(document.text())
        val services = buildList {
            document.select("table").forEach { table ->
                val rows = table.select("tr")
                if (rows.isEmpty()) return@forEach
                val headerRow = rows.firstOrNull { row -> row.select("th").isNotEmpty() }
                    ?: rows.firstOrNull { row ->
                        row.text().contains(if (arrivals) "Fermate precedenti" else "Fermate successive", ignoreCase = true)
                    }
                val headers = headerRow?.select("th,td")?.map { normalizeHeader(elementText(it)) }.orEmpty()
                val indexes = HeaderIndexes.from(headers)
                // Arrival fallback must never interpret a departure board or an error page as arrivals.
                if (arrivals && (indexes.previousStops == null ||
                        !headers[indexes.previousStops].contains("ORARIO DI PARTENZA") ||
                        headers.none { it.startsWith("ORARIO DI ARRIVO") })) {
                    return@forEach
                }
                rows.forEach { row -> parseRow(row, indexes, origin, route, direction, arrivals)?.let(::add) }
            }
        }.distinctBy { "${it.service.number}:${it.service.scheduledDeparture}:${it.service.destination}" }

        return ParsedTimetable(validity, services)
    }

    private fun parseRow(
        row: Element,
        indexes: HeaderIndexes,
        origin: Station,
        route: SavedRoute,
        direction: Direction,
        arrivals: Boolean
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
        val boardTime = normalizeTime(elementText(timeCell))
            .ifBlank {
                if (arrivals) "" else cells.map { normalizeTime(elementText(it)) }.firstOrNull { it.isNotBlank() }.orEmpty()
            }
        if (boardTime.isBlank()) return null

        val arrivalStops = if (arrivals) {
            val previous = parseStops(elementText(cells.getOrNull(indexes.previousStops ?: return null)))
            val originIndex = previous.indexOfFirst { origin.isNamedBy(it.station) }
            if (originIndex < 0) return null
            previous.drop(originIndex) + ProgrammedStop(route.destination(direction).name, boardTime)
        } else emptyList()
        val scheduled = arrivalStops.firstOrNull()?.time ?: boardTime

        val destinationCell = cell(indexes.destination, "[id*=Destin],[class*=destin],[headers*=destin]")
        val destinationText = elementText(destinationCell)
        val destination = destinationText.substringBefore("(").trim().ifBlank { "Destinazione non indicata" }

        val stopsCell = cell(indexes.stops, "[id*=Fermate],[class*=fermate],[headers*=fermate]")
        val successiveStops = parseStops(elementText(stopsCell))
        val finalDestination = arrivalStops.lastOrNull()?.station
            ?: successiveStops.lastOrNull()?.station?.ifBlank { null }
            ?: destination.takeIf { it != "Destinazione non indicata" }
            ?: return null

        val periodicity = normalizeHeader(elementText(cell(indexes.periodicity, "[id*=Period],[class*=period],[headers*=period]")))
        val carrier = elementText(cell(indexes.carrier, "[id*=Impresa],[class*=impresa],[headers*=impresa]"))
        val categoryText = "$trainText $carrier"
        val operator = operatorFrom(categoryText)
        val category = categoryFrom(categoryText, operator)

        val stops = buildList {
            add(TrainStop(origin.name, scheduled, scheduled, null, 0, completed = false))
            if (arrivals) {
                arrivalStops.drop(1).forEach { stop ->
                    add(TrainStop(stop.station, stop.time, stop.time, null, 0, completed = false))
                }
            } else if (successiveStops.isNotEmpty()) {
                successiveStops.forEach { stop ->
                    add(TrainStop(stop.station, stop.time, stop.time, null, 0, completed = false))
                }
            } else if (!origin.isNamedBy(finalDestination)) {
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
            periodicity = periodicity,
            boardDayOffset = if (arrivals) arrivalStops.zipWithNext().count { (a, b) -> timeMinutes(b.time) < timeMinutes(a.time) } else 0
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
            station.takeIf { it.isNotBlank() && parseTime(time) != null }?.let { ProgrammedStop(it, time) }
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

    internal fun runsOnDate(periodicity: String, date: LocalDate): Boolean =
        ItalianRailServiceCalendar.runsOnDate(periodicity, date)

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

    private fun unmappedDirectionMessage(origin: Station, destination: Station): String =
        "I quadri orari RFI non sono ancora mappati per ${origin.name} o ${destination.name}."

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
        val periodicity: String,
        /** Set while filtering: the date this row departs for the current query (next day after midnight). */
        val departureDate: LocalDate? = null,
        /** Arrival boards use the calendar day at the destination, which can follow departure. */
        val boardDayOffset: Int = 0
    )

    internal data class TimetableValidity(val start: LocalDate, val end: LocalDate)
    internal suspend fun cachedTimetableCountForTest(): Int = cache.sizeForTest()
    private data class ProgrammedStop(val station: String, val time: String)
    private class RfiLoadFailure(message: String, cause: Throwable) : Exception(message, cause)

    private data class HeaderIndexes(
        val time: Int?,
        val train: Int?,
        val destination: Int?,
        val stops: Int?,
        val previousStops: Int?,
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
                    previousStops = index("FERMATE PRECEDENTI"),
                    carrier = index("IMPRESA DI TRASPORTO", "IMPRESA"),
                    periodicity = index("PERIODICITA", "PERIODICITÀ")
                )
            }
        }
    }
}
