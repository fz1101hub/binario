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
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceCatalog
import it.binario.app.data.sources.RailSourceSelectionPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onParsing: (() -> Unit)? = null
) : TrainDataSource {
    /** Due indici compatti al massimo: un pack AMT completo resta sicuro anche su heap piccoli. */
    private val indexCache = object : LinkedHashMap<SourceId, StaticGtfsIndex>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, StaticGtfsIndex>?): Boolean = size > 2
    }
    // Candidate discovery touches every pack when a route has no explicit source id. Keep one
    // compact stop-name set per configured pack so the access-order LRU cannot churn on 10 packs.
    private val stopPresenceCacheCapacity = packs.size.coerceAtLeast(1)
    private val stopPresenceCache = object : LinkedHashMap<SourceId, Set<String>>(stopPresenceCacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, Set<String>>?): Boolean =
            size > stopPresenceCacheCapacity
    }
    private val indexMutex = Mutex()
    private val stopPresenceMutex = Mutex()

    /** Diagnostica interna usata dal test di regressione a heap limitato. */
    internal fun compactIndexStats(): List<GtfsCompactIndexStats> = synchronized(indexCache) {
        indexCache.values.map(StaticGtfsIndex::stats)
    }

    /** Diagnostica interna: la ricerca senza source id deve poter ricordare ogni pack configurato. */
    internal fun stopPresenceCacheSizeForTest(): Int = synchronized(stopPresenceCache) { stopPresenceCache.size }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val originStation = route.origin(direction)
        val destinationStation = route.destination(direction)
        val origin = originStation.name
        val destination = destinationStation.name
        val wantedOrigin = normalize(origin)
        val wantedDestination = normalize(destination)

        val originSourceId = sourceIdFromGtfsStationId(originStation.id)
        val destinationSourceId = sourceIdFromGtfsStationId(destinationStation.id)
        val explicitSourceIds = if (
            originSourceId != null &&
            destinationSourceId != null &&
            originSourceId == destinationSourceId
        ) {
            setOf(originSourceId)
        } else {
            null
        }

        val candidatePacks = withContext(parsingDispatcher) {
            buildList {
                for (pack in packs) {
                    if (explicitSourceIds != null) {
                        if (pack.sourceId in explicitSourceIds) add(pack)
                        continue
                    }
                    try {
                        val stops = stopNames(pack)
                        if (wantedOrigin in stops && wantedDestination in stops) add(pack)
                    } catch (_: GtfsCompactIndexLimitException) {
                        // An oversized/malformed pack cannot be a candidate.
                    }
                }
            }
        }

        if (candidatePacks.isEmpty()) {
            return DataResult.Failure("Nessun data pack GTFS contiene entrambe le stazioni richieste.")
        }

        val collected = withContext(parsingDispatcher) {
            buildList {
                for (pack in candidatePacks) {
                    val source = RailSourceCatalog.byId(pack.sourceId) ?: continue
                    val index = try {
                        indexFor(pack)
                    } catch (_: GtfsCompactIndexLimitException) {
                        continue
                    }
                    index.services(origin, destination, source.id.value, query).forEach { service ->
                        add(SourcedService(source.displayName, pack.sourceId, service))
                    }
                }
            }
        }

        if (collected.isEmpty()) {
            val expiredPacks = candidatePacks.filter { pack -> pack.lastServiceDate?.isBefore(query.serviceDate) == true }
            if (expiredPacks.isNotEmpty()) {
                val validity = expiredPacks.joinToString(" e ") { pack ->
                    val label = RailSourceCatalog.byId(pack.sourceId)?.displayName ?: pack.sourceId.value
                    "$label sono validi fino al ${pack.lastServiceDate}"
                }
                return DataResult.Failure("Gli orari inclusi per $validity: aggiorna l'app per orari più recenti.")
            }
            return DataResult.Failure("Nessuna corsa programmata trovata nei data pack GTFS compatibili per data e orario selezionati.")
        }

        val services = withContext(parsingDispatcher) {
            collected
                .sortedWith(
                    compareBy<SourcedService> { it.service.scheduledDepartureDateTime }
                        .thenByDescending { sourcePreference(it.sourceId, it.service.operatorDisplayName) }
                        .thenBy { it.service.number }
                )
                .distinctBy { item -> serviceIdentity(item.service) }
                .take(24)
                .sortedWith(compareBy<SourcedService> { it.service.scheduledDepartureDateTime }.thenBy { it.service.number })
        }

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
                    "Orario programmato da ${sourceNames.first()}. Ritardi, binari e soppressioni richiedono fonti realtime."
                }
            )
        )
    }

    private suspend fun stopNames(pack: EmbeddedGtfsPack): Set<String> {
        synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] }?.let { return it }
        return stopPresenceMutex.withLock {
            synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] } ?: run {
                val bytes = assetBytes(pack.assetPath)
                val names = withContext(parsingDispatcher) {
                    onParsing?.invoke()
                    scanStopNames(ByteArrayInputStream(bytes))
                }
                synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] ?: names.also { stopPresenceCache[pack.sourceId] = it } }
            }
        }
    }

    private suspend fun assetBytes(path: String): ByteArray = withContext(ioDispatcher) {
        openAsset(path).use(InputStream::readBytes)
    }

    private suspend fun indexFor(pack: EmbeddedGtfsPack): StaticGtfsIndex {
        synchronized(indexCache) { indexCache[pack.sourceId] }?.let { return it }
        return indexMutex.withLock {
            synchronized(indexCache) { indexCache[pack.sourceId] } ?: run {
                val bytes = assetBytes(pack.assetPath)
                val parsed = withContext(parsingDispatcher) {
                    onParsing?.invoke()
                    parse { ByteArrayInputStream(bytes) }
                }
                synchronized(indexCache) { indexCache[pack.sourceId] ?: parsed.also { indexCache[pack.sourceId] = it } }
            }
        }
    }

    private fun serviceIdentity(service: TrainService): String = listOf(
        normalizeTrainNumber(service.number),
        service.scheduledDepartureDateTime.toString(),
        normalize(service.origin),
        normalize(service.destination)
    ).joinToString("|")

    private fun sourcePreference(sourceId: SourceId, operatorName: String?): Int =
        RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            sourceId,
            operatorName
        )

    private fun normalizeTrainNumber(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]"), "")

    private fun sourceIdFromGtfsStationId(stationId: String): SourceId? {
        if (!stationId.startsWith("gtfs:")) return null
        val source = stationId.removePrefix("gtfs:").substringBefore(':').takeIf(String::isNotBlank) ?: return null
        return SourceId(source)
    }

    private fun scanStopNames(stream: InputStream): Set<String> {
        val names = linkedSetOf<String>()
        BoundedGtfsZipScanner(compactIndexLimits).scan(stream, setOf("stops.txt")) { _, rows ->
            val nameIndex = rows.header.indexOf("stop_name")
            if (nameIndex >= 0) {
                rows.forEachRow(compactIndexLimits.maxStops) { row ->
                    row.getOrNull(nameIndex)?.takeIf(String::isNotBlank)?.let { names += normalize(it) }
                }
            }
        }
        return names
    }

    /**
     * Costruisce un indice a colonne. Non esiste alcun passaggio che trasformi
     * una tabella GTFS in List<Map<String, String>>: ogni riga viene consumata
     * subito e le sole colonne necessarie alla tratta sono mantenute in array.
     */
    private fun parse(openStream: () -> InputStream): StaticGtfsIndex {
        val index = StaticGtfsIndex(compactIndexLimits)
        BoundedGtfsZipScanner(compactIndexLimits).scan(openStream(), compactIndexTables - "stop_times.txt") { table, rows ->
            when (table) {
                "agency.txt" -> index.readAgencies(rows)
                "stops.txt" -> index.readStops(rows)
                "routes.txt" -> index.readRoutes(rows)
                "trips.txt" -> index.readTrips(rows)
                "calendar.txt" -> index.readCalendars(rows)
                "calendar_dates.txt" -> index.readCalendarDates(rows)
            }
        }
        // Seconda passata: trips è ormai completo anche quando stop_times è la
        // prima entry (come nel pack AMT), quindi non indicizziamo trip ignoti.
        BoundedGtfsZipScanner(compactIndexLimits).scan(openStream(), setOf("stop_times.txt")) { _, rows ->
            index.readStopTimes(rows)
        }
        return index
    }

    private data class SourcedService(
        val sourceName: String,
        val sourceId: SourceId,
        val service: TrainService
    )

    internal data class GtfsCompactIndexStats(
        val stopTimeRowsRead: Int,
        val indexedStopTimeRows: Int,
        val stopTimeColumnBytes: Long,
        val stops: Int,
        val trips: Int
    )

    private class StaticGtfsIndex(private val limits: GtfsCompactIndexLimits) {
        private val stops = CompactStops(limits.maxStops)
        private val agencies = HashMap<String, String>()
        private val routes = HashMap<String, CompactRoute>()
        private val trips = CompactTrips(limits.maxTrips)
        private val stopTimes = CompactStopTimes(limits.maxStopTimeRows)
        private val calendars = HashMap<String, CompactCalendar>()
        private val calendarDates = HashMap<String, CompactCalendarExceptions>()

        fun readAgencies(rows: GtfsCsvRows) {
            val idIndex = rows.header.indexOf("agency_id")
            val nameIndex = rows.header.indexOf("agency_name")
            rows.forEachRow(limits.maxAgencies) { row ->
                val id = row.gtfsRawValue(idIndex).trim()
                val name = row.gtfsRawValue(nameIndex).trim()
                if (name.isNotBlank()) agencies[id] = name
            }
        }

        fun readStops(rows: GtfsCsvRows) {
            val idIndex = rows.header.indexOf("stop_id")
            val nameIndex = rows.header.indexOf("stop_name")
            rows.forEachRow(limits.maxStops) { row ->
                stops.put(row.gtfsRawValue(idIndex), row.gtfsRawValue(nameIndex))
            }
        }

        fun readRoutes(rows: GtfsCsvRows) {
            val idIndex = rows.header.indexOf("route_id")
            val agencyIndex = rows.header.indexOf("agency_id")
            val shortNameIndex = rows.header.indexOf("route_short_name")
            val longNameIndex = rows.header.indexOf("route_long_name")
            val typeIndex = rows.header.indexOf("route_type")
            rows.forEachRow(limits.maxRoutes) { row ->
                routes[row.gtfsRawValue(idIndex)] = CompactRoute(
                    agencyId = row.gtfsRawValue(agencyIndex),
                    shortName = row.gtfsRawValue(shortNameIndex),
                    longName = row.gtfsRawValue(longNameIndex),
                    type = row.getOrNull(typeIndex)
                )
            }
        }

        fun readTrips(rows: GtfsCsvRows) {
            val routeIndex = rows.header.indexOf("route_id")
            val serviceIndex = rows.header.indexOf("service_id")
            val tripIndex = rows.header.indexOf("trip_id")
            val shortNameIndex = rows.header.indexOf("trip_short_name")
            rows.forEachRow(limits.maxTrips) { row ->
                val tripId = row.gtfsRawValue(tripIndex)
                trips.configure(
                    tripId = tripId,
                    routeId = row.gtfsRawValue(routeIndex),
                    serviceId = row.gtfsRawValue(serviceIndex),
                    shortName = row.getOrNull(shortNameIndex)
                )
            }
        }

        fun readStopTimes(rows: GtfsCsvRows) {
            val tripIndex = rows.header.indexOf("trip_id")
            val arrivalIndex = rows.header.indexOf("arrival_time")
            val departureIndex = rows.header.indexOf("departure_time")
            val stopIndex = rows.header.indexOf("stop_id")
            val sequenceIndex = rows.header.indexOf("stop_sequence")
            val rowsRead = rows.forEachRow(limits.maxStopTimeRows) { row ->
                val trip = trips.indexIfDeclared(row.gtfsRawValue(tripIndex)) ?: return@forEachRow
                val entry = stopTimes.append(
                    trip = trip,
                    stop = stops.indexFor(row.gtfsRawValue(stopIndex)),
                    sequence = row.getOrNull(sequenceIndex)?.toIntOrNull() ?: Int.MAX_VALUE,
                    arrival = gtfsTimeToMinutes(row.getOrNull(arrivalIndex)),
                    departure = gtfsTimeToMinutes(row.getOrNull(departureIndex)),
                    previousForTrip = trips.head(trip)
                )
                trips.setHead(trip, entry)
            }
            stopTimes.recordRowsRead(rowsRead)
        }

        fun readCalendars(rows: GtfsCsvRows) {
            val serviceIndex = rows.header.indexOf("service_id")
            val startIndex = rows.header.indexOf("start_date")
            val endIndex = rows.header.indexOf("end_date")
            val weekdayIndexes = listOf(
                rows.header.indexOf("monday"), rows.header.indexOf("tuesday"), rows.header.indexOf("wednesday"),
                rows.header.indexOf("thursday"), rows.header.indexOf("friday"), rows.header.indexOf("saturday"),
                rows.header.indexOf("sunday")
            )
            rows.forEachRow(limits.maxCalendars) { row ->
                var activeDays = 0
                weekdayIndexes.forEachIndexed { day, index ->
                    if (row.getOrNull(index) == "1") activeDays = activeDays or (1 shl day)
                }
                calendars[row.gtfsRawValue(serviceIndex)] = CompactCalendar(
                    startDate = gtfsDateToInt(row.gtfsRawValue(startIndex)),
                    endDate = gtfsDateToInt(row.gtfsRawValue(endIndex)),
                    activeDays = activeDays
                )
            }
        }

        fun readCalendarDates(rows: GtfsCsvRows) {
            val serviceIndex = rows.header.indexOf("service_id")
            val dateIndex = rows.header.indexOf("date")
            val exceptionIndex = rows.header.indexOf("exception_type")
            rows.forEachRow(limits.maxCalendarDateRows) { row ->
                val date = gtfsDateToInt(row.gtfsRawValue(dateIndex))
                if (date != null) {
                    calendarDates.getOrPut(row.gtfsRawValue(serviceIndex)) { CompactCalendarExceptions() }
                        .add(date, row.gtfsRawValue(exceptionIndex))
                }
            }
        }

        fun services(origin: String, destination: String, sourceId: String, query: JourneyQuery): List<TrainService> {
            val wantedOrigin = normalize(origin)
            val wantedDestination = normalize(destination)
            val selected = ArrayList<TrainService>()
            // GTFS usa date di servizio: venerdì 25:10 è sabato 01:10. Due
            // giorni precedenti coprono anche continuazioni oltre 48:00.
            val serviceDates = listOf(
                query.serviceDate,
                query.serviceDate.minusDays(1),
                query.serviceDate.minusDays(2)
            )
            serviceDates.forEach { serviceDate ->
                trips.forEachDeclared { tripIndex, trip ->
                    if (!serviceRunsOn(trip.serviceId, serviceDate)) return@forEachDeclared
                    val segment = findSegment(tripIndex, wantedOrigin, wantedDestination) ?: return@forEachDeclared
                    val departureMinutes = stopTimes.preferredDeparture(segment.originEntry)
                    if (departureMinutes < 0) return@forEachDeclared
                    val scheduledDateTime = serviceDate.atStartOfDay().plusMinutes(departureMinutes.toLong())
                    if (scheduledDateTime.isBefore(query.requestedAt)) return@forEachDeclared
                    if (selected.size >= limits.maxServicesPerPack && isAfterCurrentWorst(scheduledDateTime, trip, selected)) {
                        return@forEachDeclared
                    }
                    offerBounded(selected, buildService(segment, trip, sourceId, serviceDate, scheduledDateTime), limits.maxServicesPerPack)
                }
            }
            return selected.sortedWith(compareBy<TrainService> { it.scheduledDepartureDateTime }.thenBy { it.number })
        }

        private fun isAfterCurrentWorst(
            scheduledDateTime: java.time.LocalDateTime,
            trip: CompactTrip,
            selected: List<TrainService>
        ): Boolean {
            val worst = selected.maxWithOrNull(
                compareBy<TrainService> { it.scheduledDepartureDateTime ?: java.time.LocalDateTime.MAX }.thenBy { it.number }
            ) ?: return false
            val number = trip.shortName?.takeIf(String::isNotBlank) ?: trip.id
            return compareValuesBy(
                TrainServiceSortKey(scheduledDateTime, number),
                TrainServiceSortKey(worst.scheduledDepartureDateTime ?: java.time.LocalDateTime.MAX, worst.number),
                TrainServiceSortKey::scheduledDateTime,
                TrainServiceSortKey::number
            ) > 0
        }

        private fun offerBounded(selected: MutableList<TrainService>, candidate: TrainService, maximum: Int) {
            selected += candidate
            if (selected.size > maximum) {
                val worst = selected.indices.maxWithOrNull(
                    compareBy<Int> { selected[it].scheduledDepartureDateTime ?: java.time.LocalDateTime.MAX }.thenBy { selected[it].number }
                ) ?: return
                selected.removeAt(worst)
            }
        }

        private fun findSegment(tripIndex: Int, wantedOrigin: String, wantedDestination: String): CompactSegment? {
            var origin = -1
            var entry = trips.head(tripIndex)
            while (entry >= 0) {
                if (stops.normalizedName(stopTimes.stop(entry)) == wantedOrigin && (origin < 0 || stopTimes.comesBefore(entry, origin))) {
                    origin = entry
                }
                entry = stopTimes.next(entry)
            }
            if (origin < 0) return null

            var destination = -1
            entry = trips.head(tripIndex)
            while (entry >= 0) {
                if (
                    stops.normalizedName(stopTimes.stop(entry)) == wantedDestination &&
                    stopTimes.comesBefore(origin, entry) &&
                    (destination < 0 || stopTimes.comesBefore(entry, destination))
                ) {
                    destination = entry
                }
                entry = stopTimes.next(entry)
            }
            return destination.takeIf { it >= 0 }?.let { CompactSegment(origin, it, tripIndex) }
        }

        private fun buildService(
            segment: CompactSegment,
            trip: CompactTrip,
            sourceId: String,
            serviceDate: java.time.LocalDate,
            scheduledDateTime: java.time.LocalDateTime
        ): TrainService {
            val route = routes[trip.routeId]
            val departureMinutes = stopTimes.preferredDeparture(segment.originEntry)
            val segmentEntries = segmentEntries(segment)
            val agencyName = agencyName(route)
            val number = trip.shortName?.takeIf(String::isNotBlank) ?: trip.id
            return TrainService(
                id = "$sourceId:${trip.id}:$serviceDate",
                number = number,
                operator = legacyOperator(sourceId, agencyName, route),
                operatorDisplayName = canonicalAgencyLabel(agencyName, sourceId),
                trainCategory = category(route?.type, route, agencyName),
                origin = stops.name(stopTimes.stop(segment.originEntry)),
                destination = stops.name(stopTimes.stop(segment.destinationEntry)),
                scheduledDeparture = hhmm(departureMinutes),
                expectedDeparture = hhmm(departureMinutes),
                platform = null,
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                operationalStatusVerified = false,
                scheduledDepartureDateTime = scheduledDateTime,
                stops = segmentEntries.map { entry ->
                    val scheduled = hhmm(stopTimes.preferredDeparture(entry))
                    TrainStop(stops.name(stopTimes.stop(entry)), scheduled, scheduled, null, 0, false)
                }
            )
        }

        private fun segmentEntries(segment: CompactSegment): List<Int> {
            val entries = ArrayList<Int>()
            var entry = trips.head(segment.tripIndex)
            while (entry >= 0) {
                if (!stopTimes.comesBefore(entry, segment.originEntry) && !stopTimes.comesBefore(segment.destinationEntry, entry)) {
                    entries += entry
                }
                entry = stopTimes.next(entry)
            }
            entries.sortWith(compareBy<Int> { stopTimes.sequence(it) }.thenBy { stopTimes.ordinal(it) })
            return entries
        }

        private fun agencyName(route: CompactRoute?): String? {
            val agencyId = route?.agencyId.orEmpty().trim()
            return agencies[agencyId] ?: agencies.takeIf { it.size == 1 }?.values?.singleOrNull()
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

        private fun legacyOperator(sourceId: String, agencyName: String?, route: CompactRoute?): RailOperator {
            val identity = normalize(listOfNotNull(agencyName, route?.shortName, route?.longName).joinToString(" "))
            return when {
                identity.contains("trenord") || sourceId == "lombardia-trenord-gtfs" -> RailOperator.TRENORD
                identity.contains("italo") || identity.contains("ntv") -> RailOperator.ITALO
                identity.contains("frecc") -> RailOperator.TRENITALIA_AV
                identity.contains("intercity") || identity.contains("trenitalia") -> RailOperator.TRENITALIA_LONG_DISTANCE
                else -> RailOperator.OTHER
            }
        }

        private fun category(routeType: String?, route: CompactRoute?, agencyName: String?): TrainCategory {
            val identity = normalize(listOfNotNull(route?.shortName, route?.longName, agencyName).joinToString(" "))
            return when {
                identity.contains("italo") -> TrainCategory.AV_ITALO
                identity.contains("frecc") -> TrainCategory.FRECCE
                identity.contains("intercity") || Regex("(^|[^a-z])icn?([^a-z]|$)").containsMatchIn(identity) -> TrainCategory.INTERCITY
                identity.contains("regionaleveloce") -> TrainCategory.REGIONALE_VELOCE
                identity.contains("trenord") -> TrainCategory.TRENORD_REGIO
                routeType in RAIL_ROUTE_TYPES -> TrainCategory.REGIONALE
                else -> TrainCategory.OTHER
            }
        }

        private fun serviceRunsOn(serviceId: String, date: LocalDate): Boolean {
            when (calendarDates[serviceId]?.exception(date.toGtfsInt())) {
                "1" -> return true
                "2" -> return false
            }
            val calendar = calendars[serviceId] ?: return false
            val encodedDate = date.toGtfsInt()
            val startDate = calendar.startDate ?: return false
            val endDate = calendar.endDate ?: return false
            if (encodedDate !in startDate..endDate) return false
            return calendar.activeDays and (1 shl date.dayOfWeek.ordinal) != 0
        }

        fun stats(): GtfsCompactIndexStats = GtfsCompactIndexStats(
            stopTimeRowsRead = stopTimes.rowsRead(),
            indexedStopTimeRows = stopTimes.size(),
            stopTimeColumnBytes = stopTimes.compactColumnBytes(),
            stops = stops.size(),
            trips = trips.size()
        )

        private data class CompactRoute(val agencyId: String, val shortName: String, val longName: String, val type: String?)
        private data class CompactCalendar(val startDate: Int?, val endDate: Int?, val activeDays: Int)
        private data class CompactSegment(val originEntry: Int, val destinationEntry: Int, val tripIndex: Int)
        private data class TrainServiceSortKey(val scheduledDateTime: java.time.LocalDateTime, val number: String)

        private class CompactStops(private val maximum: Int) {
            private val indexById = HashMap<String, Int>()
            private val names = ArrayList<String>()
            private val normalizedNames = ArrayList<String>()

            fun put(id: String, name: String) {
                val existing = indexById[id]
                if (existing != null) {
                    names[existing] = name
                    normalizedNames[existing] = normalize(name)
                    return
                }
                if (names.size >= maximum) throw GtfsCompactIndexLimitException("stops.txt supera il limite di stazioni")
                indexById[id] = names.size
                names += name
                normalizedNames += normalize(name)
            }

            /**
             * Alcuni pack pongono stop_times.txt prima di stops.txt. Creiamo
             * quindi uno slot senza etichetta e lo completiamo quando la entry
             * delle fermate viene letta, senza trattenere stop_id per riga.
             */
            fun indexFor(id: String): Int = indexById[id] ?: run {
                put(id, "")
                indexById.getValue(id)
            }
            fun name(index: Int): String = names.getOrNull(index).orEmpty()
            fun normalizedName(index: Int): String = normalizedNames.getOrNull(index).orEmpty()
            fun size(): Int = names.size
        }

        private class CompactTrips(private val maximum: Int) {
            private val indexById = HashMap<String, Int>()
            private val items = ArrayList<CompactTrip>()
            private var heads = IntArray(1) { -1 }

            fun indexFor(id: String): Int = indexById[id] ?: add(id)

            fun indexIfDeclared(id: String): Int? = indexById[id]?.takeIf { items[it].declared }

            fun configure(tripId: String, routeId: String, serviceId: String, shortName: String?) {
                val trip = items[indexFor(tripId)]
                trip.routeId = routeId
                trip.serviceId = serviceId
                trip.shortName = shortName
                trip.declared = true
            }

            fun head(index: Int): Int = heads[index]
            fun setHead(index: Int, entry: Int) { heads[index] = entry }

            fun forEachDeclared(block: (Int, CompactTrip) -> Unit) {
                items.forEachIndexed { index, trip -> if (trip.declared) block(index, trip) }
            }

            fun size(): Int = items.size

            private fun add(id: String): Int {
                if (items.size >= maximum) throw GtfsCompactIndexLimitException("trips.txt supera il limite di corse")
                val index = items.size
                indexById[id] = index
                items += CompactTrip(id)
                if (index >= heads.size) heads = heads.copyOf((heads.size * 2).coerceAtLeast(index + 1)).also { it.fill(-1, index, it.size) }
                return index
            }
        }

        private class CompactTrip(val id: String) {
            var routeId: String = ""
            var serviceId: String = ""
            var shortName: String? = null
            var declared: Boolean = false
        }

        private class CompactStopTimes(private val maximum: Int) {
            private var trips = IntArray(1024)
            private var stops = IntArray(1024)
            private var sequences = IntArray(1024)
            private var arrivals = IntArray(1024)
            private var departures = IntArray(1024)
            private var next = IntArray(1024)
            private var count = 0
            private var sourceRowsRead = 0

            fun append(trip: Int, stop: Int, sequence: Int, arrival: Int, departure: Int, previousForTrip: Int): Int {
                if (count >= maximum) throw GtfsCompactIndexLimitException("stop_times.txt supera il limite di righe")
                ensureCapacity(count + 1)
                trips[count] = trip
                stops[count] = stop
                sequences[count] = sequence
                arrivals[count] = arrival
                departures[count] = departure
                next[count] = previousForTrip
                return count++
            }

            fun stop(index: Int): Int = stops[index]
            fun sequence(index: Int): Int = sequences[index]
            fun next(index: Int): Int = next[index]
            fun ordinal(index: Int): Int = index
            fun comesBefore(first: Int, second: Int): Boolean =
                sequence(first) < sequence(second) || (sequence(first) == sequence(second) && ordinal(first) < ordinal(second))

            fun preferredDeparture(index: Int): Int = departures[index].takeUnless { it == MISSING_TIME } ?: arrivals[index]
            fun recordRowsRead(value: Int) { sourceRowsRead = value }
            fun rowsRead(): Int = sourceRowsRead
            fun size(): Int = count
            fun compactColumnBytes(): Long = count.toLong() * Int.SIZE_BYTES * 6L

            private fun ensureCapacity(required: Int) {
                if (required <= trips.size) return
                val capacity = (trips.size * 2).coerceAtLeast(required).coerceAtMost(maximum)
                trips = trips.copyOf(capacity)
                stops = stops.copyOf(capacity)
                sequences = sequences.copyOf(capacity)
                arrivals = arrivals.copyOf(capacity)
                departures = departures.copyOf(capacity)
                next = next.copyOf(capacity)
            }
        }

        private class CompactCalendarExceptions {
            private var dates = IntArray(8)
            private var types = arrayOfNulls<String>(8)
            private var count = 0

            fun add(date: Int, type: String) {
                if (count == dates.size) {
                    dates = dates.copyOf(dates.size * 2)
                    types = types.copyOf(types.size * 2)
                }
                dates[count] = date
                types[count] = type
                count += 1
            }

            fun exception(date: Int): String? {
                for (index in count - 1 downTo 0) if (dates[index] == date) return types[index]
                return null
            }
        }
    }

    private companion object {
        private const val MISSING_TIME = Int.MIN_VALUE
        private const val INVALID_TIME = Int.MIN_VALUE + 1
        private val RAIL_ROUTE_TYPES = setOf("2", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109")
        private val compactIndexTables = setOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt")
        private val compactIndexLimits = GtfsCompactIndexLimits()

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]".toRegex(), "")

        fun gtfsTimeToMinutes(value: String?): Int {
            if (value == null) return MISSING_TIME
            val parts = value.split(':')
            val hours = parts.getOrNull(0)?.toIntOrNull() ?: return INVALID_TIME
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return INVALID_TIME
            return if (hours < 0 || minutes !in 0..59) INVALID_TIME else hours * 60 + minutes
        }

        fun hhmm(minutes: Int): String {
            if (minutes < 0) return "00:00"
            return "%02d:%02d".format(Locale.ROOT, (minutes / 60) % 24, minutes % 60)
        }

        fun gtfsDateToInt(value: String): Int? =
            value.takeIf { it.matches(Regex("\\d{8}")) }?.toIntOrNull()

        fun LocalDate.toGtfsInt(): Int = year * 10_000 + monthValue * 100 + dayOfMonth
    }

}
