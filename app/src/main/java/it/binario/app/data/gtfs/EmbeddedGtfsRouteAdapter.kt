package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SourceId
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberMatch
import it.binario.app.data.planned.BoundedSingleFlightCache
import it.binario.app.data.sources.PlannedPackOrder
import it.binario.app.data.sources.RailSourceCatalog
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

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
    private val onParsing: (() -> Unit)? = null,
    /**
     * Compact indexes kept in memory. Three by default: a route served by three packs (the national Trenitalia pack
     * next to a regional and a cross-border one, as Bolzano–Verona) would otherwise read one of them again at every
     * search. The journey planner keeps one, because it caches the much smaller per-day connection arrays it derives
     * from it.
     */
    private val indexCacheCapacity: Int = 3,
    /**
     * The station catalogue, read once and only for packs that publish station codes
     * ([EmbeddedGtfsPack.stopCodeScheme]): their stops are then matched to the requested stations by code before
     * name ("BOLOGNA C.LE", "Padua"). Null keeps name matching only.
     */
    private val stationCatalog: (suspend () -> List<Station>)? = null
) : TrainDataSource {
    init {
        require(indexCacheCapacity > 0) { "Serve almeno un indice GTFS in cache" }
    }

    /** Al più [indexCacheCapacity] indici compatti (LRU). */
    private val indexCache = object : LinkedHashMap<SourceId, StaticGtfsIndex>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, StaticGtfsIndex>?): Boolean = size > indexCacheCapacity
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
    private val codeNamesMutex = Mutex()
    @Volatile private var codeIndex: GtfsStationCodeIndex? = null
    /** Per code-publishing pack: catalogue station id → the pack's stop names bound to it by code. */
    private val codeNamesCache = HashMap<SourceId, Map<String, List<String>>>()
    private val numberCache = BoundedSingleFlightCache<List<TrainNumberMatch>>(40, 6 * 60 * 60_000L, clock)
    /**
     * The train numbers of each pack ([GtfsPackNumbers]), learnt by the first number lookup that reads the pack: every
     * later lookup opens only the packs that have the number, instead of reading all of them again.
     */
    private val packNumbers = HashMap<SourceId, GtfsPackNumbers>()
    /** Held while a pack is read for a number before its numbers are known. */
    private val numbersMutex = Mutex()

    /** Diagnostics for tests: the packs whose numbers are known. */
    internal fun knownNumberPacksForTest(): Set<SourceId> = synchronized(packNumbers) { packNumbers.keys.toSet() }

    /** Diagnostica interna usata dal test di regressione a heap limitato. */
    internal fun compactIndexStats(): List<GtfsCompactIndexStats> = synchronized(indexCache) {
        indexCache.values.map(StaticGtfsIndex::stats)
    }

    /** Diagnostica interna: la ricerca senza source id deve poter ricordare ogni pack configurato. */
    internal fun stopPresenceCacheSizeForTest(): Int = synchronized(stopPresenceCache) { stopPresenceCache.size }

    suspend fun searchByNumber(query: TrainNumberQuery, onMatches: suspend (List<TrainNumberMatch>) -> Unit = {}): List<TrainNumberMatch> = withContext(parsingDispatcher) {
        if (!query.valid) return@withContext emptyList()
        buildList {
            for (pack in packs) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (pack.lastServiceDate?.isBefore(query.date.minusDays(2)) == true) continue
                val source = RailSourceCatalog.byId(pack.sourceId) ?: continue
                try {
                    val matches = numberCache.getOrLoad("${pack.sourceId.value}:${query.normalized}:${query.date}", cancelWhenUnused = true) {
                        // A pack already read for another number says at once whether it has this one. Lookups started
                        // together (the trains of a results screen) read a pack one at a time while its numbers are
                        // not known, so only the first of them reads the packs that have none of their numbers.
                        suspend fun read(known: GtfsPackNumbers?): List<TrainNumberMatch> {
                            if (known != null && !known.mayContain(query)) return emptyList()
                            val bytes = assetBytes(pack.assetPath)
                            val numbers = if (known == null) GtfsPackNumbers.Builder() else null
                            val job = currentCoroutineContext().job
                            val index = parse({ ByteArrayInputStream(bytes) }, query, pack.itineraryCopies, numbers) { job.ensureActive() }
                            if (numbers != null) {
                                val learnt = numbers.build(query.date)
                                synchronized(packNumbers) { packNumbers.putIfAbsent(pack.sourceId, learnt) }
                            }
                            val now = clock()
                            return index.servicesByNumber(pack.sourceId.value, query).map { service ->
                                TrainNumberMatch.create(service, source.displayName, DataConfidence.STATIC_ONLY, now, now + 12 * 60 * 60_000)
                            }
                        }
                        val known = synchronized(packNumbers) { packNumbers[pack.sourceId] }
                        if (known != null) read(known)
                        else numbersMutex.withLock { read(synchronized(packNumbers) { packNumbers[pack.sourceId] }) }
                    }.value
                    addAll(matches)
                    if (matches.isNotEmpty()) onMatches(toList())
                } catch (_: GtfsCompactIndexLimitException) { }
                catch (_: java.io.IOException) { }
            }
        }
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    /**
     * The catalogue names of a station, then the same names without the operator suffix the pack's stations carry in
     * the catalogue ([EmbeddedGtfsPack.catalogueNameSuffixes]: "Matera Centrale FAL" is "Matera Centrale" in the FAL
     * pack). Packs without suffixes get the names unchanged.
     */
    private fun withoutSuffixes(names: List<String>, pack: EmbeddedGtfsPack): List<String> {
        if (pack.catalogueNameSuffixes.isEmpty()) return names
        val stripped = names.mapNotNull { name ->
            pack.catalogueNameSuffixes.firstNotNullOfOrNull { suffix -> name.removeSuffix(" $suffix").takeIf { it != name && it.isNotBlank() } }
        }
        return names + stripped
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val originStation = route.origin(direction)
        val destinationStation = route.destination(direction)
        // GTFS stop names are matched against the catalogue name first and then its aliases:
        // bilingual and operator-specific labels ("Meran/Merano", "Milano Cadorna FN") live
        // there, and matching the display name alone left served stations unplannable
        // (Opus audit N-M5).
        val originNames = gtfsCandidateNames(originStation)
        val destinationNames = gtfsCandidateNames(destinationStation)

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
                    if (explicitSourceIds != null && pack.sourceId !in explicitSourceIds) continue
                    try {
                        val stops = stopNames(pack)
                        // A pack that publishes station codes is matched by code first: its own spelling of the
                        // station ("BOLOGNA C.LE") is then the name the index searches.
                        val byCode = codeNames(pack)
                        val origin = (byCode[originStation.id] ?: withoutSuffixes(originNames, pack)).firstOrNull { normalize(it) in stops }
                        val destination = (byCode[destinationStation.id] ?: withoutSuffixes(destinationNames, pack)).firstOrNull { normalize(it) in stops }
                        when {
                            origin != null && destination != null -> add(CandidatePack(pack, origin, destination))
                            explicitSourceIds != null -> add(CandidatePack(pack, originStation.name, destinationStation.name))
                        }
                    } catch (_: GtfsCompactIndexLimitException) {
                        // An oversized/malformed pack cannot be a candidate.
                    }
                }
            }
        }

        if (candidatePacks.isEmpty()) {
            return DataResult.Failure("Nessun data pack GTFS contiene entrambe le stazioni richieste.", RouteFailureReason.RouteNotCovered)
        }

        val collected = withContext(parsingDispatcher) {
            buildList {
                for (candidate in candidatePacks) {
                    val pack = candidate.pack
                    val source = RailSourceCatalog.byId(pack.sourceId) ?: continue
                    val index = try {
                        indexFor(pack)
                    } catch (_: GtfsCompactIndexLimitException) {
                        continue
                    }
                    index.services(
                        candidate.originStopName, candidate.destinationStopName, source.id.value, query, pack.busRoutesAreScheduledCoaches
                    ).forEach { service ->
                        add(SourcedService(source.displayName, pack.sourceId, service))
                    }
                }
            }
        }

        if (collected.isEmpty()) {
            val expiredPacks = candidatePacks.map(CandidatePack::pack).filter { pack -> pack.lastServiceDate?.isBefore(query.serviceDate) == true }
            if (expiredPacks.isNotEmpty()) {
                val validity = expiredPacks.joinToString(" e ") { pack ->
                    val label = RailSourceCatalog.byId(pack.sourceId)?.displayName ?: pack.sourceId.value
                    "$label sono validi fino al ${pack.lastServiceDate}"
                }
                return DataResult.Failure(
                    "Gli orari inclusi per $validity: aggiorna l'app per orari più recenti.",
                    RouteFailureReason.ScheduleExpired(expiredPacks.mapNotNull { it.lastServiceDate }.max())
                )
            }
            return DataResult.Failure(
                "Nessuna corsa programmata trovata nei data pack GTFS compatibili per data e orario selezionati.",
                RouteFailureReason.NoServiceAfter(query.serviceDate, query.departureTime)
            )
        }

        val services = withContext(parsingDispatcher) {
            collected
                .sortedWith(
                    compareBy<SourcedService> { it.service.scheduledDepartureDateTime }
                        .then(plannedPackOrder)
                        .thenBy { it.service.number }
                )
                .distinctBy { item -> serviceIdentity(item.service) }
                .let(::withoutCrossPackDuplicates)
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

    /**
     * Every connection (consecutive timed stops of one trip) of [pack] that runs on [serviceDate] and departs
     * between [fromMinute] and [untilMinute] inclusive, built from the same cached compact index the route search
     * uses. Times are GTFS minutes from the service day's midnight, so after-midnight runs keep values of 24:00 and
     * beyond. Runs on the parsing dispatcher and is cancellable.
     */
    internal suspend fun dayTimetable(
        pack: EmbeddedGtfsPack,
        serviceDate: LocalDate,
        fromMinute: Int = 0,
        untilMinute: Int = Int.MAX_VALUE
    ): GtfsDayTimetable {
        val index = indexFor(pack)
        return withContext(parsingDispatcher) {
            val job = currentCoroutineContext().job
            index.dayTimetable(pack.sourceId, serviceDate, fromMinute, untilMinute, pack.busRoutesAreScheduledCoaches) { job.ensureActive() }
        }
    }

    /** stop_id, stop_name and parent_station of [pack], read with the same bounded scanner as the index. */
    internal suspend fun stopDirectory(pack: EmbeddedGtfsPack): GtfsStopDirectory {
        val bytes = assetBytes(pack.assetPath)
        return withContext(parsingDispatcher) {
            val job = currentCoroutineContext().job
            scanStopDirectory(ByteArrayInputStream(bytes)) { job.ensureActive() }
        }
    }

    /**
     * For a pack with [EmbeddedGtfsPack.stopCodeScheme], the stop names its codes bind to each catalogue station
     * (see [GtfsStationCodeIndex]); empty for the other packs or without [stationCatalog]. Built once per pack.
     */
    private suspend fun codeNames(pack: EmbeddedGtfsPack): Map<String, List<String>> {
        if (pack.stopCodeScheme == GtfsStopCodeScheme.NONE) return emptyMap()
        val catalog = stationCatalog ?: return emptyMap()
        synchronized(codeNamesCache) { codeNamesCache[pack.sourceId] }?.let { return it }
        return codeNamesMutex.withLock {
            synchronized(codeNamesCache) { codeNamesCache[pack.sourceId] } ?: run {
                val index = codeIndex ?: try {
                    GtfsStationCodeIndex(catalog()).also { codeIndex = it }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Without a readable catalogue the pack is still matched by name, as before.
                    return@withLock emptyMap()
                }
                val directory = stopDirectory(pack)
                val names = withContext(parsingDispatcher) {
                    val byStation = LinkedHashMap<String, LinkedHashSet<String>>()
                    for (stop in 0 until directory.size) {
                        val station = index.resolve(
                            pack.stopCodeScheme, directory.codes[stop], directory.latitude(stop), directory.longitude(stop), directory.names[stop]
                        ) ?: continue
                        directory.names[stop].takeIf(String::isNotBlank)?.let { byStation.getOrPut(station.id) { linkedSetOf() } += it }
                    }
                    byStation.mapValues { it.value.toList() }
                }
                synchronized(codeNamesCache) { codeNamesCache[pack.sourceId] ?: names.also { codeNamesCache[pack.sourceId] = it } }
            }
        }
    }

    private suspend fun stopNames(pack: EmbeddedGtfsPack): Set<String> {
        synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] }?.let { return it }
        return stopPresenceMutex.withLock {
            synchronized(stopPresenceCache) { stopPresenceCache[pack.sourceId] } ?: run {
                val bytes = assetBytes(pack.assetPath)
                val names = withContext(parsingDispatcher) {
                    onParsing?.invoke()
                    val job = currentCoroutineContext().job
                    scanStopNames(ByteArrayInputStream(bytes)) { job.ensureActive() }
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
                    val job = currentCoroutineContext().job
                    parse({ ByteArrayInputStream(bytes) }, itineraryCopies = pack.itineraryCopies) { job.ensureActive() }
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

    /**
     * Cross-border trains are published by more than one pack under unrelated numbers (Trenord
     * "S30 - 25318", Swiss OTD "25358") and with different stop spellings, so [serviceIdentity]
     * cannot pair them. Between the same requested stations, two services from different packs
     * leaving and arriving at the same minute are one train when [mayBeSameRun] says so: only the
     * first, which the sort puts on the preferred source, is kept. Two different trains can share
     * the minutes (REG 16348 and Italo 8984, Venezia S. Lucia 13:57 → Mestre 14:09), so the times
     * alone are not enough (audit android-data-6). Services of the same pack are never merged here.
     */
    private fun withoutCrossPackDuplicates(items: List<SourcedService>): List<SourcedService> {
        val keptByTimes = HashMap<String, MutableList<SourcedService>>()
        return items.filter { item ->
            val service = item.service
            val departure = service.scheduledDepartureDateTime ?: return@filter true
            val arrival = service.stops.lastOrNull()?.scheduled ?: return@filter true
            val kept = keptByTimes.getOrPut("$departure|$arrival") { mutableListOf() }
            if (kept.any { other -> other.sourceId != item.sourceId && mayBeSameRun(other, item) }) return@filter false
            kept += item
            true
        }
    }

    /**
     * Two services of different packs at the same minutes are the same run when their public numbers match, when the
     * operator is the same, or when one of the packs republishes partner operators' trains under its own numbering
     * ([CROSS_PUBLISHING_PACKS]). Otherwise (two Italian operators' own feeds, different numbers) they are two trains.
     */
    private fun mayBeSameRun(first: SourcedService, second: SourcedService): Boolean {
        val number = coreTrainNumber(first.service.number)
        if (number != null && number == coreTrainNumber(second.service.number)) return true
        val operator = first.service.operatorDisplayName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        if (operator != null && operator == second.service.operatorDisplayName?.trim()?.lowercase(Locale.ROOT)) return true
        return first.sourceId.value in CROSS_PUBLISHING_PACKS || second.sourceId.value in CROSS_PUBLISHING_PACKS
    }

    /** The run number without line prefix or category: "S11 - 25017" → "25017", "REX 1826" → "1826". */
    private fun coreTrainNumber(value: String): String? =
        Regex("[0-9]+").findAll(value.substringAfterLast(" - ")).lastOrNull()?.value?.trimStart('0')?.takeIf(String::isNotEmpty)

    /**
     * Which copy of a train two packs both list is kept: the planned order of the packs ([PlannedPackOrder], as in the
     * journey planner), with the operator of the service for the affinity within a class. Every candidate pack serves
     * both stations (a pack matching only one is not read), so the planner's border exception (the packs serving an end
     * that no national pack serves come first) would select every candidate and leave the default order unchanged.
     */
    private val plannedPackOrder: Comparator<SourcedService> =
        PlannedPackOrder.comparator(sourceId = { it.sourceId }, operatorHint = { it.service.operatorDisplayName })

    private data class CandidatePack(val pack: EmbeddedGtfsPack, val originStopName: String, val destinationStopName: String)

    /**
     * The catalogue name plus the aliases that are other spellings of the same station. Aliases
     * that share no word with the name are translations ("Bologne", "Mailand", "Chaumont" for
     * Chiomonte) and would bind a namesake stop abroad in the SNCF or ÖBB packs, so they are not
     * used; neither are code-like aliases.
     */
    private fun gtfsCandidateNames(station: Station): List<String> {
        val nameWords = RailStopNameMatcher.tokens(station.name).filter { it.length >= 4 }.toSet()
        return (listOf(station.name) + station.aliases.filter { alias ->
            !alias.contains('_') && !alias.startsWith("@") && normalize(alias).length >= 3 &&
                RailStopNameMatcher.tokens(alias).any { it in nameWords }
        }).distinctBy(::normalize)
    }

    private fun normalizeTrainNumber(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]"), "")

    private fun sourceIdFromGtfsStationId(stationId: String): SourceId? {
        if (!stationId.startsWith("gtfs:")) return null
        val source = stationId.removePrefix("gtfs:").substringBefore(':').takeIf(String::isNotBlank) ?: return null
        return SourceId(source)
    }

    private fun scanStopNames(stream: InputStream, checkpoint: () -> Unit = {}): Set<String> {
        val names = linkedSetOf<String>()
        BoundedGtfsZipScanner(compactIndexLimits, checkpoint).scan(stream, setOf("stops.txt")) { _, rows ->
            val nameIndex = rows.header.indexOf("stop_name")
            if (nameIndex >= 0) {
                rows.forEachRow(compactIndexLimits.maxStops) { row ->
                    row.getOrNull(nameIndex)?.takeIf(String::isNotBlank)?.let { names += normalize(it) }
                }
            }
        }
        return names
    }

    private fun scanStopDirectory(stream: InputStream, checkpoint: () -> Unit = {}): GtfsStopDirectory {
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        val parents = ArrayList<String>()
        val codes = ArrayList<String>()
        val latitudes = ArrayList<Double>()
        val longitudes = ArrayList<Double>()
        val wheelchair = ArrayList<Byte>()
        BoundedGtfsZipScanner(compactIndexLimits, checkpoint).scan(stream, setOf("stops.txt")) { _, rows ->
            val idIndex = rows.header.indexOf("stop_id")
            val nameIndex = rows.header.indexOf("stop_name")
            val parentIndex = rows.header.indexOf("parent_station")
            val codeIndex = rows.header.indexOf("stop_code")
            val latitudeIndex = rows.header.indexOf("stop_lat")
            val longitudeIndex = rows.header.indexOf("stop_lon")
            val wheelchairIndex = rows.header.indexOf("wheelchair_boarding")
            if (idIndex >= 0 && nameIndex >= 0) {
                rows.forEachRow(compactIndexLimits.maxStops) { row ->
                    val id = row.gtfsRawValue(idIndex)
                    if (id.isNotEmpty()) {
                        ids += id
                        names += row.gtfsValue(nameIndex)
                        parents += row.gtfsValue(parentIndex)
                        codes += row.gtfsValue(codeIndex)
                        latitudes += row.gtfsValue(latitudeIndex).toDoubleOrNull() ?: Double.NaN
                        longitudes += row.gtfsValue(longitudeIndex).toDoubleOrNull() ?: Double.NaN
                        wheelchair += wheelchairValue(row.getOrNull(wheelchairIndex))
                    }
                }
            }
        }
        val idArray = ids.toTypedArray()
        val parentArray = parents.toTypedArray()
        return GtfsStopDirectory(
            idArray, names.toTypedArray(), parentArray, codes.toTypedArray(),
            latitudes.toDoubleArray(), longitudes.toDoubleArray(),
            GtfsStopDirectory.inheritWheelchair(idArray, parentArray, wheelchair.toByteArray())
        )
    }

    /**
     * Costruisce un indice a colonne. Non esiste alcun passaggio che trasformi
     * una tabella GTFS in List<Map<String, String>>: ogni riga viene consumata
     * subito e le sole colonne necessarie alla tratta sono mantenute in array.
     */
    private fun parse(
        openStream: () -> InputStream,
        numberQuery: TrainNumberQuery? = null,
        itineraryCopies: Boolean = false,
        /** Collects the number of every trip of the pack, also of those [numberQuery] leaves out. */
        numbers: GtfsPackNumbers.Builder? = null,
        /** Stops the reading when the search it serves was cancelled ([BoundedGtfsZipScanner]). */
        checkpoint: () -> Unit = {}
    ): StaticGtfsIndex {
        val index = StaticGtfsIndex(compactIndexLimits, itineraryCopies)
        // A copy of a train under another train's number is only told from the train itself next to the other trips
        // of the day: such a pack (a small one) is read whole even for one number.
        val tripFilter = numberQuery.takeUnless { itineraryCopies }
        BoundedGtfsZipScanner(compactIndexLimits, checkpoint).scan(openStream(), compactIndexTables - "stop_times.txt") { table, rows ->
            when (table) {
                "agency.txt" -> index.readAgencies(rows)
                "stops.txt" -> index.readStops(rows)
                "routes.txt" -> index.readRoutes(rows)
                "trips.txt" -> index.readTrips(rows, tripFilter, numbers)
                "calendar.txt" -> index.readCalendars(rows)
                "calendar_dates.txt" -> index.readCalendarDates(rows)
            }
        }
        // Seconda passata: trips è ormai completo anche quando stop_times è la
        // prima entry (come nel pack AMT), quindi non indicizziamo trip ignoti.
        index.finishReading()
        if (numberQuery == null || index.hasTrips()) {
            BoundedGtfsZipScanner(compactIndexLimits, checkpoint).scan(openStream(), setOf("stop_times.txt")) { _, rows -> index.readStopTimes(rows) }
            index.finishStopTimes()
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

    private class StaticGtfsIndex(
        private val limits: GtfsCompactIndexLimits,
        /** [EmbeddedGtfsPack.itineraryCopies]: one trip per physical run of a service day is kept ([copiesOn]). */
        private val itineraryCopies: Boolean = false
    ) {
        private val stops = CompactStops(limits.maxStops)
        private val agencies = HashMap<String, String>()
        private val routes = HashMap<String, CompactRoute>()
        private val trips = CompactTrips(limits.maxTrips)
        private val stopTimes = CompactStopTimes(limits.maxStopTimeRows)
        private val calendars = HashMap<String, CompactCalendar>()
        private val calendarDates = HashMap<String, CompactCalendarExceptions>()
        /**
         * One instance per service_id while the tables are read (calendar, calendar_dates and trips repeat it; the
         * national pack has a service per trip), dropped by [finishReading].
         */
        private var serviceIds: HashMap<String, String>? = HashMap()
        /** [copiesOn] per service date (yyyyMMdd), a few days at most; an empty array is a day without copies. */
        private val copiesByDate = object : LinkedHashMap<Int, BooleanArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BooleanArray>?): Boolean = size > MAX_COPY_DAYS
        }

        private fun serviceId(value: String): String {
            val pool = serviceIds ?: return value
            return pool.putIfAbsent(value, value) ?: value
        }

        fun finishReading() {
            serviceIds = null
        }

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

        fun readTrips(rows: GtfsCsvRows, numberQuery: TrainNumberQuery? = null, numbers: GtfsPackNumbers.Builder? = null) {
            val routeIndex = rows.header.indexOf("route_id")
            val serviceIndex = rows.header.indexOf("service_id")
            val tripIndex = rows.header.indexOf("trip_id")
            val shortNameIndex = rows.header.indexOf("trip_short_name")
            val headsignIndex = rows.header.indexOf("trip_headsign")
            val blockIndex = rows.header.indexOf("block_id")
            val wheelchairIndex = rows.header.indexOf("wheelchair_accessible")
            // Trenord's pack has no such column and Trenitalia's leaves it empty: unknown, never guessed.
            val bikesIndex = rows.header.indexOf("bikes_allowed")
            // Route ids repeat on every trip: keep the instance already held by the routes table.
            val routeIds = routes.keys.associateBy { it }
            // So do block ids (a block is several trips): one instance per block.
            val blockIds = HashMap<String, String>()
            rows.forEachRow(limits.maxTrips) { row ->
                val tripId = row.gtfsRawValue(tripIndex)
                val shortName = row.getOrNull(shortNameIndex)
                // SNCF publishes the train number as trip_headsign; a destination name there is not a number.
                val numericHeadsign = row.getOrNull(headsignIndex)?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                val searched = searchNumber(shortName, numericHeadsign, tripId)
                numbers?.add(searched)
                if (numberQuery != null && !numberQuery.matches(searched)) return@forEachRow
                val routeId = row.gtfsRawValue(routeIndex)
                trips.configure(
                    tripId = tripId,
                    routeId = routeIds[routeId] ?: routeId,
                    serviceId = serviceId(row.gtfsRawValue(serviceIndex)),
                    shortName = shortName,
                    numericHeadsign = numericHeadsign,
                    blockId = row.getOrNull(blockIndex)?.trim()?.takeIf(String::isNotEmpty)?.let { blockIds.getOrPut(it) { it } },
                    wheelchair = wheelchairValue(row.getOrNull(wheelchairIndex)),
                    bikes = bikesValue(row.getOrNull(bikesIndex))
                )
            }
        }

        fun readStopTimes(rows: GtfsCsvRows) {
            val tripIndex = rows.header.indexOf("trip_id")
            val arrivalIndex = rows.header.indexOf("arrival_time")
            val departureIndex = rows.header.indexOf("departure_time")
            val stopIndex = rows.header.indexOf("stop_id")
            val sequenceIndex = rows.header.indexOf("stop_sequence")
            val pickupIndex = rows.header.indexOf("pickup_type")
            val dropOffIndex = rows.header.indexOf("drop_off_type")
            val rowsRead = rows.forEachRow(limits.maxStopTimeRows) { row ->
                val trip = trips.indexIfDeclared(row.gtfsRawValue(tripIndex)) ?: return@forEachRow
                val entry = stopTimes.append(
                    trip = trip,
                    stop = stops.indexFor(row.gtfsRawValue(stopIndex)),
                    sequence = row.getOrNull(sequenceIndex)?.toIntOrNull() ?: Int.MAX_VALUE,
                    arrival = gtfsTimeToMinutes(row.getOrNull(arrivalIndex)),
                    departure = gtfsTimeToMinutes(row.getOrNull(departureIndex)),
                    // GTFS 1 = "no pickup" / "no drop off"; 0, 2, 3 and empty still allow it.
                    noPickup = row.getOrNull(pickupIndex)?.trim() == "1",
                    noDropOff = row.getOrNull(dropOffIndex)?.trim() == "1",
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
                calendars[serviceId(row.gtfsRawValue(serviceIndex))] = CompactCalendar(
                    startDate = gtfsDateToInt(row.gtfsRawValue(startIndex)) ?: NO_DATE,
                    endDate = gtfsDateToInt(row.gtfsRawValue(endIndex)) ?: NO_DATE,
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
                    calendarDates.getOrPut(serviceId(row.gtfsRawValue(serviceIndex))) { CompactCalendarExceptions() }
                        .add(date, row.gtfsRawValue(exceptionIndex))
                }
            }
        }

        fun hasTrips(): Boolean = trips.size() > 0

        /** The number a train-number search compares: trip_short_name, else a numeric trip_headsign, else the trip id. */
        private fun searchNumber(shortName: String?, numericHeadsign: String?, tripId: String): String =
            shortName?.takeIf(String::isNotBlank) ?: numericHeadsign ?: tripId

        fun servicesByNumber(sourceId: String, query: TrainNumberQuery): List<TrainService> {
            val selected = ArrayList<TrainService>()
            for (serviceDate in listOf(query.date.minusDays(2), query.date.minusDays(1), query.date)) {
                val copies = copiesOn(serviceDate)
                trips.forEachDeclared { tripIndex, trip ->
                    // A pack read whole ([itineraryCopies]) holds every number: only the trips of the asked one, and no copy.
                    if (itineraryCopies && !query.matches(searchNumber(trip.shortName, trip.numericHeadsign, trip.id))) return@forEachDeclared
                    if (copies != null && copies[tripIndex]) return@forEachDeclared
                    if (!serviceRunsOn(trip.serviceId, serviceDate)) return@forEachDeclared
                    val type = routes[trip.routeId]?.type?.toIntOrNull() ?: return@forEachDeclared
                    if (type != 1 && type != 2 && type !in 100..199 && type !in 400..499) return@forEachDeclared
                    var first = -1
                    var last = -1
                    var entry = trips.head(tripIndex)
                    while (entry >= 0) {
                        if (stopTimes.preferredDeparture(entry) >= 0) {
                            if (first < 0 || stopTimes.comesBefore(entry, first)) first = entry
                            if (last < 0 || stopTimes.comesBefore(last, entry)) last = entry
                        }
                        entry = stopTimes.next(entry)
                    }
                    if (first < 0 || last < 0 || first == last) return@forEachDeclared
                    val departure = serviceDate.atStartOfDay().plusMinutes(stopTimes.preferredDeparture(first).toLong())
                    val arrival = serviceDate.atStartOfDay().plusMinutes(stopTimes.preferredDeparture(last).toLong())
                    if (departure.toLocalDate() > query.date || arrival.toLocalDate() < query.date) return@forEachDeclared
                    if (selected.size >= limits.maxServicesPerPack) return@forEachDeclared
                    selected += buildService(CompactSegment(first, last, tripIndex), trip, sourceId, serviceDate, departure)
                }
            }
            return selected
        }

        fun services(
            origin: String,
            destination: String,
            sourceId: String,
            query: JourneyQuery,
            scheduledCoaches: Boolean = false
        ): List<TrainService> {
            val wantedOrigin = normalize(origin)
            val wantedDestination = normalize(destination)
            val selected = ArrayList<TrainService>()
            // GTFS usa date di servizio: venerdì 25:10 è sabato 01:10. Due
            // giorni precedenti coprono anche continuazioni oltre 48:00.
            // The next service day is searched too, up to 24 hours after the request, so a search
            // made after the day's last train shows the next morning's first departures (as iOS
            // does) and a late-evening one the first trains after midnight (Opus audit N-M12).
            val nextServiceDate = query.serviceDate.plusDays(1)
            val nextDayHorizon = query.requestedAt.plusHours(NEXT_DAY_LOOK_AHEAD_HOURS)
            val serviceDates = listOf(
                query.serviceDate,
                query.serviceDate.minusDays(1),
                query.serviceDate.minusDays(2),
                nextServiceDate
            )
            serviceDates.forEach { serviceDate ->
                val copies = copiesOn(serviceDate)
                trips.forEachDeclared { tripIndex, trip ->
                    if (!serviceRunsOn(trip.serviceId, serviceDate)) return@forEachDeclared
                    if (copies != null && copies[tripIndex]) return@forEachDeclared
                    val segment = findSegment(tripIndex, wantedOrigin, wantedDestination) ?: return@forEachDeclared
                    val departureMinutes = stopTimes.preferredDeparture(segment.originEntry)
                    if (departureMinutes < 0) return@forEachDeclared
                    val scheduledDateTime = serviceDate.atStartOfDay().plusMinutes(departureMinutes.toLong())
                    if (scheduledDateTime.isBefore(query.requestedAt)) return@forEachDeclared
                    if (serviceDate == nextServiceDate && !scheduledDateTime.isBefore(nextDayHorizon)) return@forEachDeclared
                    if (selected.size >= limits.maxServicesPerPack && isAfterCurrentWorst(scheduledDateTime, trip, selected)) {
                        return@forEachDeclared
                    }
                    offerBounded(
                        selected,
                        buildService(segment, trip, sourceId, serviceDate, scheduledDateTime, scheduledCoaches),
                        limits.maxServicesPerPack
                    )
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
            val number = publicNumber(trip, routes[trip.routeId])
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
                if (
                    stops.normalizedName(stopTimes.stop(entry)) == wantedOrigin &&
                    stopTimes.allowsPickup(entry) &&
                    (origin < 0 || stopTimes.comesBefore(entry, origin))
                ) {
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
                    stopTimes.allowsDropOff(entry) &&
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
            scheduledDateTime: java.time.LocalDateTime,
            scheduledCoaches: Boolean = false
        ): TrainService {
            val route = routes[trip.routeId]
            val departureMinutes = stopTimes.preferredDeparture(segment.originEntry)
            val segmentEntries = segmentEntries(segment)
            val agencyName = agencyName(route)
            val number = publicNumber(trip, route)
            val scheduledBus = scheduledCoaches && journeyLegMode(route?.type, scheduledCoaches) == LegMode.BUS
            return TrainService(
                id = "$sourceId:${trip.id}:$serviceDate",
                number = number,
                operator = legacyOperator(sourceId, agencyName, route),
                operatorDisplayName = canonicalAgencyLabel(agencyName, sourceId),
                trainCategory = if (scheduledBus) TrainCategory.OTHER else category(route?.type, route, agencyName),
                scheduledBus = scheduledBus,
                bikesAllowed = bikesOnBoard(packBikesValue(sourceId, trip.bikes)),
                origin = stops.name(stopTimes.stop(segment.originEntry)),
                destination = stops.name(stopTimes.stop(segment.destinationEntry)),
                scheduledDeparture = hhmm(departureMinutes),
                expectedDeparture = hhmm(departureMinutes),
                platform = null,
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                operationalStatusVerified = false,
                scheduledDepartureDateTime = scheduledDateTime,
                gtfsIdentity = it.binario.app.data.GtfsServiceIdentity(
                    sourceId, trip.id, serviceDate,
                    segmentEntries.map { entry ->
                        it.binario.app.data.GtfsStopIdentity(stops.id(stopTimes.stop(entry)), stopTimes.sequence(entry))
                    }
                ),
                stops = segmentEntries.mapIndexed { index, entry ->
                    // Where the traveller gets off (the segment's last stop) the time is the train's arrival, not its
                    // departure after the dwell; the boarding and intermediate stops keep the departure.
                    val alighting = index > 0 && index == segmentEntries.lastIndex
                    val scheduled = hhmm(if (alighting) stopTimes.preferredArrival(entry) else stopTimes.preferredDeparture(entry))
                    TrainStop(stops.name(stopTimes.stop(entry)), scheduled, scheduled, null, 0, false, operationalStatusVerified = false)
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

        /**
         * The number travellers see: trip_short_name, else a numeric trip_headsign (SNCF), else the
         * line's route_short_name. The raw trip_id is a last resort only: SNCF ids run past 100
         * characters and Trentino ids are 25 digits nobody recognises on a phone or a watch.
         */
        private fun publicNumber(trip: CompactTrip, route: CompactRoute?): String =
            trip.shortName?.takeIf(String::isNotBlank)
                ?: trip.numericHeadsign
                ?: route?.shortName?.trim()?.takeIf(String::isNotEmpty)
                ?: trip.id

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
                normalized.contains("ntv") || normalized.contains("italotreno") || normalized.startsWith("italo") -> "Italo"
                else -> raw
            }
        }

        private fun legacyOperator(sourceId: String, agencyName: String?, route: CompactRoute?): RailOperator {
            val identity = normalize(listOfNotNull(agencyName, route?.shortName, route?.longName).joinToString(" "))
            trenitaliaLineCode(route)?.let { code ->
                if (identity.contains("trenitalia")) return when (code) {
                    "FR", "FA", "FB" -> RailOperator.TRENITALIA_AV
                    "IC", "ICN", "EC", "EN" -> RailOperator.TRENITALIA_LONG_DISTANCE
                    else -> RailOperator.TRENITALIA_REGIONALE
                }
            }
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
            if (identity.contains("trenitalia")) when (trenitaliaLineCode(route)) {
                "FR", "FA", "FB" -> return TrainCategory.FRECCE
                "IC", "ICN" -> return TrainCategory.INTERCITY
                "RV" -> return TrainCategory.REGIONALE_VELOCE
                "REG", "SFM" -> return TrainCategory.REGIONALE
                "MET" -> return TrainCategory.METROPOLITANO
                "EC", "EN", "EXP" -> return TrainCategory.OTHER
            }
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

        /** route_short_name without Trenitalia's "0083_" company prefix ("0083_FR" is "FR"), display only. */
        private fun lineLabel(route: CompactRoute?): String? {
            val shortName = route?.shortName?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return TRENITALIA_LINE.matchEntire(shortName)?.groupValues?.get(1) ?: shortName
        }

        /** The category code of a Trenitalia route ("0083_FR" → "FR"), or null for any other route. */
        private fun trenitaliaLineCode(route: CompactRoute?): String? =
            route?.shortName?.trim()?.let(TRENITALIA_LINE::matchEntire)?.groupValues?.get(1)?.uppercase(Locale.ROOT)

        private fun serviceRunsOn(serviceId: String, date: LocalDate): Boolean {
            when (calendarDates[serviceId]?.exception(date.toGtfsInt())) {
                "1" -> return true
                "2" -> return false
            }
            val calendar = calendars[serviceId] ?: return false
            val encodedDate = date.toGtfsInt()
            val startDate = calendar.startDate.takeIf { it != NO_DATE } ?: return false
            val endDate = calendar.endDate.takeIf { it != NO_DATE } ?: return false
            if (encodedDate !in startDate..endDate) return false
            return calendar.activeDays and (1 shl date.dayOfWeek.ordinal) != 0
        }

        /**
         * Connections of the trips running on [serviceDate]: one per pair of consecutive timed stops, in trip
         * order. Stops without any usable time are skipped (they can be neither boarded nor left), and so is a stop
         * whose time goes back (arriving before the previous stop's departure, or after a departure earlier than the
         * previous arrival). Only rail, urban rail and rail-replacement bus route types take part.
         */
        fun dayTimetable(
            sourceId: SourceId,
            serviceDate: LocalDate,
            fromMinute: Int,
            untilMinute: Int,
            scheduledCoaches: Boolean,
            ensureActive: () -> Unit
        ): GtfsDayTimetable {
            val builder = GtfsDayTimetable.Builder(sourceId, serviceDate, stops.size(), stops::id, stops::name)
            var entries = IntArray(64)
            var visited = 0
            val copies = copiesOn(serviceDate)
            trips.forEachDeclared { tripIndex, trip ->
                visited += 1
                if (visited and 255 == 0) ensureActive()
                if (!serviceRunsOn(trip.serviceId, serviceDate)) return@forEachDeclared
                if (copies != null && copies[tripIndex]) return@forEachDeclared
                val route = routes[trip.routeId]
                val mode = journeyLegMode(route?.type, scheduledCoaches) ?: return@forEachDeclared
                var count = 0
                var entry = trips.head(tripIndex)
                while (entry >= 0) {
                    if (count == entries.size) entries = entries.copyOf(count * 2)
                    entries[count++] = entry
                    entry = stopTimes.next(entry)
                }
                // The per-trip chain is in reverse file order; files are usually sorted by stop_sequence, so a
                // reversal makes the insertion sort below linear in the common case.
                for (left in 0 until count / 2) {
                    val right = count - 1 - left
                    val swap = entries[left]
                    entries[left] = entries[right]
                    entries[right] = swap
                }
                for (index in 1 until count) {
                    val value = entries[index]
                    var position = index - 1
                    while (position >= 0 && stopTimes.comesBefore(value, entries[position])) {
                        entries[position + 1] = entries[position]
                        position -= 1
                    }
                    entries[position + 1] = value
                }
                val agency = agencyName(route)
                // A day read from a later minute leaves out the trip's first connections: keep where it really starts.
                var origin = -1
                for (index in 0 until count) if (stopTimes.preferredDeparture(entries[index]) >= 0) { origin = entries[index]; break }
                val originDeparture = if (origin >= 0) stopTimes.preferredDeparture(origin) else -1
                val cutOrigin = origin >= 0 && originDeparture < fromMinute
                builder.beginTrip(
                    tripId = trip.id,
                    publicNumber = publicNumber(trip, route),
                    lineLabel = lineLabel(route),
                    operatorName = canonicalAgencyLabel(agency, sourceId.value),
                    category = if (mode == LegMode.BUS) TrainCategory.OTHER else category(route?.type, route, agency),
                    operator = legacyOperator(sourceId.value, agency, route),
                    mode = mode,
                    blockId = trip.blockId,
                    wheelchairAccessible = trip.wheelchair,
                    bikesAllowed = packBikesValue(sourceId.value, trip.bikes),
                    originIndexStop = if (cutOrigin) stopTimes.stop(origin) else -1,
                    originDepartureMinute = if (cutOrigin) originDeparture else -1
                )
                var previous = -1
                var lastArrival = Int.MIN_VALUE
                for (index in 0 until count) {
                    val current = entries[index]
                    if (stopTimes.preferredDeparture(current) < 0) continue
                    if (previous >= 0) {
                        val departure = stopTimes.preferredDeparture(previous)
                        val arrival = stopTimes.preferredArrival(current)
                        // A stop whose time goes back is skipped like an untimed one, so the kept connections of a
                        // trip never go back in time and a scan meets them in stop order.
                        if (departure < lastArrival || arrival < departure) continue
                        if (departure in fromMinute..untilMinute) {
                            builder.connection(
                                departureStop = stopTimes.stop(previous),
                                arrivalStop = stopTimes.stop(current),
                                departureMinute = departure,
                                arrivalMinute = arrival,
                                pickupAllowed = stopTimes.allowsPickup(previous),
                                dropOffAllowed = stopTimes.allowsDropOff(current)
                            )
                        }
                        lastArrival = arrival
                    }
                    previous = current
                }
                builder.endTrip()
            }
            return builder.build()
        }

        /**
         * The trips of [serviceDate] left out as copies, by trip index; null when the pack has none
         * ([EmbeddedGtfsPack.itineraryCopies] off, or a day without copies). A pack that publishes the operator's
         * combined itineraries as trips (train A, a change, train B) repeats one physical run under several trip ids,
         * some under the first train's number: Italo's 14:35 Milano Centrale → Venezia (8987) is also "9920" and "9939".
         * A trip is a copy when another trip of the day runs the same stops in the same order with the same arrival
         * times over the whole of it ([RunOfDay.offsetIn]) and that trip is the better one to keep ([keptBefore]): the
         * one that starts where the first train of its number that day starts (the train under its own number), then
         * the longer one. Two trains that both start their own number (coupled portions) are never merged.
         */
        private fun copiesOn(serviceDate: LocalDate): BooleanArray? {
            if (!itineraryCopies) return null
            val key = serviceDate.toGtfsInt()
            synchronized(copiesByDate) { copiesByDate[key] }?.let { return it.takeIf { found -> found.isNotEmpty() } }
            val runs = railRuns { trip -> serviceRunsOn(trip.serviceId, serviceDate) }
            // Where the first train of each number starts that day: stop and minute.
            val firstStart = HashMap<String, Long>()
            for (run in runs) {
                val start = run.start()
                val known = firstStart[run.number]
                if (known == null || start < known) firstStart[run.number] = start
            }
            runs.forEach { it.own = firstStart[it.number] == it.start() }
            val copies = BooleanArray(trips.size())
            var found = false
            forEachSharingPair(runs) { first, second ->
                for ((copy, kept) in listOf(first to second, second to first)) {
                    if (!copies[copy.tripIndex] && keptBefore(kept, copy) && copy.offsetIn(kept) >= 0) {
                        copies[copy.tripIndex] = true
                        found = true
                    }
                }
            }
            val result = if (found) copies else BooleanArray(0)
            synchronized(copiesByDate) { copiesByDate[key] = result }
            return result.takeIf { it.isNotEmpty() }
        }

        /** [kept] stays and [copy] goes when both are the same run: see [copiesOn]. */
        private fun keptBefore(kept: RunOfDay, copy: RunOfDay): Boolean {
            if (kept.own != copy.own) return kept.own
            // Two trains under their own numbers on the same track at the same minutes are coupled portions.
            if (kept.own && kept.number != copy.number) return false
            if (kept.stops.size != copy.stops.size) return kept.stops.size > copy.stops.size
            return kept.tripIndex < copy.tripIndex
        }

        /**
         * The copies of a pack with [itineraryCopies] often lose a dwell (arrival and departure at the same minute) that
         * another copy of the same run still has: Italo's 9939 leaves Roma Termini at 17:30, as the itineraries that
         * board it there say, while its own trip says 17:19, its arrival. Every trip takes the latest departure the
         * trips of the same run publish for a stop, once, when the pack is read.
         */
        fun finishStopTimes() {
            if (!itineraryCopies) return
            val runs = railRuns { true }
            forEachSharingPair(runs) { first, second ->
                val (inner, outer) = if (first.stops.size <= second.stops.size) first to second else second to first
                val offset = inner.offsetIn(outer)
                if (offset < 0) return@forEachSharingPair
                // The last stop of the shorter trip has no departure of its own.
                for (position in 0 until inner.stops.size - 1) {
                    val latest = maxOf(inner.departures[position], outer.departures[offset + position])
                    if (latest > inner.arrivals[position + 1]) continue
                    for ((run, at) in listOf(inner to position, outer to offset + position)) {
                        if (run.departures[at] == latest) continue
                        run.departures[at] = latest
                        stopTimes.setDeparture(run.entries[at], latest)
                    }
                }
            }
        }

        /** The train trips [wanted] with two timed stops at least, in stop order. */
        private fun railRuns(wanted: (CompactTrip) -> Boolean): List<RunOfDay> {
            val runs = ArrayList<RunOfDay>()
            trips.forEachDeclared { tripIndex, trip ->
                if (!wanted(trip)) return@forEachDeclared
                val route = routes[trip.routeId]
                if (journeyLegMode(route?.type) != LegMode.TRAIN) return@forEachDeclared
                val entries = ArrayList<Int>()
                var entry = trips.head(tripIndex)
                while (entry >= 0) {
                    if (stopTimes.preferredDeparture(entry) >= 0) entries += entry
                    entry = stopTimes.next(entry)
                }
                if (entries.size < 2) return@forEachDeclared
                entries.sortWith(compareBy<Int> { stopTimes.sequence(it) }.thenBy { stopTimes.ordinal(it) })
                runs += RunOfDay(
                    tripIndex = tripIndex,
                    number = publicNumber(trip, route),
                    entries = entries.toIntArray(),
                    stops = IntArray(entries.size) { stopTimes.stop(entries[it]) },
                    arrivals = IntArray(entries.size) { stopTimes.preferredArrival(entries[it]) },
                    departures = IntArray(entries.size) { stopTimes.preferredDeparture(entries[it]) }
                )
            }
            return runs
        }

        /** Every pair of [runs] with a hop in common: the same two stops, reached at the same minute. */
        private fun forEachSharingPair(runs: List<RunOfDay>, visit: (RunOfDay, RunOfDay) -> Unit) {
            val byHop = HashMap<Long, MutableList<Int>>()
            runs.forEachIndexed { index, run ->
                for (position in 1 until run.stops.size) {
                    val hop = (run.stops[position - 1].toLong() shl 40) or (run.stops[position].toLong() shl 16) or
                        (run.arrivals[position].toLong() and 0xFFFF)
                    val sharing = byHop.getOrPut(hop) { ArrayList(2) }
                    if (sharing.lastOrNull() != index) sharing += index
                }
            }
            val seen = HashSet<Long>()
            for (sharing in byHop.values) {
                if (sharing.size < 2) continue
                for (first in sharing.indices) for (second in first + 1 until sharing.size) {
                    val low = minOf(sharing[first], sharing[second])
                    val high = maxOf(sharing[first], sharing[second])
                    if (seen.add((low.toLong() shl 32) or high.toLong())) visit(runs[low], runs[high])
                }
            }
        }

        /** One train trip in stop order ([entries]: its stop_times), for [copiesOn]. */
        private class RunOfDay(
            val tripIndex: Int,
            val number: String,
            val entries: IntArray,
            val stops: IntArray,
            val arrivals: IntArray,
            val departures: IntArray
        ) {
            /** It starts where the first train of its number starts that day: the train under its own number. */
            var own = false

            fun start(): Long = (departures[0].toLong() shl 32) or stops[0].toLong()

            /**
             * Where this trip lies inside [other] (the position of its first stop there), or -1: the same stops in the
             * same order, reached at the same minutes after the first one, and left within [MAX_DWELL_SKEW_MINUTES] (a
             * copy may have lost a dwell).
             */
            fun offsetIn(other: RunOfDay): Int {
                val size = stops.size
                offsets@ for (offset in 0..other.stops.size - size) {
                    for (position in 0 until size) {
                        if (stops[position] != other.stops[offset + position]) continue@offsets
                        if (position > 0 && arrivals[position] != other.arrivals[offset + position]) continue@offsets
                        if (position < size - 1 &&
                            Math.abs(departures[position] - other.departures[offset + position]) > MAX_DWELL_SKEW_MINUTES
                        ) continue@offsets
                    }
                    return offset
                }
                return -1
            }
        }

        fun stats(): GtfsCompactIndexStats = GtfsCompactIndexStats(
            stopTimeRowsRead = stopTimes.rowsRead(),
            indexedStopTimeRows = stopTimes.size(),
            stopTimeColumnBytes = stopTimes.compactColumnBytes(),
            stops = stops.size(),
            trips = trips.size()
        )

        private data class CompactRoute(val agencyId: String, val shortName: String, val longName: String, val type: String?)
        /** Dates as yyyyMMdd ints, [NO_DATE] when unreadable: no boxed Integer per calendar row. */
        private class CompactCalendar(val startDate: Int, val endDate: Int, val activeDays: Int)
        private data class CompactSegment(val originEntry: Int, val destinationEntry: Int, val tripIndex: Int)
        private data class TrainServiceSortKey(val scheduledDateTime: java.time.LocalDateTime, val number: String)

        private class CompactStops(private val maximum: Int) {
            private val indexById = HashMap<String, Int>()
            private val ids = ArrayList<String>()
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
                ids += id
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
            fun id(index: Int): String = ids[index]
            fun normalizedName(index: Int): String = normalizedNames.getOrNull(index).orEmpty()
            fun size(): Int = names.size
        }

        private class CompactTrips(private val maximum: Int) {
            private val indexById = HashMap<String, Int>()
            private val items = ArrayList<CompactTrip>()
            private var heads = IntArray(1) { -1 }

            fun indexFor(id: String): Int = indexById[id] ?: add(id)

            fun indexIfDeclared(id: String): Int? = indexById[id]?.takeIf { items[it].declared }

            fun configure(
                tripId: String,
                routeId: String,
                serviceId: String,
                shortName: String?,
                numericHeadsign: String?,
                blockId: String? = null,
                wheelchair: Byte = WHEELCHAIR_UNKNOWN,
                bikes: Byte = BIKES_UNKNOWN
            ) {
                val trip = items[indexFor(tripId)]
                trip.routeId = routeId
                trip.serviceId = serviceId
                trip.shortName = shortName
                trip.numericHeadsign = numericHeadsign
                trip.blockId = blockId
                trip.wheelchair = wheelchair
                trip.bikes = bikes
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
            var numericHeadsign: String? = null
            /** block_id, for the trips the same vehicle runs one after the other ("Resta a bordo"). */
            var blockId: String? = null
            var wheelchair: Byte = WHEELCHAIR_UNKNOWN
            /** bikes_allowed as published ([BIKES_UNKNOWN] when empty or without the column). */
            var bikes: Byte = BIKES_UNKNOWN
            var declared: Boolean = false
        }

        private class CompactStopTimes(private val maximum: Int) {
            private var trips = IntArray(1024)
            private var stops = IntArray(1024)
            private var sequences = IntArray(1024)
            private var arrivals = IntArray(1024)
            private var departures = IntArray(1024)
            private var next = IntArray(1024)
            private var boarding = ByteArray(1024)
            private var count = 0
            private var sourceRowsRead = 0

            fun append(
                trip: Int,
                stop: Int,
                sequence: Int,
                arrival: Int,
                departure: Int,
                previousForTrip: Int,
                noPickup: Boolean = false,
                noDropOff: Boolean = false
            ): Int {
                if (count >= maximum) throw GtfsCompactIndexLimitException("stop_times.txt supera il limite di righe")
                ensureCapacity(count + 1)
                trips[count] = trip
                stops[count] = stop
                sequences[count] = sequence
                arrivals[count] = arrival
                departures[count] = departure
                next[count] = previousForTrip
                boarding[count] = ((if (noPickup) NO_PICKUP else 0) or (if (noDropOff) NO_DROP_OFF else 0)).toByte()
                return count++
            }

            fun allowsPickup(index: Int): Boolean = boarding[index].toInt() and NO_PICKUP == 0
            fun allowsDropOff(index: Int): Boolean = boarding[index].toInt() and NO_DROP_OFF == 0

            fun stop(index: Int): Int = stops[index]
            fun sequence(index: Int): Int = sequences[index]
            fun next(index: Int): Int = next[index]
            fun ordinal(index: Int): Int = index
            fun comesBefore(first: Int, second: Int): Boolean =
                sequence(first) < sequence(second) || (sequence(first) == sequence(second) && ordinal(first) < ordinal(second))

            /** A blank or unreadable departure falls back to the arrival; negative when neither time is usable. */
            fun preferredDeparture(index: Int): Int = departures[index].takeIf { it >= 0 } ?: arrivals[index]
            /** The mirror of [preferredDeparture] for the arrival side of a stop. */
            fun preferredArrival(index: Int): Int = arrivals[index].takeIf { it >= 0 } ?: departures[index]
            /** A departure another copy of the same run publishes ([finishStopTimes]). */
            fun setDeparture(index: Int, minute: Int) { departures[index] = minute }
            fun recordRowsRead(value: Int) { sourceRowsRead = value }
            fun rowsRead(): Int = sourceRowsRead
            fun size(): Int = count
            fun compactColumnBytes(): Long = count.toLong() * (Int.SIZE_BYTES * 6L + 1L)

            private fun ensureCapacity(required: Int) {
                if (required <= trips.size) return
                val capacity = (trips.size * 2).coerceAtLeast(required).coerceAtMost(maximum)
                trips = trips.copyOf(capacity)
                stops = stops.copyOf(capacity)
                sequences = sequences.copyOf(capacity)
                arrivals = arrivals.copyOf(capacity)
                departures = departures.copyOf(capacity)
                next = next.copyOf(capacity)
                boarding = boarding.copyOf(capacity)
            }

            private companion object {
                const val NO_PICKUP = 1
                const val NO_DROP_OFF = 2
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
                // One shared instance per exception type: the national pack has a quarter of a million rows.
                types[count] = when (type) {
                    "1" -> "1"
                    "2" -> "2"
                    else -> type
                }
                count += 1
            }

            fun exception(date: Int): String? {
                for (index in count - 1 downTo 0) if (dates[index] == date) return types[index]
                return null
            }
        }
    }

    private companion object {
        private const val NO_DATE = Int.MIN_VALUE
        private const val MISSING_TIME = Int.MIN_VALUE
        private const val INVALID_TIME = Int.MIN_VALUE + 1
        private const val NEXT_DAY_LOOK_AHEAD_HOURS = 24L
        /** Service days whose copies are remembered per index: the days of one search and of the next. */
        private const val MAX_COPY_DAYS = 8
        /** Two copies of one run leave a stop within this many minutes of each other (a lost dwell at a large station). */
        private const val MAX_DWELL_SKEW_MINUTES = 20
        /**
         * Packs that also publish partner operators' trains under their own numbers and operator names, as the shipped
         * data shows: the Swiss, French and Austrian national feeds (TILO trains of Trenord, SNCF/SBB border runs, ÖBB
         * trains into South Tyrol) and South Tyrol's multi-operator feed (Trenitalia, ÖBB, Italo and Trenord runs).
         */
        private val CROSS_PUBLISHING_PACKS = setOf("ch-otd-gtfs", "sncf-gtfs", "oebb-gtfs", "alto-adige-sta-gtfs")
        private val RAIL_ROUTE_TYPES = setOf("2", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109")
        /** Trenitalia's route_short_name: its company code 0083, then the category ("0083_REG", "0083_BUS"). */
        private val TRENITALIA_LINE = Regex("0083_([A-Za-z]{1,4})")
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

        /** A stop without a readable time shows the same placeholder as the live boards, never a fabricated 00:00. */
        fun hhmm(minutes: Int): String {
            if (minutes < 0) return "—"
            return "%02d:%02d".format(Locale.ROOT, (minutes / 60) % 24, minutes % 60)
        }

        fun gtfsDateToInt(value: String): Int? =
            value.takeIf { it.matches(Regex("\\d{8}")) }?.toIntOrNull()

        fun LocalDate.toGtfsInt(): Int = year * 10_000 + monthValue * 100 + dayOfMonth

        /**
         * Journey legs come from rail (route_type 2, 100-199), metro (1, 400-499), tram (0, 900-999),
         * funicular and lift (5, 6, 7, 1300-1499) and bus routes (3, 700-799); a missing type is a train, as in the
         * route search. Metro, tram and funicular lines are only in the city packs (the railway packs keep rail and
         * the operators' coaches). A bus route is the operator's own scheduled coach ([LegMode.BUS]) in a pack
         * whose buses are [EmbeddedGtfsPack.busRoutesAreScheduledCoaches] (Trenitalia, ItaloBus) and a rail
         * replacement bus in the others. Anything else (ferries, …) is not a leg of a rail journey.
         */
        fun journeyLegMode(rawType: String?, scheduledCoaches: Boolean = false): LegMode? {
            val text = rawType?.trim().orEmpty()
            if (text.isEmpty()) return LegMode.TRAIN
            val type = text.toIntOrNull() ?: return null
            return when (type) {
                2, in 100..199 -> LegMode.TRAIN
                1, in 400..499 -> LegMode.METRO
                0, in 900..999 -> LegMode.TRAM
                5, 6, 7, in 1300..1499 -> LegMode.FUNICULAR
                3, in 700..799 -> if (scheduledCoaches) LegMode.BUS else LegMode.REPLACEMENT_BUS
                else -> null
            }
        }
    }

}
