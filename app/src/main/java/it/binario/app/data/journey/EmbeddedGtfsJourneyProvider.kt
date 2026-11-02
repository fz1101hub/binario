package it.binario.app.data.journey

import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.DataWarning
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.GtfsCompactIndexLimitException
import it.binario.app.data.gtfs.GtfsDayTimetable
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.StopNameCase
import it.binario.app.data.gtfs.GtfsStopCodeScheme
import it.binario.app.data.gtfs.GtfsStopDirectory
import it.binario.app.data.planned.RfiProgrammedStationIds
import it.binario.app.data.planned.SupplementalRfiProgrammedStationIds
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.KnownProviderIds
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Tuning of the offline planner. Every value is a parameter so that tests and later screens can change it. */
data class GtfsJourneyPlannerSettings(
    /** Minimum change time at an ordinary station. */
    val defaultTransferMinutes: Int = 5,
    /** Minimum change time at a large hub. */
    val largeHubTransferMinutes: Int = 10,
    /** Catalogue railway codes treated as large hubs: by default the hubs with a verified RFI timetable. */
    val largeHubRailwayCodes: Set<String> = DEFAULT_LARGE_HUB_RAILWAY_CODES,
    /** Departures are searched from the requested time up to this many minutes later. */
    val searchWindowMinutes: Int = 8 * 60,
    /**
     * Connections leaving up to this long after the window are scanned too, so that a journey leaving inside the
     * window can still arrive (Merano → Roma takes seven hours): only its first departure must be in the window.
     */
    val journeyHorizonMinutes: Int = 10 * 60,
    val maxPacksPerSearch: Int = 3,
    val maxIterations: Int = 32,
    /** Bound on the connections of one search window (they are copied into the scan arrays). */
    val maxSearchConnections: Int = 400_000,
    /**
     * Bound on the cached per-pack, per-service-day connection arrays (the Swiss pack's day is about 5 MB, the
     * Trenitalia one 2.5 MB): room for the requested and the next day of the three packs of one search.
     */
    val maxCachedDays: Int = 16,
    val maxCachedBytes: Long = 24L * 1024L * 1024L,
    /**
     * Walking transfers: two transfer points at most this far apart (straight line) are joined by a walk, and so are
     * the requested stations and the stops near them (Milano Centrale and the Centrale M2/M3 stops).
     */
    val maxWalkMeters: Int = 400,
    /** Walking speed of those transfers. */
    val walkingSpeedKmh: Double = 4.5,
    /** Added to every walk (finding the way, stairs, ticket barriers). */
    val walkMarginMinutes: Int = 2,
    /** Minimum change time at a stop of a city network (metro, tram, funicular). */
    val urbanTransferMinutes: Int = 3,
    /** City packs loaded for one search besides the [maxPacksPerSearch] railway packs: the city of each end. */
    val maxUrbanPacksPerSearch: Int = 2
) {
    init {
        require(defaultTransferMinutes in 0..60 && largeHubTransferMinutes in 0..60) { "Tempo di cambio non valido" }
        require(searchWindowMinutes in 30..24 * 60) { "Finestra di ricerca non valida" }
        require(journeyHorizonMinutes in 0..24 * 60) { "Orizzonte dei viaggi non valido" }
        require(maxPacksPerSearch in 1..8) { "Numero di pack non valido" }
        require(maxIterations > 0 && maxSearchConnections > 0 && maxCachedDays > 0 && maxCachedBytes > 0)
        require(maxWalkMeters in 0..2_000 && walkingSpeedKmh > 0 && walkMarginMinutes in 0..30) { "Percorsi a piedi non validi" }
        require(urbanTransferMinutes in 0..60 && maxUrbanPacksPerSearch in 0..4) { "Reti urbane non valide" }
    }

    /** Minutes of a walk of [meters]: the distance at [walkingSpeedKmh], rounded up, plus [walkMarginMinutes]. */
    fun walkMinutes(meters: Double): Int =
        Math.ceil(meters / (walkingSpeedKmh * 1000.0 / 60.0)).toInt() + walkMarginMinutes

    companion object {
        val DEFAULT_LARGE_HUB_RAILWAY_CODES: Set<String> =
            RfiProgrammedStationIds.hubRailwayCodes + SupplementalRfiProgrammedStationIds.hubRailwayCodes
    }
}

/**
 * Journeys with changes computed on the phone from the embedded GTFS packs (Connection Scan Algorithm, see
 * [ConnectionScanPlanner]). Offline, open data, scheduled times only: it never claims a delay, a platform or a
 * cancellation, and it only knows the trains of the networks in the packs.
 *
 * A search resolves origin and destination to the packs' stops (catalogue station, its same-station aliases, or
 * the exact GTFS stop the user picked), uses the packs that contain both stations (or, when none does, those
 * containing either, so that a change between packs is possible at a shared catalogue station), loads the
 * per-day connection arrays of the previous, the requested and, when the window crosses midnight, the next
 * service day from the adapter's compact index, and scans the departures inside the window. Day arrays are cached
 * with a count and a size bound; everything runs on [dispatcher] and stops at the next check when cancelled.
 */
