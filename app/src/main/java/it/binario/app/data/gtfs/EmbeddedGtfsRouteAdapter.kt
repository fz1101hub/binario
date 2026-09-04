package it.binario.app.data.gtfs

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
import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailSourceCatalog
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback offline per orari programmati. Legge soltanto i data pack GTFS già
 * verificati e inclusi nell'APK: non effettua download, non richiede token e
 * non attribuisce ritardi o binari non presenti nel feed statico.
 *
 * Una tratta può essere pubblicata da più dataset (es. Trenord + Svizzera,
 * STA + ÖBB, SNCF + altri feed transfrontalieri). Per questo i risultati dei
 * pack compatibili vengono uniti invece di fermarsi alla prima corrispondenza.
 * agency.txt viene mantenuto per attribuire la corsa all'operatore effettivo,
 * senza confondere il proprietario del data pack con l'impresa ferroviaria.
 */
class EmbeddedGtfsRouteAdapter(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val clock: () -> Long = System::currentTimeMillis
) : TrainDataSource {
    private val indexCache = object : LinkedHashMap<SourceId, StaticGtfsIndex>(5, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, StaticGtfsIndex>?): Boolean = size > 5
    }
    private val stopPresenceCache = mutableMapOf<SourceId, Set<String>>()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val origin = route.origin(direction).name
        val destination = route.destination(direction).name
        val wantedOrigin = normalize(origin)
        val wantedDestination = normalize(destination)

        val explicitSourceIds = listOf(route.origin(direction).id, route.destination(direction).id)
            .mapNotNull(::sourceIdFromGtfsStationId)
            .distinct()
            .takeIf { ids -> ids.size == 1 }
            ?.toSet()

        val candidatePacks = withContext(Dispatchers.IO) {
            packs.filter { pack ->
                if (explicitSourceIds != null) {
                    pack.sourceId in explicitSourceIds
                } else {
                    val stops = stopNames(pack)
                    wantedOrigin in stops && wantedDestination in stops
                }
            }
        }

        if (candidatePacks.isEmpty()) {
            return DataResult.Failure("Nessun data pack GTFS contiene entrambe le stazioni richieste.")
        }

        val collected = mutableListOf<SourcedService>()
        for (pack in candidatePacks) {
            val source = RailSourceCatalog.byId(pack.sourceId) ?: continue
            val index = synchronized(indexCache) { indexCache[pack.sourceId] } ?: withContext(Dispatchers.IO) {
                parse(openAsset(pack.assetPath))
            }.also { parsed ->
                synchronized(indexCache) { indexCache[pack.sourceId] = parsed }
            }
            index.services(origin, destination, source.id.value, query).forEach { service ->
                collected += SourcedService(source.displayName, pack.sourceId, service)
            }
        }

        if (collected.isEmpty()) {
            return DataResult.Failure("Nessuna corsa programmata trovata nei data pack GTFS compatibili per data e orario selezionati.")
        }

        val services = collected
            .sortedWith(
                compareBy<SourcedService> { it.service.scheduledDepartureDateTime }
                    .thenByDescending { sourcePreference(it.sourceId, it.service.operatorDisplayName) }
                    .thenBy { it.service.number }
            )
            .distinctBy { item -> serviceIdentity(item.service) }
            .take(24)
            .sortedWith(compareBy<SourcedService> { it.service.scheduledDepartureDateTime }.thenBy { it.service.number })

        val now = clock()
        val sourceNames = services.map { it.sourceName }.distinct()
        return DataResult.Success(
            RouteSnapshot(
                route = route,
                direction = direction,
                services = services.map { it.service },
                providerName = sourceNames.joinToString(" + ") { "$it · orario programmato" },
                retrievedAtEpochMillis = now,
                sourceObservedAtEpochMillis = now,
                expiresAtEpochMillis = now + 12 * 60 * 60 * 1000,
                freshness = Freshness.AGING,
                confidence = DataConfidence.STATIC_ONLY,
                notice = if (sourceNames.size > 1) {
                    "Orario programmato unificato da ${sourceNames.joinToString()}. I duplicati sono riconciliati privilegiando la fonte ufficiale più vicina all'operatore. Ritardi, binari e soppressioni richiedono fonti realtime."
                } else {
                    "Orario programmato da ${sourceNames.first()}. Ritardi, binari e soppressioni richiedono una fonte realtime."
                }
            )
        )
    }

    private fun serviceIdentity(service: TrainService): String = listOf(
        normalizeTrainNumber(service.number),
        service.scheduledDepartureDateTime.toString(),
        normalize(service.origin),
        normalize(service.destination)
    ).joinToString("|")

    private fun sourcePreference(sourceId: SourceId, operatorName: String?): Int {
        val operator = normalize(operatorName.orEmpty())
        return when (sourceId.value) {
            "ch-otd-gtfs" -> if (operator.matchesAny("sbb", "tilo", "bls", "rhb", "rhatischebahn", "fart", "ssif")) 120 else 90
            "oebb-gtfs" -> if (operator.matchesAny("obb", "oebb", "osterreichischebundesbahnen")) 120 else 90
            "sncf-gtfs" -> if (operator.contains("sncf")) 120 else 95
            "lombardia-trenord-gtfs" -> if (operator.contains("trenord")) 120 else 100
            "alto-adige-sta-gtfs" -> if (operator.matchesAny("sad", "sta", "sudtirol", "suedtirol")) 115 else 85
            "delfi-gtfs" -> if (operator.matchesAny("db", "deutschebahn", "dbfernverkehr")) 120 else 80
            else -> 70
        }
    }

    private fun String.matchesAny(vararg candidates: String): Boolean = candidates.any(::contains)

    private fun normalizeTrainNumber(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]"), "")

    private fun sourceIdFromGtfsStationId(stationId: String): SourceId? {
        if (!stationId.startsWith("gtfs:")) return null
        val source = stationId.removePrefix("gtfs:").substringBefore(':').takeIf(String::isNotBlank) ?: return null
        return SourceId(source)
    }

    private fun stopNames(pack: EmbeddedGtfsPack): Set<String> {
        synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] }?.let { return it }
        val names = linkedSetOf<String>()
        openAsset(pack.assetPath).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.substringAfterLast('/') == "stops.txt") {
                        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                        val header = reader.readLine()?.let(::csvLine).orEmpty()
                        val nameIndex = header.indexOf("stop_name")
                        if (nameIndex >= 0) {
                            var line = reader.readLine()
                            while (line != null) {
                                if (line.length <= MAX_CSV_LINE_LENGTH) {
                                    csvLine(line).getOrNull(nameIndex)?.takeIf(String::isNotBlank)?.let { names += normalize(it) }
                                }
                                line = reader.readLine()
                            }
                        }
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return names.toSet().also { synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] = it } }
    }

    private fun parse(stream: InputStream): StaticGtfsIndex {
        val tables = mutableMapOf<String, List<Map<String, String>>>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name in requiredTables) {
                    tables[name] = parseCsv(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val agencies = tables["agency.txt"].orEmpty()
            .mapNotNull { row ->
                val id = row["agency_id"].orEmpty().trim()
                val name = row["agency_name"].orEmpty().trim()
                name.takeIf(String::isNotBlank)?.let { id to it }
            }
            .toMap()
        return StaticGtfsIndex(
            stops = tables["stops.txt"].orEmpty().associate { it["stop_id"].orEmpty() to it["stop_name"].orEmpty() },
            agencies = agencies,
            routes = tables["routes.txt"].orEmpty().associateBy { it["route_id"].orEmpty() },
            trips = tables["trips.txt"].orEmpty(),
            stopTimes = tables["stop_times.txt"].orEmpty().groupBy { it["trip_id"].orEmpty() },
            calendars = tables["calendar.txt"].orEmpty().associateBy { it["service_id"].orEmpty() },
            calendarDates = tables["calendar_dates.txt"].orEmpty().groupBy { it["service_id"].orEmpty() }
        )
    }

    private fun parseCsv(reader: BufferedReader): List<Map<String, String>> {
        val header = reader.readLine()?.let(::csvLine).orEmpty()
        if (header.isEmpty()) return emptyList()
        return generateSequence { reader.readLine() }
            .filter { it.isNotBlank() && it.length <= MAX_CSV_LINE_LENGTH }
            .map { row -> header.zip(csvLine(row)).toMap() }
            .toList()
    }

    private fun csvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when (val character = line[index]) {
                '"' -> if (quoted && line.getOrNull(index + 1) == '"') {
                    current.append('"')
                    index += 1
                } else quoted = !quoted
                ',' -> if (quoted) current.append(character) else {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private data class SourcedService(
        val sourceName: String,
        val sourceId: SourceId,
        val service: TrainService
    )

    private data class StaticGtfsIndex(
        val stops: Map<String, String>,
        val agencies: Map<String, String>,
        val routes: Map<String, Map<String, String>>,
        val trips: List<Map<String, String>>,
        val stopTimes: Map<String, List<Map<String, String>>>,
        val calendars: Map<String, Map<String, String>>,
        val calendarDates: Map<String, List<Map<String, String>>>
    ) {
        fun services(origin: String, destination: String, sourceId: String, query: JourneyQuery): List<TrainService> {
            val wantedOrigin = normalize(origin)
            val wantedDestination = normalize(destination)
            return trips.asSequence().mapNotNull { trip ->
                val serviceId = trip["service_id"].orEmpty()
                if (!serviceRunsOn(serviceId, query.serviceDate)) return@mapNotNull null
                val times = stopTimes[trip["trip_id"]].orEmpty()
                    .sortedBy { it["stop_sequence"]?.toIntOrNull() ?: Int.MAX_VALUE }
                val originIndex = times.indexOfFirst { normalize(stops[it["stop_id"]].orEmpty()) == wantedOrigin }
                val destinationIndex = times.indices.firstOrNull { candidateIndex ->
                    candidateIndex > originIndex && normalize(stops[times[candidateIndex]["stop_id"]].orEmpty()) == wantedDestination
                } ?: -1
                if (originIndex < 0 || destinationIndex < 0) return@mapNotNull null
                val route = routes[trip["route_id"]].orEmpty()
                val segment = times.subList(originIndex, destinationIndex + 1)
                val rawDeparture = segment.first()["departure_time"] ?: segment.first()["arrival_time"] ?: return@mapNotNull null
                val departureMinutes = serviceMinutes(rawDeparture) ?: return@mapNotNull null
                val scheduledDateTime = query.serviceDate.atStartOfDay().plusMinutes(departureMinutes.toLong())
                if (scheduledDateTime.isBefore(query.requestedAt)) return@mapNotNull null
                val departure = hhmm(rawDeparture)
                val number = trip["trip_short_name"]?.takeIf { it.isNotBlank() } ?: trip["trip_id"].orEmpty()
                val agencyName = agencyName(route)
                TrainService(
                    id = "$sourceId:${trip["trip_id"]}",
                    number = number,
                    operator = legacyOperator(sourceId, agencyName, route),
                    operatorDisplayName = canonicalAgencyLabel(agencyName, sourceId),
                    trainCategory = category(route["route_type"], route, agencyName),
                    origin = stops[segment.first()["stop_id"]].orEmpty(),
                    destination = stops[segment.last()["stop_id"]].orEmpty(),
                    scheduledDeparture = departure,
                    expectedDeparture = departure,
                    platform = null,
                    delayMinutes = 0,
                    status = ServiceStatus.ON_TIME,
                    operationalStatusVerified = false,
                    scheduledDepartureDateTime = scheduledDateTime,
                    stops = segment.map { stop ->
                        val scheduled = hhmm(stop["departure_time"] ?: stop["arrival_time"].orEmpty())
                        TrainStop(stops[stop["stop_id"]].orEmpty(), scheduled, scheduled, null, 0, false)
                    }
                )
            }.sortedWith(compareBy<TrainService> { it.scheduledDepartureDateTime }.thenBy { it.number }).toList()
        }

        private fun agencyName(route: Map<String, String>): String? {
            val agencyId = route["agency_id"].orEmpty().trim()
            return agencies[agencyId]
                ?: agencies.takeIf { it.size == 1 }?.values?.singleOrNull()
        }

        private fun canonicalAgencyLabel(value: String?, sourceId: String): String? {
            val raw = value?.trim().orEmpty()
            if (raw.isBlank()) {
                return when (sourceId) {
                    "lombardia-trenord-gtfs" -> "Trenord"
                    "oebb-gtfs" -> "ÖBB"
                    "sncf-gtfs" -> "SNCF Voyageurs"
                    else -> null
                }
            }
            val normalized = normalize(raw)
            return when {
                normalized.contains("tilo") -> "TILO"
                normalized.contains("schweizerischebundesbahnen") || Regex("(^|[^a-z])sbb([^a-z]|$)").containsMatchIn(raw.lowercase(Locale.ROOT)) -> "SBB"
                normalized.contains("bls") -> "BLS"
                normalized.contains("rhatischebahn") || normalized.contains("rhaetischebahn") || normalized == "rhb" -> "RhB"
                normalized.contains("ferrovieautolineeregionaliticinesi") || normalized == "fart" -> "FART"
                normalized.contains("societasubalpinadiimpreseferroviarie") || normalized == "ssif" -> "SSIF"
                normalized.contains("osterreichischebundesbahnen") || normalized.contains("oebb") || normalized == "obb" -> "ÖBB"
                normalized.contains("dbfernverkehr") || normalized.contains("deutschebahn") -> "DB"
                normalized.contains("sncf") -> "SNCF Voyageurs"
                normalized.contains("europeansleeper") -> "European Sleeper"
                normalized.contains("trenord") -> "Trenord"
                normalized.contains("trenitalia") -> "Trenitalia"
                normalized.contains("ntv") || normalized.contains("italotreno") -> "Italo"
                else -> raw
            }
        }

        private fun legacyOperator(sourceId: String, agencyName: String?, route: Map<String, String>): RailOperator {
            val identity = normalize(listOfNotNull(agencyName, route["route_short_name"], route["route_long_name"]).joinToString(" "))
            return when {
                identity.contains("trenord") || sourceId == "lombardia-trenord-gtfs" -> RailOperator.TRENORD
                identity.contains("italo") || identity.contains("ntv") -> RailOperator.ITALO
                identity.contains("frecc") -> RailOperator.TRENITALIA_AV
                identity.contains("intercity") || identity.contains("trenitalia") -> RailOperator.TRENITALIA_LONG_DISTANCE
                else -> RailOperator.OTHER
            }
        }

        private fun category(routeType: String?, route: Map<String, String>, agencyName: String?): TrainCategory {
            val identity = normalize(listOfNotNull(route["route_short_name"], route["route_long_name"], agencyName).joinToString(" "))
            return when {
                identity.contains("italo") -> TrainCategory.AV_ITALO
                identity.contains("frecc") -> TrainCategory.FRECCE
                identity.contains("intercity") || Regex("(^|[^a-z])icn?([^a-z]|$)").containsMatchIn(identity) -> TrainCategory.INTERCITY
                identity.contains("regionaleveloce") -> TrainCategory.REGIONALE_VELOCE
                sourceIdLooksTrenord(identity) -> TrainCategory.TRENORD_REGIO
                routeType in RAIL_ROUTE_TYPES -> TrainCategory.REGIONALE
                else -> TrainCategory.OTHER
            }
        }

        private fun sourceIdLooksTrenord(identity: String): Boolean = identity.contains("trenord")

        private fun serviceRunsOn(serviceId: String, date: LocalDate): Boolean {
            val exception = calendarDates[serviceId]
                .orEmpty()
                .lastOrNull { parseGtfsDate(it["date"].orEmpty()) == date }
                ?.get("exception_type")
            if (exception == "1") return true
            if (exception == "2") return false

            val calendar = calendars[serviceId] ?: return false
            val start = parseGtfsDate(calendar["start_date"].orEmpty()) ?: return false
            val end = parseGtfsDate(calendar["end_date"].orEmpty()) ?: return false
            if (date < start || date > end) return false

            val field = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> "monday"
                DayOfWeek.TUESDAY -> "tuesday"
                DayOfWeek.WEDNESDAY -> "wednesday"
                DayOfWeek.THURSDAY -> "thursday"
                DayOfWeek.FRIDAY -> "friday"
                DayOfWeek.SATURDAY -> "saturday"
                DayOfWeek.SUNDAY -> "sunday"
            }
            return calendar[field] == "1"
        }

        private fun parseGtfsDate(value: String): LocalDate? =
            if (value.matches(Regex("\\d{8}"))) runCatching {
                LocalDate.of(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
            }.getOrNull() else null

        private fun serviceMinutes(value: String): Int? {
            val parts = value.split(':')
            val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
            if (hours < 0 || minutes !in 0..59) return null
            return hours * 60 + minutes
        }

        private fun hhmm(value: String): String {
            val parts = value.split(':')
            val hours = parts.firstOrNull()?.toIntOrNull()?.mod(24) ?: 0
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return "%02d:%02d".format(Locale.ROOT, hours, minutes)
        }
    }

    private companion object {
        const val MAX_CSV_LINE_LENGTH = 32_768
        val RAIL_ROUTE_TYPES = setOf("2", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109")
        val requiredTables = setOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]".toRegex(), "")
    }
}
