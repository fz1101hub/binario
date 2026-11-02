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
    val maxPacksPerSearch: Int = 3,
    val maxIterations: Int = 32,
    /** Bound on the connections of one search window (they are copied into the scan arrays). */
    val maxSearchConnections: Int = 400_000,
    /** Bound on the cached per-pack, per-service-day connection arrays (the Swiss pack's day is about 5 MB). */
    val maxCachedDays: Int = 16,
    val maxCachedBytes: Long = 16L * 1024L * 1024L
) {
    init {
        require(defaultTransferMinutes in 0..60 && largeHubTransferMinutes in 0..60) { "Tempo di cambio non valido" }
        require(searchWindowMinutes in 30..24 * 60) { "Finestra di ricerca non valida" }
        require(maxPacksPerSearch in 1..8) { "Numero di pack non valido" }
        require(maxIterations > 0 && maxSearchConnections > 0 && maxCachedDays > 0 && maxCachedBytes > 0)
    }

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

        val departure = request.departureAt.withZoneSameInstant(zone).toLocalDateTime()
        val serviceDate = departure.toLocalDate()
        val startMinute = departure.hour * 60 + departure.minute
        val endMinute = startMinute + settings.searchWindowMinutes

        var incomplete = false
        val coverage = ArrayList<PackCoverage>()
        for (pack in packs) {
            currentCoroutineContext().ensureActive()
            val stops = try {
                packStops(pack, resolver)
            } catch (_: GtfsCompactIndexLimitException) {
                incomplete = true
                continue
            }
            val originNodes = stops.nodesFor(origin)
            val destinationNodes = stops.nodesFor(destination)
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
        val ranked = current.sortedByDescending {
            RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, it.pack.sourceId, null)
        }
        val primary = ranked.filter { it.coversOrigin && it.coversDestination }
        val union = unionForChanges(ranked, origin, destination, resolver)
        val selections = listOf(primary, union).filter(List<PackCoverage>::isNotEmpty)
            .map { it.take(settings.maxPacksPerSearch) }
            .distinctBy { selection -> selection.map { it.pack.sourceId } }

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
     * form a pair when they share a catalogue station, other than the two ends, that is not a large detour (origin → station → destination
     * at most 1.5 times the direct distance plus 30 km), so a Swiss or French pack that only shares Milano is not
     * loaded for a trip across the Brenner. Pairs are taken by increasing detour while both packs fit in
     * [GtfsJourneyPlannerSettings.maxPacksPerSearch]. Without coordinates, or when no pair qualifies, the
     * best-ranked packs serving either end are used.
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
        // The previous service day only matters past its midnight and the next one only before the window ends,
        // so those two are cached as partial days; the requested day is cached whole for later searches.
        val parts = buildList {
            add(DayPart(serviceDate.minusDays(1), MINUTES_PER_DAY, Int.MAX_VALUE))
            add(DayPart(serviceDate, 0, Int.MAX_VALUE))
            if (endMinute >= MINUTES_PER_DAY) add(DayPart(serviceDate.plusDays(1), 0, settings.searchWindowMinutes - 1))
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
        val graph = buildGraph(slices, startMinute, endMinute, resolver)
        incomplete = incomplete || graph.truncated
        val origins = BooleanArray(graph.timetable.nodeCount)
        val destinations = BooleanArray(graph.timetable.nodeCount)
        selection.forEach { coverage ->
            coverage.originNodes.forEach { key -> graph.nodeIndex[key]?.let { origins[it] = true } }
            coverage.destinationNodes.forEach { key -> graph.nodeIndex[key]?.let { destinations[it] = true } }
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
                maxIterations = settings.maxIterations
            )
        ) { job.ensureActive() }
        val options = journeys.map { journey ->
            toOption(journey, graph, slices, request, origin, destination, serviceDate, fetchedAt, resolver)
        }
        return SearchOutcome(options, incomplete)
    }

    private fun buildGraph(slices: List<Slice>, startMinute: Int, endMinute: Int, resolver: GtfsStopStationResolver): SearchGraph {
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
                if (departure < startMinute || departure > endMinute) continue
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
            trip[index] = tripOffsets[sliceIndex] + day.connectionTrip[connection]
            boarding[index] = ConnectionScanTimetable.boardingFlags(day.pickupAllowed(connection), day.dropOffAllowed(connection))
            sliceOf[index] = sliceIndex
            connectionOf[index] = connection
        }
        val transferMinutes = IntArray(nodeKeys.size) { node ->
            val railwayCode = resolver.station(nodeKeys[node])?.railwayCode
            if (railwayCode != null && railwayCode in settings.largeHubRailwayCodes) settings.largeHubTransferMinutes
            else settings.defaultTransferMinutes
        }
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
                boarding = boarding
            ),
            nodeKeys = nodeKeys,
            nodeIndex = nodeIndex,
            sliceOf = sliceOf,
            connectionOf = connectionOf,
            truncated = truncated
        )
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
        val legs = journey.legs.mapIndexed { index, leg ->
            val slice = slices[graph.sliceOf[leg.boardConnection]]
            val day = slice.day
            val board = graph.connectionOf[leg.boardConnection]
            val alight = graph.connectionOf[leg.alightConnection]
            val trip = day.trips[day.connectionTrip[board]]
            usedPacks += day.sourceId
            val boardStop = day.departureStop[board]
            val alightStop = day.arrivalStop[alight]
            val (originId, originLabel) = if (index == 0) request.origin to origin.name
            else place(graph.nodeKeys[graph.timetable.departureNode[leg.boardConnection]], day, boardStop, resolver)
            val (destinationId, destinationLabel) = if (index == journey.legs.lastIndex) request.destination to destination.name
            else place(graph.nodeKeys[graph.timetable.arrivalNode[leg.alightConnection]], day, alightStop, resolver)
            val number = displayNumber(trip.publicNumber)
            val line = trip.lineLabel?.takeIf { label ->
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
            id = JourneyId("gtfs-journey:" + legs.joinToString("|") { "${it.runId?.value}@${it.scheduledDeparture.toLocalDateTime()}" }),
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
        return StationId("gtfs:${day.sourceId.value}:${day.stopIds[stop]}") to resolver.displayName(day.stopNames[stop])
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
                PackStops.build(pack.sourceId, directory, resolver).also { built ->
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
        // A station picked outside the catalogue joins the catalogue station its name resolves to, if exactly one.
        val resolved = catalogue ?: resolver.resolve(station.name)
        val gtfsStop = id.value.takeIf { it.startsWith("gtfs:") }?.removePrefix("gtfs:")?.let { native ->
            val source = native.substringBefore(':')
            val stopId = native.substringAfter(':', "")
            if (source.isNotEmpty() && stopId.isNotEmpty()) source to stopId else null
        }
        val keys = GtfsStopStationResolver.candidateKeys(station) +
            (resolved?.let(GtfsStopStationResolver::candidateKeys) ?: emptySet())
        return Endpoint(name = catalogue?.name ?: station.name, catalogueId = resolved?.id, keys = keys, gtfsStop = gtfsStop)
    }

    private fun notCovered(): ProviderResult<List<JourneyOption>> = ProviderResult.Failure(
        ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Le stazioni non sono coperte dagli orari offline inclusi.")
    )

    private class Endpoint(
        val name: String,
        val catalogueId: String?,
        val keys: Set<String>,
        val gtfsStop: Pair<String, String>?
    )

    /** Transfer-point identity of every stop of one pack; built once per pack and kept (bounded by the packs). */
    private class PackStops(
        private val sourceId: SourceId,
        private val nodeKeys: Array<String>,
        private val indexById: HashMap<String, Int>,
        private val stopsByMatchKey: HashMap<String, MutableList<Int>>,
        private val stopsByNode: HashMap<String, MutableList<Int>>,
        /** Catalogue stations among this pack's transfer points. */
        val catalogueStations: Set<String>
    ) {
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

        companion object {
            /**
             * A stop's transfer point is the catalogue station it resolves to; the platforms of one parent_station
             * share the station any of them resolves to (when they agree). Stops the catalogue does not know stay
             * points of their own pack: grouped by parent_station, or by identical name when they have none.
             */
            fun build(sourceId: SourceId, directory: GtfsStopDirectory, resolver: GtfsStopStationResolver): PackStops {
                val size = directory.size
                val resolvedByName = HashMap<String, Station?>()
                val resolved = Array(size) { index ->
                    val name = directory.names[index]
                    resolvedByName.getOrPut(name) { resolver.resolve(name) }
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
                    for (key in resolver.matchKeys(directory.names[index])) stopsByMatchKey.getOrPut(key) { ArrayList(2) } += index
                    stopsByNode.getOrPut(nodeKeys[index]) { ArrayList(2) } += index
                }
                val catalogueStations = stopsByNode.keys.filterTo(HashSet()) { resolver.station(it) != null }
                return PackStops(sourceId, nodeKeys, indexById, stopsByMatchKey, stopsByNode, catalogueStations)
            }
        }
    }

    private class PackCoverage(
        val pack: EmbeddedGtfsPack,
        val stops: PackStops,
        val originNodes: Set<String>,
        val destinationNodes: Set<String>
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

    companion object {
        val SOURCE_ID: SourceId = KnownProviderIds.EMBEDDED_GTFS_JOURNEYS

        val DESCRIPTOR = ProviderDescriptor(
            id = SOURCE_ID,
            displayName = "Viaggi con cambi — calcolo offline sugli orari GTFS inclusi",
            capabilities = setOf(ProviderCapability.JOURNEY_SEARCH),
            coverage = CoverageDescriptor(
                countryCodes = setOf("IT", "CH", "AT", "FR", "HR"),
                description = "Solo le reti dei data pack GTFS inclusi nell'app (Trenord, Alto Adige, Trentino, EAV, " +
                    "ARST, TFT, Svizzera, ÖBB, SNCF, HŽPP): i treni fuori da questi feed non ci sono. Orari programmati, " +
                    "senza ritardi, binari né soppressioni."
            ),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(minimumRequestInterval = Duration.ZERO, defaultTimeToLive = Duration.ofHours(12)),
            schemaVersion = 1
        )

        private const val MINUTES_PER_DAY = 24 * 60
        private const val MAX_SLICES = 63
        private const val MAX_ENCODED_DURATION = 0x3FFF

        /** The production instance: its own adapter with a one-index cache, over the registry-enabled packs. */
        fun create(
            openAsset: (String) -> InputStream,
            packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all
        ): EmbeddedGtfsJourneyProvider = EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset, packs = packs, indexCacheCapacity = 1),
            packs = packs,
            loadStations = { withContext(Dispatchers.IO) { EmbeddedCuratedRailStationCatalog(openAsset).load() } }
        )

        /** "RE_11 - 2153" (Trenord's trip_short_name) shows as "2153"; other numbers are kept as published. */
        internal fun displayNumber(published: String): String? =
            published.substringAfterLast(" - ").trim().ifEmpty { published.trim() }.ifEmpty { null }

        internal fun serviceKind(mode: LegMode, category: TrainCategory, line: String?): ServiceKind {
            if (mode == LegMode.REPLACEMENT_BUS) return ServiceKind.REPLACEMENT_BUS
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