class EmbeddedGtfsJourneyProvider internal constructor(
    private val adapter: EmbeddedGtfsRouteAdapter,
    private val packs: List<EmbeddedGtfsPack>,
    private val loadStations: suspend () -> List<Station>,
    private val settings: GtfsJourneyPlannerSettings = GtfsJourneyPlannerSettings(),
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : JourneyProvider {
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    private val zone = RailTime.defaultZone
    /** Packs of `gtfs-transit/` and `gtfs-personal/`, whose stop names [StopNameCase] tidies. */
    private val transitSources: Set<SourceId> = packs.filterNot { it.assetPath.startsWith("gtfs/") }.mapTo(HashSet()) { it.sourceId }
    private val urbanSources: Set<SourceId> = packs.filter(EmbeddedGtfsPack::isUrban).mapTo(HashSet()) { it.sourceId }
    private val resolverMutex = Mutex()
    @Volatile private var resolver: GtfsStopStationResolver? = null
    private val packStopsMutex = Mutex()
    private val packStops = HashMap<SourceId, PackStops>()
    private val dayMutex = Mutex()
    private val dayCache = LinkedHashMap<String, GtfsDayTimetable>(16, 0.75f, true)

    override suspend fun searchJourneys(request: JourneySearchRequest): ProviderResult<List<JourneyOption>> =
        withContext(dispatcher) {
            try {
                search(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: GtfsCompactIndexLimitException) {
                ProviderResult.Failure(ProviderError(ProviderErrorKind.INVALID_DATA, "Un orario offline supera i limiti di lettura."))
            } catch (error: IOException) {
                ProviderResult.Failure(ProviderError(ProviderErrorKind.UNKNOWN, "Orari offline non leggibili.", error::class.java.simpleName))
            }
        }

    /** Diagnostics for tests: cached service days and their approximate size. */
    internal fun cachedDaysForTest(): Int = synchronized(dayCache) { dayCache.size }
    internal fun cachedBytesForTest(): Long = synchronized(dayCache) { dayCache.values.sumOf(GtfsDayTimetable::approximateBytes) }

    private suspend fun search(request: JourneySearchRequest): ProviderResult<List<JourneyOption>> {
        val fetchedAt = clock.instant()
        val meta = ProviderResponseMeta(
            providerId = descriptor.id,
            fetchedAt = fetchedAt,
            expiresAt = fetchedAt.plus(Duration.ofHours(12)),
            completeness = Completeness.STATIC_ONLY
        )
        val resolver = resolver()
        val origin = endpoint(request.origin, request.originEndpoint, resolver) ?: return notCovered()
        val destination = endpoint(request.destination, request.destinationEndpoint, resolver) ?: return notCovered()
        locateStop(origin, resolver)
        locateStop(destination, resolver)

        val departure = request.departureAt.withZoneSameInstant(zone).toLocalDateTime()
        val serviceDate = departure.toLocalDate()
        val startMinute = departure.hour * 60 + departure.minute
        val endMinute = startMinute + settings.searchWindowMinutes

        var incomplete = false
        val coverage = ArrayList<PackCoverage>()
        for (pack in packs) {
            currentCoroutineContext().ensureActive()
            // A city network is read only for a search with an end in its city (or at one of its stops).
            if (pack.isUrban && !urbanCandidate(pack, origin, destination)) continue
            val stops = try {
                packStops(pack, resolver)
            } catch (_: GtfsCompactIndexLimitException) {
                incomplete = true
                continue
            }
            val originNodes = stops.accessFor(origin, settings)
            val destinationNodes = stops.accessFor(destination, settings)
            if (originNodes.isNotEmpty() || destinationNodes.isNotEmpty()) {
                coverage += PackCoverage(pack, stops, originNodes, destinationNodes)
            }
        }
        if (coverage.none { it.coversOrigin } || coverage.none { it.coversDestination }) return notCovered()
        val current = coverage.filter { it.pack.lastServiceDate?.isBefore(serviceDate) != true }
        if (current.none { it.coversOrigin } || current.none { it.coversDestination }) {
            return ProviderResult.Partial(emptyList(), setOf(ProviderWarning.STALE_SOURCE), meta)
        }

        // The pack the route search prefers for planned itineraries comes first, so that when two packs publish
        // the same train its copy is the one kept.
        val ranked = current.filterNot { it.pack.isUrban }.sortedByDescending {
            RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, it.pack.sourceId, null)
        }
        // The city networks of the two ends (metro, tram), next to the railway packs of every selection.
        val urban = current.filter { it.pack.isUrban }
            .sortedByDescending { (if (it.coversOrigin) 1 else 0) + (if (it.coversDestination) 1 else 0) }
            .take(settings.maxUrbanPacksPerSearch)
        val primary = ranked.filter { it.coversOrigin && it.coversDestination }
        val railSelections = if (ranked.any { it.coversOrigin } && ranked.any { it.coversDestination }) {
            val union = unionForChanges(ranked, origin, destination, resolver)
            // A national pack (Trenitalia) serves both ends of most Italian searches: the free places of the first
            // selection go to the packs the change pairing chose, so a regional network that only serves one end
            // (the South Tyrol trains to Merano) still takes part when the national pack alone would find a journey.
            val primaryWithChanges = (primary.take(settings.maxPacksPerSearch) + union).distinctBy { it.pack.sourceId }
            listOf(primaryWithChanges, union)
        } else {
            // One end is reached only by a city network: the railway packs of the other end.
            listOf(ranked.filter { it.coversOrigin || it.coversDestination })
        }.filter(List<PackCoverage>::isNotEmpty).map { selection -> selection.take(settings.maxPacksPerSearch).sortedBy(ranked::indexOf) }
        // A journey to or from a city stop (Duomo, Peretola Aeroporto) takes the city lines from the start. Between two
        // railway stations the railway packs are searched as before and the city lines of the two ends only join when
        // they find nothing: a long-distance search through Milan does not read the Milan metro and trams.
        val endAtCityStop = urban.any { coverage ->
            coverage.pack.sourceId.value == origin.gtfsStop?.first || coverage.pack.sourceId.value == destination.gtfsStop?.first
        }
        val selections = buildList {
            if (!endAtCityStop) railSelections.forEach { add(it) }
            // Both ends in a city network (Milano Centrale to Duomo): the city lines with the railway packs serving both.
            if (urban.any { it.coversOrigin } && urban.any { it.coversDestination }) add(primary.take(settings.maxPacksPerSearch) + urban)
            if (urban.isNotEmpty()) railSelections.forEach { add(it + urban) }
        }.filter(List<PackCoverage>::isNotEmpty).distinctBy { selection -> selection.map { it.pack.sourceId } }

        var options = emptyList<JourneyOption>()
        for (selection in selections) {
            val outcome = searchPacks(selection, request, origin, destination, serviceDate, startMinute, endMinute, fetchedAt, resolver)
            incomplete = incomplete || outcome.incomplete
            options = outcome.options
            // The packs serving both stations come first; the union of the packs serving either is the fallback
            // that allows a change between packs.
            if (options.isNotEmpty()) break
        }
        return if (incomplete) ProviderResult.Partial(options, setOf(ProviderWarning.INCOMPLETE_COVERAGE), meta)
        else ProviderResult.Success(options, meta)
    }

    /**
     * Packs for a journey that changes between packs. A pack serving the origin and one serving the destination
     * form a pair when they share a catalogue station, other than the two ends, that is not a large detour
     * (origin → station → destination at most 1.5 times the direct distance plus 30 km), so a Swiss or French pack
     * that only shares Milano is not loaded for a trip across the Brenner. Pairs are taken by increasing detour
     * while both packs fit in [GtfsJourneyPlannerSettings.maxPacksPerSearch]. Without coordinates, or when no pair
     * qualifies, the best-ranked packs serving either end are used.
     */
    private fun unionForChanges(
        ranked: List<PackCoverage>,
        origin: Endpoint,
        destination: Endpoint,
        resolver: GtfsStopStationResolver
    ): List<PackCoverage> {
        val originPoint = origin.catalogueId?.let(resolver::station)?.point()
        val destinationPoint = destination.catalogueId?.let(resolver::station)?.point()
        val chosen = linkedSetOf<SourceId>()
        if (originPoint != null && destinationPoint != null) {
            val direct = distanceKm(originPoint, destinationPoint)
            val pairs = ArrayList<Triple<PackCoverage, PackCoverage, Double>>()
            for (first in ranked.filter { it.coversOrigin }) {
                for (second in ranked.filter { it.coversDestination }) {
                    if (first === second) continue
                    val detour = first.stops.catalogueStations.asSequence()
                        // The two ends themselves are no bridge: a pack reaching the destination needs no partner.
                        .filter { it in second.stops.catalogueStations && it != origin.catalogueId && it != destination.catalogueId }
                        .mapNotNull { id -> resolver.station(id)?.point() }
                        .map { point -> distanceKm(originPoint, point) + distanceKm(point, destinationPoint) }
                        .filter { it <= direct * 1.5 + 30.0 }
                        .minOrNull() ?: continue
                    pairs += Triple(first, second, detour)
                }
            }
            for ((first, second, _) in pairs.sortedBy { it.third }) {
                val added = setOf(first.pack.sourceId, second.pack.sourceId) - chosen
                if (chosen.size + added.size <= settings.maxPacksPerSearch) chosen += added
            }
        }
        if (chosen.isNotEmpty()) return ranked.filter { it.pack.sourceId in chosen }
        return (ranked.filter { it.coversOrigin }.take(1) + ranked.filter { it.coversDestination }.take(1) + ranked)
            .distinctBy { it.pack.sourceId }
    }

    private suspend fun searchPacks(
        selection: List<PackCoverage>,
        request: JourneySearchRequest,
        origin: Endpoint,
        destination: Endpoint,
        serviceDate: LocalDate,
        startMinute: Int,
        endMinute: Int,
        fetchedAt: Instant,
        resolver: GtfsStopStationResolver
    ): SearchOutcome {
        var incomplete = false
        // The previous service day only matters past its midnight, so it is cached as a partial day; the requested
        // day is cached whole for later searches, and so is the next one when the horizon reaches it.
        val lastConnectionMinute = endMinute + settings.journeyHorizonMinutes
        val parts = buildList {
            add(DayPart(serviceDate.minusDays(1), MINUTES_PER_DAY, Int.MAX_VALUE))
            add(DayPart(serviceDate, 0, Int.MAX_VALUE))
            // Whole, like a requested day: every later search of the same two dates, at any time of day, reuses it
            // instead of reading the pack again.
            if (lastConnectionMinute >= MINUTES_PER_DAY) add(DayPart(serviceDate.plusDays(1), 0, Int.MAX_VALUE))
        }
        val slices = ArrayList<Slice>()
        for (coverage in selection) {
            for (part in parts) {
                val day = try {
                    day(coverage.pack, part)
                } catch (_: GtfsCompactIndexLimitException) {
                    incomplete = true
                    break
                }
                if (day.connectionCount > 0) {
                    slices += Slice(coverage, day, ChronoUnit.DAYS.between(serviceDate, part.date).toInt() * MINUTES_PER_DAY)
                }
            }
        }
        val graph = buildGraph(slices, startMinute, lastConnectionMinute, resolver)
        incomplete = incomplete || graph.truncated
        val nodeCount = graph.timetable.nodeCount
        val origins = BooleanArray(nodeCount)
        val destinations = BooleanArray(nodeCount)
        val originWalk = IntArray(nodeCount) { -1 }
        val destinationWalk = IntArray(nodeCount) { -1 }
        fun mark(nodes: Map<String, Int>, exact: BooleanArray, walk: IntArray) {
            nodes.forEach { (key, minutes) ->
                val node = graph.nodeIndex[key] ?: return@forEach
                if (minutes == 0) exact[node] = true
                else if (walk[node] < 0 || minutes < walk[node]) walk[node] = minutes
            }
        }
        selection.forEach { coverage ->
            mark(coverage.originNodes, origins, originWalk)
            mark(coverage.destinationNodes, destinations, destinationWalk)
        }
        val job = currentCoroutineContext().job
        val journeys = ConnectionScanPlanner(graph.timetable).plan(
            ConnectionScanQuery(
                origins = origins,
                destinations = destinations,
                departureMinute = startMinute,
                latestDepartureMinute = endMinute,
                maxTransfers = request.maxTransfers,
                maxResults = request.maxResults,
                maxIterations = settings.maxIterations,
                lastConnectionMinute = lastConnectionMinute,
                originWalkMinutes = originWalk,
                destinationWalkMinutes = destinationWalk
            )
        ) { job.ensureActive() }
        val options = journeys.map { journey ->
            toOption(journey, graph, slices, request, origin, destination, serviceDate, fetchedAt, resolver)
        }
        return SearchOutcome(options, incomplete)
    }

    private fun buildGraph(slices: List<Slice>, startMinute: Int, lastMinute: Int, resolver: GtfsStopStationResolver): SearchGraph {
        require(slices.size <= MAX_SLICES) { "Troppi giorni di servizio in una ricerca" }
        val nodeIndex = HashMap<String, Int>()
        val nodeKeys = ArrayList<String>()
        fun node(key: String): Int = nodeIndex.getOrPut(key) { nodeKeys += key; nodeKeys.size - 1 }
        val stopNodes = slices.map { slice ->
            IntArray(slice.day.stopIds.size) { stop -> node(slice.coverage.stops.nodeKey(slice.day.stopIds[stop])) }
        }
        val tripOffsets = IntArray(slices.size)
        var tripCount = 0
        slices.forEachIndexed { index, slice ->
            tripOffsets[index] = tripCount
            tripCount += slice.day.trips.size
        }

        var keys = LongArray(4096)
        var count = 0
        var truncated = false
        selection@ for ((sliceIndex, slice) in slices.withIndex()) {
            val day = slice.day
            for (connection in 0 until day.connectionCount) {
                val departure = day.departureMinute[connection] + slice.offset
                if (departure < startMinute || departure > lastMinute) continue
                if (count == settings.maxSearchConnections) {
                    truncated = true
                    break@selection
                }
                if (count == keys.size) keys = keys.copyOf(minOf(keys.size * 2, settings.maxSearchConnections))
                val duration = (day.arrivalMinute[connection] - day.departureMinute[connection]).coerceAtMost(MAX_ENCODED_DURATION)
                keys[count++] = (departure.toLong() shl 42) or (duration.toLong() shl 28) or
                    (sliceIndex.toLong() shl 22) or connection.toLong()
            }
        }
        val runs = mergeCrossPackRuns(slices, keys, count, stopNodes, tripOffsets, tripCount)
        count = runs.keptConnections
        keys.sort(0, count)

        val departureNode = IntArray(count)
        val arrivalNode = IntArray(count)
        val departureMinute = IntArray(count)
        val arrivalMinute = IntArray(count)
        val trip = IntArray(count)
        val boarding = ByteArray(count)
        val sliceOf = IntArray(count)
        val connectionOf = IntArray(count)
        for (index in 0 until count) {
            val key = keys[index]
            val sliceIndex = ((key ushr 22) and 0x3F).toInt()
            val connection = (key and 0x3FFFFF).toInt()
            val slice = slices[sliceIndex]
            val day = slice.day
            departureNode[index] = stopNodes[sliceIndex][day.departureStop[connection]]
            arrivalNode[index] = stopNodes[sliceIndex][day.arrivalStop[connection]]
            departureMinute[index] = day.departureMinute[connection] + slice.offset
            arrivalMinute[index] = day.arrivalMinute[connection] + slice.offset
            trip[index] = runs.representative[tripOffsets[sliceIndex] + day.connectionTrip[connection]]
            boarding[index] = ConnectionScanTimetable.boardingFlags(day.pickupAllowed(connection), day.dropOffAllowed(connection))
            sliceOf[index] = sliceIndex
            connectionOf[index] = connection
        }
        val urbanPrefixes = slices.filter { it.coverage.pack.isUrban }.mapTo(HashSet()) { it.coverage.pack.sourceId.value + ":" }
        val transferMinutes = IntArray(nodeKeys.size) { node ->
            val key = nodeKeys[node]
            val railwayCode = resolver.station(key)?.railwayCode
            when {
                railwayCode != null && railwayCode in settings.largeHubRailwayCodes -> settings.largeHubTransferMinutes
                urbanPrefixes.any(key::startsWith) -> settings.urbanTransferMinutes
                else -> settings.defaultTransferMinutes
            }
        }
        val footpaths = footpaths(nodeKeys, slices, resolver, transferMinutes)
        return SearchGraph(
            timetable = ConnectionScanTimetable(
                nodeCount = nodeKeys.size,
                tripCount = tripCount,
                transferMinutes = transferMinutes,
                departureNode = departureNode,
                arrivalNode = arrivalNode,
                departureMinute = departureMinute,
                arrivalMinute = arrivalMinute,
                trip = trip,
                boarding = boarding,
                footStart = footpaths.start,
                footTarget = footpaths.target,
                footMinutes = footpaths.minutes
            ),
            nodeKeys = nodeKeys,
            nodeIndex = nodeIndex,
            sliceOf = sliceOf,
            connectionOf = connectionOf,
            truncated = truncated
        )
    }

    /**
     * Walking transfers between the transfer points of one search at most [GtfsJourneyPlannerSettings.maxWalkMeters]
     * apart (two stations, a station and a metro or tram stop, stops of two networks): minutes are the walk
     * ([GtfsJourneyPlannerSettings.walkMinutes]) and never less than the change time of the node walked to. Points
     * come from the catalogue station or, for a stop the catalogue does not know, from its pack; a node without
     * coordinates has no footpath. Nodes are bucketed on a grid of cells about [maxWalkMeters] wide.
     */
    private fun footpaths(
        nodeKeys: List<String>,
        slices: List<Slice>,
        resolver: GtfsStopStationResolver,
        transferMinutes: IntArray
    ): Footpaths {
        val size = nodeKeys.size
        val maxMeters = settings.maxWalkMeters.toDouble()
        if (maxMeters <= 0.0 || size == 0) return Footpaths(IntArray(size + 1), IntArray(0), IntArray(0))
        val stops = slices.map { it.coverage.stops }.distinct()
        val latitudes = DoubleArray(size) { Double.NaN }
        val longitudes = DoubleArray(size) { Double.NaN }
        for (node in 0 until size) {
            val key = nodeKeys[node]
            val station = resolver.station(key)
            val point = if (station?.latitude != null && station.longitude != null) doubleArrayOf(station.latitude, station.longitude)
            else stops.firstNotNullOfOrNull { it.point(key) }
            if (point != null) {
                latitudes[node] = point[0]
                longitudes[node] = point[1]
            }
        }
        val cellDegrees = maxMeters / 111_000.0
        fun cellOf(latitude: Double, longitude: Double): Long {
            val row = Math.floor(latitude / cellDegrees).toLong()
            val column = Math.floor(longitude * Math.cos(Math.toRadians(latitude)) / cellDegrees).toLong()
            return (row shl 32) xor (column and 0xFFFFFFFFL)
        }
        val cells = HashMap<Long, MutableList<Int>>()
        for (node in 0 until size) {
            if (latitudes[node].isNaN() || longitudes[node].isNaN()) continue
            cells.getOrPut(cellOf(latitudes[node], longitudes[node])) { ArrayList(4) } += node
        }
        val targets = Array(size) { IntArrayList() }
        val minutes = Array(size) { IntArrayList() }
        var total = 0
        for (node in 0 until size) {
            val latitude = latitudes[node]
            val longitude = longitudes[node]
            if (latitude.isNaN() || longitude.isNaN()) continue
            val row = Math.floor(latitude / cellDegrees).toLong()
            val column = Math.floor(longitude * Math.cos(Math.toRadians(latitude)) / cellDegrees).toLong()
            for (deltaRow in -1L..1L) for (deltaColumn in -1L..1L) {
                val cell = ((row + deltaRow) shl 32) xor ((column + deltaColumn) and 0xFFFFFFFFL)
                for (other in cells[cell] ?: continue) {
                    if (other == node) continue
                    val meters = GtfsStationCodeIndex.distanceKm(latitude, longitude, latitudes[other], longitudes[other]) * 1000.0
                    if (meters > maxMeters) continue
                    targets[node].add(other)
                    minutes[node].add(maxOf(settings.walkMinutes(meters), transferMinutes[other]))
                    total += 1
                }
            }
        }
        val start = IntArray(size + 1)
        val target = IntArray(total)
        val walk = IntArray(total)
        var position = 0
        for (node in 0 until size) {
            start[node] = position
            for (index in 0 until targets[node].size) {
                target[position] = targets[node][index]
                walk[position] = minutes[node][index]
                position += 1
            }
        }
        start[size] = position
        return Footpaths(start, target, walk)
    }

    /**
     * The same run published by two packs (an EuroCity in the Trenitalia and the ÖBB packs, a South Tyrol regional
     * in the Trenitalia and the STA packs) becomes one trip of the scan: a trip of a later (lower-ranked) slice that
     * has the same public number as a trip of another pack and at least one identical connection (same transfer
     * points, departure and arrival minute) takes that trip's identity, and its identical connections are dropped.
     * Staying on board across the part each pack publishes is then no change, and the run is scanned once.
     * Connections of [keys] (the first [count], in slice order, so the preferred pack comes first) are compacted in
     * place; the result maps every trip to the trip it is scanned as.
     */
    private fun mergeCrossPackRuns(
        slices: List<Slice>,
        keys: LongArray,
        count: Int,
        stopNodes: List<IntArray>,
        tripOffsets: IntArray,
        tripCount: Int
    ): MergedRuns {
        val representative = IntArray(tripCount) { it }
        if (slices.map { it.day.sourceId }.distinct().size < 2) return MergedRuns(representative, count)
        val numberOfTrip = arrayOfNulls<String>(tripCount)
        val sliceOfTrip = IntArray(tripCount)
        val sourcesByNumber = HashMap<String, MutableSet<SourceId>>()
        slices.forEachIndexed { sliceIndex, slice ->
            slice.day.trips.forEachIndexed { local, dayTrip ->
                val global = tripOffsets[sliceIndex] + local
                sliceOfTrip[global] = sliceIndex
                // City runs have no public number (their trip ids are not one) and are never published twice.
                if (dayTrip.mode in URBAN_MODES) return@forEachIndexed
                val number = runNumberKey(dayTrip.publicNumber) ?: return@forEachIndexed
                numberOfTrip[global] = number
                sourcesByNumber.getOrPut(number) { HashSet(2) } += slice.day.sourceId
            }
        }
        val shared = sourcesByNumber.filterValues { it.size > 1 }.keys
        if (shared.isEmpty()) return MergedRuns(representative, count)
        fun root(trip: Int): Int {
            var current = trip
            while (representative[current] != current) current = representative[current]
            return current
        }
        val owner = HashMap<RunConnection, Int>()
        var kept = 0
        for (index in 0 until count) {
            val key = keys[index]
            val sliceIndex = ((key ushr 22) and 0x3F).toInt()
            val connection = (key and 0x3FFFFF).toInt()
            val slice = slices[sliceIndex]
            val day = slice.day
            val trip = tripOffsets[sliceIndex] + day.connectionTrip[connection]
            val number = numberOfTrip[trip]
            var duplicate = false
            if (number != null && number in shared) {
                val identity = RunConnection(
                    number,
                    stopNodes[sliceIndex][day.departureStop[connection]],
                    stopNodes[sliceIndex][day.arrivalStop[connection]],
                    day.departureMinute[connection] + slice.offset,
                    day.arrivalMinute[connection] + slice.offset
                )
                val first = owner.putIfAbsent(identity, trip)
                if (first != null && first != trip && slices[sliceOfTrip[first]].day.sourceId != day.sourceId) {
                    val target = root(first)
                    if (representative[trip] == trip && target != trip) representative[trip] = target
                    duplicate = root(trip) == target
                }
            }
            if (!duplicate) keys[kept++] = key
        }
        for (trip in 0 until tripCount) representative[trip] = root(trip)
        return MergedRuns(representative, kept)
    }

    private fun toOption(
        journey: ScannedJourney,
        graph: SearchGraph,
        slices: List<Slice>,
        request: JourneySearchRequest,
        origin: Endpoint,
        destination: Endpoint,
        serviceDate: LocalDate,
        fetchedAt: Instant,
        resolver: GtfsStopStationResolver
    ): JourneyOption {
        val usedPacks = linkedSetOf<SourceId>()
        val legs = ArrayList<JourneyLeg>(journey.legs.size * 2 + 1)
        // Where each vehicle leg is boarded and left, for the walks between them.
        val boardPlaces = ArrayList<Pair<StationId, String>>(journey.legs.size)
        val alightPlaces = ArrayList<Pair<StationId, String>>(journey.legs.size)
        val vehicleLegs = journey.legs.mapIndexed { index, leg ->
            val day = slices[graph.sliceOf[leg.boardConnection]].day
            // A run merged across packs can be left on a connection only the other pack publishes.
            val alightDay = slices[graph.sliceOf[leg.alightConnection]].day
            val board = graph.connectionOf[leg.boardConnection]
            val alight = graph.connectionOf[leg.alightConnection]
            val trip = day.trips[day.connectionTrip[board]]
            usedPacks += day.sourceId
            usedPacks += alightDay.sourceId
            val boardStop = day.departureStop[board]
            val alightStop = alightDay.arrivalStop[alight]
            val boardPlace = place(graph.nodeKeys[graph.timetable.departureNode[leg.boardConnection]], day, boardStop, resolver)
            val alightPlace = place(graph.nodeKeys[graph.timetable.arrivalNode[leg.alightConnection]], alightDay, alightStop, resolver)
            boardPlaces += boardPlace
            alightPlaces += alightPlace
            val (originId, originLabel) = if (index == 0 && journey.accessWalk == null) request.origin to origin.name else boardPlace
            val (destinationId, destinationLabel) =
                if (index == journey.legs.lastIndex && journey.egressWalk == null) request.destination to destination.name else alightPlace
            val urban = trip.mode in URBAN_MODES
            // A city run has a line ("M1", "14") but no public number: its trip id is not one.
            val number = if (urban) null else displayNumber(trip.publicNumber)
            val line = (if (urban) urbanLineName(trip.mode, trip.lineLabel) else trip.lineLabel)?.takeIf { label ->
                label.length <= 10 && '(' !in label && !label.equals("INCONNU", ignoreCase = true) &&
                    !label.equals(number, ignoreCase = true)
            }
            JourneyLeg(
                mode = trip.mode,
                runId = TrainRunId("${day.sourceId.value}:${trip.tripId}:${day.serviceDate}"),
                origin = originId,
                destination = destinationId,
                scheduledDeparture = at(serviceDate, graph.timetable.departureMinute[leg.boardConnection]),
                scheduledArrival = at(serviceDate, graph.timetable.arrivalMinute[leg.alightConnection]),
                operatorId = trip.operatorName?.let { OperatorId(it) },
                publicNumber = number,
                lineLabel = line,
                originLabel = originLabel,
                destinationLabel = destinationLabel,
                serviceKind = serviceKind(trip.mode, trip.category, line),
                operatorName = trip.operatorName
            )
        }
        fun walk(from: Pair<StationId, String>, to: Pair<StationId, String>, startMinute: Int, minutes: Int) = JourneyLeg(
            mode = LegMode.WALK,
            origin = from.first,
            destination = to.first,
            scheduledDeparture = at(serviceDate, startMinute),
            scheduledArrival = at(serviceDate, startMinute + minutes),
            originLabel = from.second,
            destinationLabel = to.second
        )
        journey.accessWalk?.let { access ->
            val first = graph.timetable.departureMinute[journey.legs.first().boardConnection]
            legs += walk(request.origin to origin.name, boardPlaces.first(), first - access.minutes, access.minutes)
        }
        vehicleLegs.forEachIndexed { index, leg ->
            if (index > 0) {
                journey.transferWalks.getOrNull(index - 1)?.let { transfer ->
                    val arrived = graph.timetable.arrivalMinute[journey.legs[index - 1].alightConnection]
                    legs += walk(alightPlaces[index - 1], boardPlaces[index], arrived, transfer.minutes)
                }
            }
            legs += leg
        }
        journey.egressWalk?.let { egress ->
            val arrived = graph.timetable.arrivalMinute[journey.legs.last().alightConnection]
            legs += walk(alightPlaces.last(), request.destination to destination.name, arrived, egress.minutes)
        }
        val sources = usedPacks.map { sourceId ->
            SourceRef(
                sourceId = sourceId,
                fetchedAt = fetchedAt,
                expiresAt = fetchedAt.plus(Duration.ofHours(12)),
                confidence = Confidence.OPEN_DATA,
                traceId = "gtfs-journey:${sourceId.value}"
            )
        }.toSet()
        return JourneyOption(
            id = JourneyId("gtfs-journey:" + vehicleLegs.joinToString("|") { "${it.runId?.value}@${it.scheduledDeparture.toLocalDateTime()}" }),
            origin = request.origin,
            destination = request.destination,
            departure = legs.first().scheduledDeparture,
            arrival = legs.last().scheduledArrival,
            legs = legs,
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = fetchedAt,
                validUntil = fetchedAt.plus(Duration.ofHours(12)),
                freshness = Freshness.AGING,
                completeness = Completeness.STATIC_ONLY,
                contributingSources = sources,
                warnings = setOf(DataWarning.STATIC_ONLY, DataWarning.REALTIME_UNAVAILABLE)
            )
        )
    }

    private fun place(nodeKey: String, day: GtfsDayTimetable, stop: Int, resolver: GtfsStopStationResolver): Pair<StationId, String> {
        resolver.station(nodeKey)?.let { return StationId(it.id) to it.name }
        // A city stop keeps its own name ("Valfonda - Stazione Smn"): the railway shortening ("Stazione di …") is not
        // for it. The packs added with the city networks often publish names in capitals ("DUOMO M1", "CASELLA/PAESE").
        val label = if (day.sourceId in urbanSources) day.stopNames[stop] else resolver.displayName(day.stopNames[stop])
        return StationId("gtfs:${day.sourceId.value}:${day.stopIds[stop]}") to
            (if (day.sourceId in transitSources) StopNameCase.display(label) else label)
    }

    private fun at(serviceDate: LocalDate, minute: Int): ZonedDateTime =
        serviceDate.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone)

    private suspend fun resolver(): GtfsStopStationResolver {
        resolver?.let { return it }
        return resolverMutex.withLock {
            resolver ?: GtfsStopStationResolver(loadStations()).also { resolver = it }
        }
    }

    private suspend fun packStops(pack: EmbeddedGtfsPack, resolver: GtfsStopStationResolver): PackStops {
        synchronized(packStops) { packStops[pack.sourceId] }?.let { return it }
        return packStopsMutex.withLock {
            synchronized(packStops) { packStops[pack.sourceId] } ?: run {
                val directory = adapter.stopDirectory(pack)
                currentCoroutineContext().ensureActive()
                PackStops.build(pack, directory, resolver).also { built ->
                    synchronized(packStops) { packStops[pack.sourceId] = built }
                }
            }
        }
    }

    private suspend fun day(pack: EmbeddedGtfsPack, part: DayPart): GtfsDayTimetable {
        val key = "${pack.sourceId.value}|${part.date}|${part.fromMinute}|${part.untilMinute}"
        synchronized(dayCache) { dayCache[key] }?.let { return it }
        return dayMutex.withLock {
            synchronized(dayCache) { dayCache[key] } ?: adapter.dayTimetable(pack, part.date, part.fromMinute, part.untilMinute).also { built ->
                synchronized(dayCache) {
                    dayCache[key] = built
                    var bytes = dayCache.values.sumOf(GtfsDayTimetable::approximateBytes)
                    val iterator = dayCache.entries.iterator()
                    while (dayCache.size > 1 && (dayCache.size > settings.maxCachedDays || bytes > settings.maxCachedBytes) && iterator.hasNext()) {
                        val eldest = iterator.next()
                        if (eldest.key == key) continue
                        bytes -= eldest.value.approximateBytes()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun endpoint(id: StationId, hint: JourneyEndpoint?, resolver: GtfsStopStationResolver): Endpoint? {
        val catalogue = resolver.station(id.value)
        val station = catalogue ?: hint?.let {
            Station(id = id.value, name = it.name, city = it.name, railwayCode = it.railwayCode, aliases = it.aliases)
        } ?: return null
        val gtfsStop = id.value.takeIf { it.startsWith("gtfs:") }?.removePrefix("gtfs:")?.let { native ->
            val source = native.substringBefore(':')
            val stopId = native.substringAfter(':', "")
            if (source.isNotEmpty() && stopId.isNotEmpty()) source to stopId else null
        }
        // A metro or tram stop is that stop only: never a railway station by name ("Garibaldi FS" is not the station).
        if (gtfsStop != null && packs.any { it.isUrban && it.sourceId.value == gtfsStop.first }) {
            return Endpoint(name = station.name, catalogueId = null, keys = emptySet(), gtfsStop = gtfsStop, point = null)
        }
        // A station picked outside the catalogue joins the catalogue station its name resolves to, if exactly one.
        val resolved = catalogue ?: resolver.resolve(station.name)
        val keys = GtfsStopStationResolver.candidateKeys(station) +
            (resolved?.let(GtfsStopStationResolver::candidateKeys) ?: emptySet())
        val point = resolved?.point() ?: station.point()
        return Endpoint(name = catalogue?.name ?: station.name, catalogueId = resolved?.id, keys = keys, gtfsStop = gtfsStop, point = point)
    }

    /** A stop picked from a pack (`gtfs:<source>:<stop>`) without a catalogue position takes its pack's coordinates. */
    private suspend fun locateStop(endpoint: Endpoint, resolver: GtfsStopStationResolver) {
        if (endpoint.point != null) return
        val (source, stopId) = endpoint.gtfsStop ?: return
        val pack = packs.firstOrNull { it.sourceId.value == source } ?: return
        val stops = try {
            packStops(pack, resolver)
        } catch (_: GtfsCompactIndexLimitException) {
            return
        }
        endpoint.point = stops.stopPoint(stopId)
    }

    /** A city pack joins a search with an end at one of its stops or inside its area. */
    private fun urbanCandidate(pack: EmbeddedGtfsPack, origin: Endpoint, destination: Endpoint): Boolean {
        if (settings.maxUrbanPacksPerSearch == 0) return false
        if (origin.gtfsStop?.first == pack.sourceId.value || destination.gtfsStop?.first == pack.sourceId.value) return true
        return listOfNotNull(origin.point, destination.point).any { (latitude, longitude) ->
            pack.areas.any { area -> area.contains(latitude, longitude) }
        }
    }

    private fun notCovered(): ProviderResult<List<JourneyOption>> = ProviderResult.Failure(
        ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Le stazioni non sono coperte dagli orari offline inclusi.")
    )

    private class Endpoint(
        val name: String,
        val catalogueId: String?,
        val keys: Set<String>,
        val gtfsStop: Pair<String, String>?,
        /** Latitude and longitude, for the walks to nearby stops; null when unknown. */
        var point: Pair<Double, Double>?
    )

    /** Transfer-point identity of every stop of one pack; built once per pack and kept (bounded by the packs). */
    private class PackStops(
        private val sourceId: SourceId,
        private val nodeKeys: Array<String>,
        private val indexById: HashMap<String, Int>,
        private val stopsByMatchKey: HashMap<String, MutableList<Int>>,
        private val stopsByNode: HashMap<String, MutableList<Int>>,
        /** Catalogue stations among this pack's transfer points. */
        val catalogueStations: Set<String>,
        private val latitudes: DoubleArray,
        private val longitudes: DoubleArray,
        /** Catalogue coordinates of the catalogue nodes (they win over the pack's own stop coordinates). */
        private val cataloguePoints: Map<String, Pair<Double, Double>>
    ) {
        /** Stops with coordinates, bucketed on a grid of [GRID_DEGREES] for the nearby search. */
        private val grid: HashMap<Long, MutableList<Int>> = HashMap<Long, MutableList<Int>>().also { cells ->
            for (index in latitudes.indices) {
                if (latitudes[index].isNaN() || longitudes[index].isNaN()) continue
                cells.getOrPut(cell(latitudes[index], longitudes[index])) { ArrayList(4) } += index
            }
        }

        fun nodeKey(stopId: String): String = indexById[stopId]?.let { nodeKeys[it] } ?: "${sourceId.value}:stop:$stopId"

        fun nodesFor(endpoint: Endpoint): Set<String> {
            val nodes = linkedSetOf<String>()
            endpoint.catalogueId?.let { id -> stopsByNode[id]?.forEach { nodes += nodeKeys[it] } }
            for (key in endpoint.keys) stopsByMatchKey[key]?.forEach { nodes += nodeKeys[it] }
            endpoint.gtfsStop?.takeIf { it.first == sourceId.value }?.let { (_, stopId) ->
                indexById[stopId]?.let { nodes += nodeKeys[it] }
            }
            return nodes
        }

        /**
         * The transfer points of this pack a journey can start or end at for [endpoint], with the minutes of walking:
         * 0 for the endpoint's own nodes ([nodesFor]), the walk for the other points within
         * [GtfsJourneyPlannerSettings.maxWalkMeters] of the endpoint's coordinates.
         */
        fun accessFor(endpoint: Endpoint, settings: GtfsJourneyPlannerSettings): Map<String, Int> {
            val result = LinkedHashMap<String, Int>()
            nodesFor(endpoint).forEach { result[it] = 0 }
            val (latitude, longitude) = endpoint.point ?: return result
            val maxMeters = settings.maxWalkMeters.toDouble()
            if (maxMeters <= 0.0) return result
            val reach = Math.ceil(maxMeters / 111_000.0 / GRID_DEGREES).toInt().coerceAtLeast(1)
            val row = Math.floor(latitude / GRID_DEGREES).toLong()
            val column = Math.floor(longitude / GRID_DEGREES).toLong()
            for (deltaRow in -reach..reach) for (deltaColumn in -reach * 2..reach * 2) {
                val stops = grid[key(row + deltaRow, column + deltaColumn)] ?: continue
                for (stop in stops) {
                    val node = nodeKeys[stop]
                    if (result[node] == 0) continue
                    val point = cataloguePoints[node] ?: (latitudes[stop] to longitudes[stop])
                    val meters = GtfsStationCodeIndex.distanceKm(latitude, longitude, point.first, point.second) * 1000.0
                    if (meters > maxMeters) continue
                    val minutes = settings.walkMinutes(meters)
                    if (result[node]?.let { minutes < it } != false) result[node] = minutes
                }
            }
            return result
        }

        /** Coordinates of a transfer point of this pack: the catalogue station's, or those of its first stop with them. */
        fun point(nodeKey: String): DoubleArray? {
            cataloguePoints[nodeKey]?.let { return doubleArrayOf(it.first, it.second) }
            val stops = stopsByNode[nodeKey] ?: return null
            for (stop in stops) if (!latitudes[stop].isNaN() && !longitudes[stop].isNaN()) return doubleArrayOf(latitudes[stop], longitudes[stop])
            return null
        }

        /** Coordinates of one stop of this pack, or of its transfer point. */
        fun stopPoint(stopId: String): Pair<Double, Double>? {
            val index = indexById[stopId] ?: return null
            if (!latitudes[index].isNaN() && !longitudes[index].isNaN()) return latitudes[index] to longitudes[index]
            return point(nodeKeys[index])?.let { it[0] to it[1] }
        }

        companion object {
            /** About 550 m of latitude: the nearby search reads the 3 x 5 cells around a point. */
            private const val GRID_DEGREES = 0.005

            private fun key(row: Long, column: Long): Long = (row shl 32) xor (column and 0xFFFFFFFFL)

            private fun cell(latitude: Double, longitude: Double): Long =
                key(Math.floor(latitude / GRID_DEGREES).toLong(), Math.floor(longitude / GRID_DEGREES).toLong())

            /**
             * A stop's transfer point is the catalogue station it resolves to (by the station code the pack
             * publishes, if any, else by name); the platforms of one parent_station share the station any of them
             * resolves to (when they agree). Stops the catalogue does not know stay points of their own pack:
             * grouped by parent_station, or by identical name when they have none. The stops of a city network
             * ([EmbeddedGtfsPack.isUrban]) never resolve to a catalogue station: they reach stations by walking.
             */
            fun build(pack: EmbeddedGtfsPack, directory: GtfsStopDirectory, resolver: GtfsStopStationResolver): PackStops {
                val sourceId = pack.sourceId
                val size = directory.size
                val resolvedByName = HashMap<String, Station?>()
                val resolved = Array(size) { index ->
                    if (pack.isUrban) return@Array null
                    val name = directory.names[index]
                    val byCode = if (pack.stopCodeScheme == GtfsStopCodeScheme.NONE) null
                    else resolver.resolveCode(pack.stopCodeScheme, directory.codes[index], directory.latitude(index), directory.longitude(index), name)
                    byCode ?: resolvedByName.getOrPut(name) {
                        // "Matera Centrale" of the FAL pack is the catalogue's "Matera Centrale FAL" when it exists.
                        pack.catalogueNameSuffixes.firstNotNullOfOrNull { suffix -> resolver.resolve("$name $suffix") } ?: resolver.resolve(name)
                    }
                }
                val referencedParents = directory.parents.filter(String::isNotEmpty).toHashSet()
                val groupOf = Array(size) { index -> directory.parents[index].ifEmpty { directory.ids[index] } }
                val groupStation = HashMap<String, String>()
                val conflicting = HashSet<String>()
                for (index in 0 until size) {
                    val station = resolved[index] ?: continue
                    val group = groupOf[index]
                    val previous = groupStation.putIfAbsent(group, station.id)
                    if (previous != null && previous != station.id) conflicting += group
                }
                val nodeKeys = Array(size) { index ->
                    val group = groupOf[index]
                    val grouped = directory.parents[index].isNotEmpty() || directory.ids[index] in referencedParents
                    when {
                        group !in conflicting && groupStation[group] != null -> groupStation.getValue(group)
                        resolved[index] != null -> resolved[index]!!.id
                        grouped -> "${sourceId.value}:group:$group"
                        else -> "${sourceId.value}:name:${GtfsStopStationResolver.normalize(directory.names[index])}"
                    }
                }
                val indexById = HashMap<String, Int>(size * 2)
                val stopsByMatchKey = HashMap<String, MutableList<Int>>()
                val stopsByNode = HashMap<String, MutableList<Int>>()
                for (index in 0 until size) {
                    indexById[directory.ids[index]] = index
                    // A city stop is found by its id only, never by a railway station's name.
                    if (!pack.isUrban) for (key in resolver.matchKeys(directory.names[index])) stopsByMatchKey.getOrPut(key) { ArrayList(2) } += index
                    stopsByNode.getOrPut(nodeKeys[index]) { ArrayList(2) } += index
                }
                val catalogueStations = stopsByNode.keys.filterTo(HashSet()) { resolver.station(it) != null }
                val cataloguePoints = HashMap<String, Pair<Double, Double>>()
                for (id in catalogueStations) {
                    val station = resolver.station(id) ?: continue
                    val latitude = station.latitude ?: continue
                    val longitude = station.longitude ?: continue
                    cataloguePoints[id] = latitude to longitude
                }
                val latitudes = DoubleArray(size) { directory.latitude(it) ?: Double.NaN }
                val longitudes = DoubleArray(size) { directory.longitude(it) ?: Double.NaN }
                return PackStops(sourceId, nodeKeys, indexById, stopsByMatchKey, stopsByNode, catalogueStations, latitudes, longitudes, cataloguePoints)
            }
        }
    }

    private class PackCoverage(
        val pack: EmbeddedGtfsPack,
        val stops: PackStops,
        /** Transfer points of the pack for the origin, with the minutes of walking to them (0: the origin itself). */
        val originNodes: Map<String, Int>,
        /** The same for the destination. */
        val destinationNodes: Map<String, Int>
    ) {
        val coversOrigin: Boolean get() = originNodes.isNotEmpty()
        val coversDestination: Boolean get() = destinationNodes.isNotEmpty()
    }

    private class Slice(val coverage: PackCoverage, val day: GtfsDayTimetable, val offset: Int)

    private data class DayPart(val date: LocalDate, val fromMinute: Int, val untilMinute: Int)

    private fun Station.point(): Pair<Double, Double>? {
        val latitude = latitude ?: return null
        val longitude = longitude ?: return null
        return latitude to longitude
    }

    private fun distanceKm(first: Pair<Double, Double>, second: Pair<Double, Double>): Double {
        val earthRadiusKm = 6371.0
        val latitude1 = Math.toRadians(first.first)
        val latitude2 = Math.toRadians(second.first)
        val deltaLatitude = latitude2 - latitude1
        val deltaLongitude = Math.toRadians(second.second - first.second)
        val a = Math.sin(deltaLatitude / 2).let { it * it } +
            Math.cos(latitude1) * Math.cos(latitude2) * Math.sin(deltaLongitude / 2).let { it * it }
        return 2 * earthRadiusKm * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private class SearchGraph(
        val timetable: ConnectionScanTimetable,
        val nodeKeys: List<String>,
        val nodeIndex: Map<String, Int>,
        val sliceOf: IntArray,
        val connectionOf: IntArray,
        val truncated: Boolean
    )

    private class SearchOutcome(val options: List<JourneyOption>, val incomplete: Boolean)

    /** Footpaths in the [ConnectionScanTimetable] layout. */
    private class Footpaths(val start: IntArray, val target: IntArray, val minutes: IntArray)

    /** A growable int list without boxing. */
    private class IntArrayList {
        private var values = IntArray(4)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]
    }

    /** [representative] maps a trip to the trip it is scanned as; the first [keptConnections] keys remain. */
    private class MergedRuns(val representative: IntArray, val keptConnections: Int)

    /** One connection of a numbered run, in the search's transfer points and minutes. */
    private data class RunConnection(val number: String, val from: Int, val to: Int, val departure: Int, val arrival: Int)

    companion object {
        val SOURCE_ID: SourceId = KnownProviderIds.EMBEDDED_GTFS_JOURNEYS

        val DESCRIPTOR = ProviderDescriptor(
            id = SOURCE_ID,
            displayName = "Viaggi con cambi — calcolo offline sugli orari GTFS inclusi",
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH),
            coverage = CoverageDescriptor(
                countryCodes = setOf("IT", "CH", "AT", "FR", "HR"),
                description = "Solo le reti dei data pack GTFS inclusi nell'app (Trenitalia con i suoi bus, Italo con " +
                    "ItaloBus, Trenord, Alto Adige, Trentino, EAV, ARST, TFT, Svizzera, ÖBB, SNCF, HŽPP; le ferrovie " +
                    "regionali di gtfs-transit/ e gtfs-personal/; metropolitane, tram e funicolari delle città incluse, con " +
                    "brevi tratti a piedi): i treni fuori da questi feed non ci sono. Orari programmati, senza ritardi, " +
                    "binari né soppressioni."
            ),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(minimumRequestInterval = Duration.ZERO, defaultTimeToLive = Duration.ofHours(12)),
            schemaVersion = 1
        )

        private val URBAN_MODES = setOf(LegMode.METRO, LegMode.TRAM, LegMode.FUNICULAR)
        private const val MINUTES_PER_DAY = 24 * 60
        private const val MAX_SLICES = 63
        private const val MAX_ENCODED_DURATION = 0x3FFF

        /**
         * The production instance: its own adapter with a one-index cache, over the registry-enabled railway packs and
         * the city packs of this build.
         */
        fun create(
            openAsset: (String) -> InputStream,
            packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all + EmbeddedGtfsPacks.urban
        ): EmbeddedGtfsJourneyProvider = EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset, packs = packs, indexCacheCapacity = 1),
            packs = packs,
            loadStations = { withContext(Dispatchers.IO) { EmbeddedCuratedRailStationCatalog(openAsset).load() } }
        )

        /** "RE_11 - 2153" (Trenord's trip_short_name) shows as "2153"; other numbers are kept as published. */
        internal fun displayNumber(published: String): String? =
            published.substringAfterLast(" - ").trim().ifEmpty { published.trim() }.ifEmpty { null }

        /**
         * The number two packs publish for the same run, compared across packs: the digits of the displayed
         * number ("EC 85", "85" and "RE_11 - 85" all give "85"), or the letters and digits when it has no digit.
         * Numbers longer than eight characters are ids, not train numbers, and never merge.
         */
        internal fun runNumberKey(published: String): String? {
            val shown = displayNumber(published) ?: return null
            val digits = shown.filter(Char::isDigit).trimStart('0')
            val key = digits.ifEmpty { shown.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit) }
            return key.takeIf { it.isNotEmpty() && it.length <= 8 }
        }

        internal fun serviceKind(mode: LegMode, category: TrainCategory, line: String?): ServiceKind {
            if (mode == LegMode.REPLACEMENT_BUS) return ServiceKind.REPLACEMENT_BUS
            if (mode == LegMode.BUS) return ServiceKind.BUS
            if (mode in URBAN_MODES) return ServiceKind.URBAN
            // An Intercity Notte is a night train before it is an Intercity.
            if (line?.uppercase(Locale.ROOT)?.replace(" ", "") == "ICN") return ServiceKind.NIGHT
            when (category) {
                TrainCategory.FRECCE, TrainCategory.AV_ITALO -> return ServiceKind.HIGH_SPEED
                TrainCategory.INTERCITY -> return ServiceKind.INTERCITY
                else -> Unit
            }
            val code = line?.uppercase(Locale.ROOT)?.replace(" ", "").orEmpty()
            return when {
                code in setOf("NJ", "EN", "ICN") -> ServiceKind.NIGHT
                code in setOf("FR", "FA", "FB", "AV", "RJ", "RJX", "ICE", "TGV", "ES") -> ServiceKind.HIGH_SPEED
                code in setOf("EC", "IC", "IR") -> ServiceKind.INTERCITY
                Regex("S\\d{0,3}").matches(code) -> ServiceKind.SUBURBAN
                Regex("(R|RE|REX|RV|REG|RB|TER)\\d{0,3}").matches(code) -> ServiceKind.REGIONAL
                category == TrainCategory.REGIONALE || category == TrainCategory.REGIONALE_VELOCE ||
                    category == TrainCategory.TRENORD_REGIO -> ServiceKind.REGIONAL
                else -> ServiceKind.UNKNOWN
            }
        }
    }
}

/**
 * The public name of a city line from its route_short_name: Milan publishes its metro lines as "1".."5" ("M1"),
 * Rome as "MEA", "MEB1" ("A", "B1"); every other name is kept as published ("M1", "T2", "14").
 */
internal fun urbanLineName(mode: LegMode, label: String?): String? {
    val value = label?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (mode != LegMode.METRO) return value
    if (value.length <= 2 && value.all(Char::isDigit)) return "M$value"
    return ROME_METRO_LINE.matchEntire(value)?.groupValues?.get(1) ?: value
}

private val ROME_METRO_LINE = Regex("ME([A-Z][0-9]?)")
