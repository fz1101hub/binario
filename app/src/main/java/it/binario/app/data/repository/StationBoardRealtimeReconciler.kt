package it.binario.app.data.repository

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.matchesPublishedName
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Uses identity-checked operator routes for today, then enriches programmed
 * services by matching their public number, service day and schedule.
 */
class StationBoardEnrichedTrainDataSource(
    private val plannedSource: TrainDataSource,
    private val boardProviders: List<StationBoardProvider>,
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val zone: ZoneId = RailTime.defaultZone,
    private val liveRouteSources: List<TrainDataSource> = emptyList(),
    private val liveSourceTimeoutMillis: Long = 15_000
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult = coroutineScope {
        // The timetable is always needed and starts with the live sources, as in [observeRoute], not after them: a
        // caller with little time (the commuter reminder has seven seconds in a cold process) no longer waits for
        // the two one after the other (audit fix-android-6). The same requests, only not in sequence.
        val plannedCall = async { plannedSource.loadRoute(route, direction, query) }
        val live = liveFirst(route, direction, query)
        val verified = live.verified
            ?: return@coroutineScope afterPlanned(plannedCall.await(), live.unverified, route, direction, query)
        // A live route holds only the runs whose details arrived in time (never Italo): it enriches the planned list,
        // whose other trains keep their timetable and board data (audit android-data-2).
        val planned = plannedCall.await() as? DataResult.Success ?: return@coroutineScope verified
        LiveRunMerge.merge(afterPlanned(planned, null, route, direction, query), verified, query)
    }

    /**
     * [loadRoute] published as it improves: the offline rows (then with their GTFS-Realtime) appear while the live
     * sources answer; the verified live runs then take the place of the same planned runs, and the board
     * reconciliation comes last. The final emission is exactly what [loadRoute] returns.
     */
    override fun observeRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): Flow<DataResult> = channelFlow {
        val lock = Mutex()
        var verifiedLive: DataResult.Success? = null
        var latestPlanned: DataResult? = null
        var lastSent: DataResult? = null
        suspend fun publish(result: DataResult) {
            if (result != lastSent) { lastSent = result; send(result) }
        }
        val live = async { liveFirst(route, direction, query) }
        val planned = async {
            plannedSource.observeRoute(route, direction, query).collect { result ->
                lock.withLock {
                    latestPlanned = result
                    if (result is DataResult.Success) publish(LiveRunMerge.merge(result, verifiedLive, query))
                }
            }
            lock.withLock { latestPlanned }
        }
        val verdict = live.await()
        verdict.verified?.let { verified ->
            lock.withLock {
                verifiedLive = verified
                (latestPlanned as? DataResult.Success)?.let { publish(LiveRunMerge.merge(it, verified, query)) }
            }
        }
        val plannedResult = planned.await() ?: plannedSource.loadRoute(route, direction, query)
        val final = verdict.verified?.takeIf { plannedResult !is DataResult.Success }
            ?: LiveRunMerge.merge(afterPlanned(plannedResult, verdict.unverified, route, direction, query), verdict.verified, query)
        lock.withLock { publish(final) }
    }

    private class LiveVerdict(val verified: DataResult.Success?, val unverified: DataResult.Success?)

    private suspend fun liveFirst(route: SavedRoute, direction: Direction, query: JourneyQuery): LiveVerdict {
        val today = clock.instant().atZone(zone).toLocalDate()
        var unverifiedLive: DataResult.Success? = null
        if (query.serviceDate == today) {
            val verified = coroutineScope {
                val pending = liveRouteSources.map { source -> async {
                    try { withTimeoutOrNull(liveSourceTimeoutMillis) { source.loadRoute(route, direction, query) } }
                        catch (error: CancellationException) { currentCoroutineContext().ensureActive(); null }
                        catch (_: Exception) { null }
                } }
                // Start together, but preserve operator/infrastructure precedence:
                // network speed must not decide which published delay wins.
                try { for (attempt in pending) {
                    val live = attempt.await()
                    if (live is DataResult.Success) {
                        if (live.snapshot.services.any { it.operationalStatusVerified }) return@coroutineScope live
                        if (unverifiedLive == null) unverifiedLive = live
                    }
                }
                null
                } finally { pending.forEach { it.cancel() } }
            }
            if (verified != null) return LiveVerdict(verified, unverifiedLive)
        }
        return LiveVerdict(null, unverifiedLive)
    }

    private suspend fun afterPlanned(
        planned: DataResult,
        unverifiedLive: DataResult.Success?,
        route: SavedRoute,
        direction: Direction,
        query: JourneyQuery
    ): DataResult {
        val today = clock.instant().atZone(zone).toLocalDate()
        if (planned is DataResult.Success) {
            if (query.serviceDate != today || boardProviders.isEmpty()) return planned
            val snapshot = StationBoardRealtimeReconciler(
                providers = boardProviders,
                clock = clock,
                zone = zone
            ).enrich(planned.snapshot, query)
            return DataResult.Success(snapshot)
        }

        // A matched timetable without an operational observation must not mask
        // the next live source. Retain it only if planning is unavailable too.
        unverifiedLive?.let { return it }

        if (query.serviceDate == today) {
            boardProviders.forEach { provider ->
                val legacy = provider as? TrainDataSource ?: return@forEach
                when (val live = withTimeoutOrNull(liveSourceTimeoutMillis) { legacy.loadRoute(route, direction, query) }) {
                    is DataResult.Success -> {
                        return DataResult.Success(
                            live.snapshot.copy(
                                notice = listOfNotNull(
                                    "Fallback realtime diretto: nessun itinerario programmato disponibile per questa tratta.",
                                    live.snapshot.notice
                                ).joinToString(" ")
                            )
                        )
                    }
                    is DataResult.Failure -> Unit
                    null -> Unit
                }
            }
        }
        return planned
    }
}

/**
 * The verified live runs of a route over the planned list (audit android-data-2). A live route holds only the runs whose
 * details arrived in time, never Italo: it must never replace the list. A live run takes the place of the planned
 * service of the same run (core number, compatible operator, the nearest scheduled departure within [RETIMING_WINDOW])
 * unless the live run is unverified and the boards verified the planned one; a live run the timetable does not list is
 * added; every other planned service stays as the timetable and the boards left it. Which live source answered is
 * decided before, in the same order as always.
 */
internal object LiveRunMerge {
    /**
     * On one day and one station pair a train number is one train: a run the day's timetable moved by half an hour
     * (works, an old pack) is still the planned train, not a second one (audit fix-android-2). Both sides carry the
     * full date and time, so the run of the next day, about 24 hours away, stays apart.
     */
    private val RETIMING_WINDOW: Duration = Duration.ofHours(3)

    fun merge(planned: DataResult, live: DataResult.Success?, query: JourneyQuery): DataResult {
        if (live == null) return planned
        if (planned !is DataResult.Success) return live
        val base = planned.snapshot
        val services = base.services.toMutableList()
        val taken = HashSet<Int>()
        val added = mutableListOf<TrainService>()
        var used = false
        live.snapshot.services.forEach { run ->
            val runTime = scheduled(run, query) ?: return@forEach
            val index = services.indices
                .filter { it !in taken && sameRun(services[it], run) }
                .mapNotNull { index -> scheduled(services[index], query)?.let { index to abs(Duration.between(it, runTime).toMinutes()) } }
                .filter { (_, delta) -> delta <= RETIMING_WINDOW.toMinutes() }
                .minByOrNull { (_, delta) -> delta }?.first
            if (index == null) {
                added += run
                used = true
            } else {
                taken += index
                // An unverified live run does not mask what the boards verified for the same train.
                if (run.operationalStatusVerified || !services[index].operationalStatusVerified) {
                    services[index] = withTimetableIdentity(run, services[index])
                    used = true
                }
            }
        }
        if (!used) return planned
        val liveSnapshot = live.snapshot
        return DataResult.Success(base.copy(
            services = it.binario.app.data.orderServicesForDisplay(services + added),
            providerName = "${base.providerName} + ${liveSnapshot.providerName}",
            retrievedAtEpochMillis = maxOf(base.retrievedAtEpochMillis, liveSnapshot.retrievedAtEpochMillis),
            sourceObservedAtEpochMillis = maxOf(base.sourceObservedAtEpochMillis, liveSnapshot.sourceObservedAtEpochMillis),
            expiresAtEpochMillis = minOf(base.expiresAtEpochMillis, liveSnapshot.expiresAtEpochMillis),
            freshness = if (base.freshness == Freshness.FRESH || liveSnapshot.freshness == Freshness.FRESH) Freshness.FRESH else liveSnapshot.freshness,
            qualityWarnings = base.qualityWarnings - SnapshotWarning.STATIC_ONLY,
            notice = listOfNotNull(base.notice?.takeIf(String::isNotBlank), liveSnapshot.notice?.takeIf(String::isNotBlank))
                .joinToString(" ").takeIf(String::isNotBlank)
        ))
    }

    /**
     * The live run in the place of its planned service keeps what only the timetable knows: who runs the train when
     * the live source does not say (never «Altro operatore» over a known company, audit fix-android-1), the category
     * and whether bikes are allowed. Everything operational is the live run's.
     */
    private fun withTimetableIdentity(live: TrainService, planned: TrainService): TrainService {
        val unknownOperator = live.operator == RailOperator.OTHER && live.operatorDisplayName.isNullOrBlank()
        return live.copy(
            operator = if (unknownOperator) planned.operator else live.operator,
            operatorDisplayName = if (unknownOperator) planned.operatorDisplayName else live.operatorDisplayName,
            trainCategory = if (live.trainCategory == TrainCategory.OTHER) planned.trainCategory else live.trainCategory,
            bikesAllowed = live.bikesAllowed ?: planned.bikesAllowed
        )
    }

    private fun sameRun(planned: TrainService, live: TrainService): Boolean {
        val number = coreNumber(live.number) ?: return false
        if (coreNumber(planned.number) != number) return false
        val plannedOperator = operatorFamily(planned.operator) ?: return true
        val liveOperator = operatorFamily(live.operator) ?: return true
        return plannedOperator == liveOperator
    }

    /** ViaggiaTreno publishes Trenitalia and Trenord runs under one identity, so the two count as one family here. */
    private fun operatorFamily(operator: RailOperator): String? = when (operator) {
        RailOperator.TRENITALIA_REGIONALE, RailOperator.TRENITALIA_LONG_DISTANCE, RailOperator.TRENITALIA_AV,
        RailOperator.TRENORD -> "trenitalia-trenord"
        RailOperator.ITALO -> "italo"
        RailOperator.OTHER -> null
        else -> operator.name
    }

    /** "S11 - 25017" → "25017", "REG 16348" → "16348". */
    private fun coreNumber(value: String): String? =
        Regex("[0-9]+").findAll(value.substringAfterLast(" - ")).lastOrNull()?.value?.trimStart('0')?.takeIf(String::isNotEmpty)

    private fun scheduled(service: TrainService, query: JourneyQuery): LocalDateTime? {
        service.scheduledDepartureDateTime?.let { return it }
        val parts = service.scheduledDeparture.trim().split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val candidate = query.requestedAt.toLocalDate().atTime(hour, minute)
        return if (candidate.isBefore(query.requestedAt.minusHours(12))) candidate.plusDays(1) else candidate
    }
}

class StationBoardRealtimeReconciler(
    private val providers: List<StationBoardProvider>,
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val zone: ZoneId = RailTime.defaultZone,
    private val scheduleTolerance: Duration = Duration.ofMinutes(20),
    private val conflictTolerance: Duration = Duration.ofMinutes(5),
    private val providerTimeoutMillis: Long = 8_000
) {
    suspend fun enrich(snapshot: RouteSnapshot, query: JourneyQuery): RouteSnapshot {
        if (snapshot.services.isEmpty()) return snapshot

        val origin = snapshot.route.origin(snapshot.direction)
        val destination = snapshot.route.destination(snapshot.direction)
        val requested = query.requestedAt.atZone(zone)
        // The instants the live route of the same refresh asks ViaggiaTreno for (the requested time and, for a search
        // around now, two hours before it for the trains still late to leave): the board is then answered from the
        // shared response cache instead of asking for a third window (audit fix-android-5).
        val from = requested.toInstant()
        val request = StationBoardRequest(
            stationId = StationId(origin.id),
            stationName = origin.name,
            stationAliases = origin.aliases,
            railwayCode = origin.railwayCode,
            from = from,
            to = requested.plusHours(8).toInstant(),
            lateSince = from.minus(LATE_LOOKBACK).takeIf { !from.isAfter(clock.instant().plus(AROUND_NOW)) },
            kind = BoardKind.DEPARTURES,
            trenitaliaLocationId = origin.trenitaliaLocationId,
            coordinates = origin.catalogueCoordinates()
        )

        val boards = coroutineScope {
            providers.map { provider ->
                async {
                    try { withTimeoutOrNull(providerTimeoutMillis) {
                    when (val result = provider.getStationBoard(request)) {
                        is ProviderResult.Success -> ProviderBoard(provider.descriptor.displayName, result.value)
                        is ProviderResult.Partial -> ProviderBoard(provider.descriptor.displayName, result.value)
                        is ProviderResult.Failure,
                        is ProviderResult.NotModified -> null
                    }
                    } } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
                }
            }.awaitAll().filterNotNull()
        }
        if (boards.isEmpty()) return snapshot

        var conflict = false
        val usedProviders = linkedSetOf<String>()
        var newestObservedAt: Long? = null
        var earliestExpiry: Long? = null

        val enrichedServices = snapshot.services.map { service ->
            val matches = boards.mapNotNull { providerBoard ->
                bestMatch(service, destination, providerBoard.board)?.let { entry ->
                    MatchedEntry(providerBoard.providerName, entry)
                }
            }
            if (matches.isEmpty()) return@map service

            if (hasConflict(matches)) conflict = true

            // Choose each operational field with the source hierarchy for that
            // information category, never by provider-list order.
            val expectedMatch = bestSourcedMatch(
                matches = matches,
                category = RailInformationCategory.TRAIN_REALTIME,
                operatorHint = service.accessibleOperator,
                source = { it.entry.expectedTime?.source }
            )
            val statusMatch = bestSourcedMatch(
                matches = matches,
                category = RailInformationCategory.TRAIN_REALTIME,
                operatorHint = service.accessibleOperator,
                source = { it.entry.status?.source }
            )
            val platformMatch = bestSourcedMatch(
                matches = matches,
                category = RailInformationCategory.STATION_BOARD,
                operatorHint = service.accessibleOperator,
                source = { it.entry.platform?.source }
            )
            val routeChangeMatches = matches.filter { it.entry.routeChange != null }
            val routeChangeMatch = bestSourcedMatch(
                matches = routeChangeMatches,
                category = RailInformationCategory.ROUTE_VARIATIONS,
                operatorHint = service.accessibleOperator,
                source = { it.entry.routeChange?.source }
            )

            val operationalSources = listOfNotNull(
                expectedMatch?.entry?.expectedTime?.source,
                statusMatch?.entry?.status?.source,
                platformMatch?.entry?.platform?.source
            ).distinctBy { it.sourceId }
            val routeChangeSources = routeChangeMatches.mapNotNull { it.entry.routeChange?.source }
                .distinctBy { it.sourceId }
            val selectedSources = (operationalSources + routeChangeSources).distinctBy { it.sourceId }

            selectedSources.forEach { source ->
                usedProviders += source.sourceId.value
                val observed = (source.observedAt ?: source.fetchedAt).toEpochMilli()
                newestObservedAt = maxOf(newestObservedAt ?: observed, observed)
                source.expiresAt?.toEpochMilli()?.let { expiry ->
                    earliestExpiry = minOf(earliestExpiry ?: expiry, expiry)
                }
            }

            val scheduled = expectedMatch?.entry?.scheduledTime ?: scheduledDateTime(service, query)
            val scheduledDeparture = scheduled?.let(::displayTime) ?: service.scheduledDeparture
            val expected = expectedMatch?.entry?.expectedTime?.value
            val expectedDeparture = expected?.let(::displayTime) ?: service.expectedDeparture
            val delayMinutes = if (expected != null && scheduled != null) {
                Duration.between(scheduled, expected).toMinutes().toInt()
            } else {
                service.delayMinutes
            }

            // A board that shows the train late without minutes («RITARDO»): no verified status and no delay to show,
            // so nothing reads it as on time; the train says «In ritardo (minuti non pubblicati)».
            val lateWithoutMinutes = expected == null && delayMinutes <= 0 && !service.operationalStatusVerified &&
                statusMatch?.entry?.status?.value == RunStatus.DELAYED
            val liveStatus = statusMatch?.entry?.status?.value?.takeUnless { lateWithoutMinutes }?.toLegacyStatus()
                ?: if (expected != null) {
                    if (delayMinutes > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME
                } else null

            val platform = platformMatch?.entry?.platform?.value ?: service.platform
            // The timetable platform comes with the actual one, from the same source, so a change is never mixed up.
            val scheduledPlatform = if (platformMatch?.entry?.platform != null) platformMatch.entry.scheduledPlatform?.value else service.scheduledPlatform
            val verified = liveStatus != null || expected != null
            val routeChange = routeChangeMatch?.entry?.routeChange?.value ?: service.routeChange
            val originStop = service.stops.firstOrNull()
            val timingUpdatedStops = if (originStop != null) {
                listOf(
                    originStop.copy(
                        scheduled = scheduledDeparture,
                        expected = expectedDeparture,
                        platform = platform,
                        scheduledPlatform = scheduledPlatform,
                        delayMinutes = delayMinutes,
                        operationalStatusVerified = if (expected != null) true else originStop.operationalStatusVerified
                    )
                ) + service.stops.drop(1).map { stop ->
                    // Legacy programmed stops must not inherit verification
                    // just because the departure board updated the first stop.
                    if (!service.operationalStatusVerified && stop.operationalStatusVerified == null) {
                        stop.copy(operationalStatusVerified = false)
                    } else stop
                }
            } else {
                service.stops
            }
            val updatedStops = applyRouteChange(timingUpdatedStops, routeChange)

            service.copy(
                scheduledDeparture = scheduledDeparture,
                scheduledDepartureDateTime = if (expectedMatch != null) scheduled?.withZoneSameInstant(zone)?.toLocalDateTime() else service.scheduledDepartureDateTime,
                expectedDeparture = expectedDeparture,
                platform = platform,
                scheduledPlatform = scheduledPlatform,
                delayMinutes = delayMinutes,
                officialDelayMinutes = if (expected != null) delayMinutes else service.officialDelayMinutes,
                status = liveStatus ?: service.status,
                stops = updatedStops,
                operationalStatusVerified = verified || service.operationalStatusVerified,
                operationalSourceIds = service.operationalSourceIds + operationalSources.map { it.sourceId.value },
                operationalObservedAtEpochMillis = (selectedSources.map { (it.observedAt ?: it.fetchedAt).toEpochMilli() } +
                    listOfNotNull(service.operationalObservedAtEpochMillis)).maxOrNull(),
                routeChange = routeChange,
                routeChangeSourceIds = if (routeChangeSources.isNotEmpty()) {
                    routeChangeSources.mapTo(linkedSetOf()) { it.sourceId.value }
                } else {
                    service.routeChangeSourceIds
                },
                delayMinutesUnpublished = (lateWithoutMinutes || service.delayMinutesUnpublished) && liveStatus == null && expected == null
            )
        }

        if (usedProviders.isEmpty()) return snapshot

        val providerLabels = boards.filter { board ->
            board.board.snapshot.contributingSources.any { it.sourceId.value in usedProviders }
        }.map { it.providerName }.distinct()

        val noticeParts = mutableListOf<String>()
        snapshot.notice?.takeIf(String::isNotBlank)?.let(noticeParts::add)
        noticeParts += "Realtime riconciliato con ${providerLabels.joinToString(", ")}."
        if (conflict) {
            noticeParts += "Alcune fonti realtime divergono; Binario seleziona ogni campo con la gerarchia della relativa categoria e conserva il conflitto."
        }

        return snapshot.copy(
            services = enrichedServices,
            providerName = "${snapshot.providerName} + ${providerLabels.joinToString(" / ")}",
            sourceObservedAtEpochMillis = newestObservedAt ?: snapshot.sourceObservedAtEpochMillis,
            expiresAtEpochMillis = minOf(snapshot.expiresAtEpochMillis, earliestExpiry ?: snapshot.expiresAtEpochMillis),
            freshness = Freshness.FRESH,
            qualityWarnings = (snapshot.qualityWarnings - SnapshotWarning.STATIC_ONLY).let { warnings ->
                if (conflict) warnings + SnapshotWarning.SOURCE_CONFLICT else warnings
            },
            notice = noticeParts.joinToString(" ")
        )
    }

    private fun bestSourcedMatch(
        matches: List<MatchedEntry>,
        category: RailInformationCategory,
        operatorHint: String?,
        source: (MatchedEntry) -> SourceRef?
    ): MatchedEntry? = matches
        .mapNotNull { match -> source(match)?.let { ref -> match to ref } }
        .maxWithOrNull(
            compareBy<Pair<MatchedEntry, SourceRef>> { (_, ref) ->
                RailSourceSelectionPolicy.score(category, ref.sourceId, operatorHint)
            }.thenBy { (_, ref) -> (ref.observedAt ?: ref.fetchedAt).toEpochMilli() }
        )
        ?.first

    private fun applyRouteChange(
        stops: List<TrainStop>,
        routeChange: it.binario.app.data.core.OperationalRouteChange?
    ): List<TrainStop> {
        if (routeChange == null || !routeChange.structured) return stops
        val skipped = routeChange.skippedStopLabels.map(::normalizeStationLabel).toSet()
        val added = routeChange.addedStopLabels.map(::normalizeStationLabel).toSet()
        return stops.map { stop ->
            val normalized = normalizeStationLabel(stop.station)
            stop.copy(
                routeChange = when {
                    normalized in skipped -> StopRouteChange.SKIPPED
                    normalized in added -> StopRouteChange.ADDED
                    else -> stop.routeChange
                }
            )
        }
    }

    private fun normalizeStationLabel(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace("S. ", "SAN ")
        .replace(Regex("[^A-Z0-9]"), "")

    private fun bestMatch(
        service: TrainService,
        destination: Station,
        board: StationBoard
    ): StationBoardEntry? {
        if (!clock.instant().isBefore(board.snapshot.validUntil)) return null
        val scheduled = service.scheduledDepartureDateTime?.atZone(zone)
        val expectedOperator = operatorId(service.operator)
        return board.entries.asSequence()
            .map { entry ->
                entry.copy(
                    expectedTime = entry.expectedTime?.takeIf { fresh(it.source) },
                    status = entry.status?.takeIf { fresh(it.source) },
                    platform = entry.platform?.takeIf { fresh(it.source) },
                    scheduledPlatform = entry.scheduledPlatform?.takeIf { fresh(it.source) },
                    routeChange = entry.routeChange?.takeIf { fresh(it.source) }
                )
            }
            .filter { entry ->
                normalizeTrainNumber(entry.publicNumber.orEmpty()) == normalizeTrainNumber(service.number)
            }
            .filter { entry ->
                expectedOperator == null || entry.operatorId == null || entry.operatorId.value == expectedOperator
            }
            .map { entry ->
                val delta = scheduled?.let {
                    abs(Duration.between(it, entry.scheduledTime).toMinutes())
                } ?: 0L
                val label = entry.destinationOrOriginLabel ?: entry.destinationOrOrigin.value
                val destinationPenalty = if (destination.matchesPublishedName(label)) 0 else 1
                MatchCandidate(entry, delta, destinationPenalty)
            }
            .filter { candidate -> candidate.scheduleDeltaMinutes <= scheduleTolerance.toMinutes() }
            .minWithOrNull(
                compareBy<MatchCandidate> { it.scheduleDeltaMinutes }
                    .thenBy { it.destinationPenalty }
            )
            ?.entry
    }

    private fun fresh(source: SourceRef): Boolean {
        val now = clock.instant()
        val observed = source.observedAt ?: source.fetchedAt
        return !observed.isBefore(now.minusSeconds(300)) && !observed.isAfter(now.plusSeconds(60)) &&
            source.expiresAt?.isAfter(now) != false
    }

    private fun hasConflict(matches: List<MatchedEntry>): Boolean {
        val statuses = matches.mapNotNull { it.entry.status?.value }.toSet()
        if (statuses.size > 1) return true

        val routeChanges = matches.mapNotNull { it.entry.routeChange?.value }
        val routeFingerprints = routeChanges.map {
            listOf(
                it.kind.name,
                it.skippedStopLabels.sorted().joinToString("|"),
                it.addedStopLabels.sorted().joinToString("|"),
                it.viaStopLabels.sorted().joinToString("|"),
                it.replacementDestinationLabel.orEmpty()
            ).joinToString("::")
        }.toSet()
        if (routeFingerprints.size > 1) return true

        val expected = matches.mapNotNull { it.entry.expectedTime?.value }
        if (expected.size < 2) return false
        val earliest = expected.minOf { it.toInstant() }
        val latest = expected.maxOf { it.toInstant() }
        return Duration.between(earliest, latest) > conflictTolerance
    }

    private fun scheduledDateTime(service: TrainService, query: JourneyQuery): ZonedDateTime? =
        service.scheduledDepartureDateTime?.atZone(zone)
            ?: parseLegacyTime(service.scheduledDeparture, query.requestedAt)?.atZone(zone)

    private fun parseLegacyTime(value: String, requestedAt: LocalDateTime): LocalDateTime? {
        val parts = value.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        var candidate = requestedAt.toLocalDate().atTime(hour, minute)
        if (candidate.isBefore(requestedAt.minusHours(12))) candidate = candidate.plusDays(1)
        return candidate
    }

    private fun displayTime(value: ZonedDateTime): String =
        String.format(Locale.ROOT, "%02d:%02d", value.hour, value.minute)

    private fun normalizeTrainNumber(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]"), "")

    private fun RunStatus.toLegacyStatus(): ServiceStatus? = when (this) {
        RunStatus.ON_TIME -> ServiceStatus.ON_TIME
        RunStatus.DELAYED -> ServiceStatus.DELAYED
        RunStatus.CANCELLED -> ServiceStatus.CANCELLED
        else -> null
    }

    private fun operatorId(operator: RailOperator): String? = when (operator) {
        RailOperator.TRENITALIA_REGIONALE,
        RailOperator.TRENITALIA_LONG_DISTANCE,
        RailOperator.TRENITALIA_AV -> "trenitalia"
        RailOperator.ITALO -> "italo"
        RailOperator.TRENORD -> "trenord"
        RailOperator.SBB -> "sbb"
        RailOperator.TILO -> "tilo"
        RailOperator.BLS -> "bls"
        RailOperator.RHB -> "rhb"
        RailOperator.FART_SSIF -> "fart-ssif"
        RailOperator.SNCF -> "sncf"
        RailOperator.OEBB -> "oebb"
        RailOperator.DB -> "db"
        RailOperator.WESTBAHN -> "westbahn"
        RailOperator.EUROPEAN_SLEEPER -> "european-sleeper"
        RailOperator.OTHER -> null
    }

    private companion object {
        /** As ViaggiaTrenoRealtimeAdapter.loadRoute: the look-back and when it applies. */
        val LATE_LOOKBACK: Duration = Duration.ofHours(2)
        val AROUND_NOW: Duration = Duration.ofMinutes(10)
    }

    private data class ProviderBoard(
        val providerName: String,
        val board: StationBoard
    )

    private data class MatchedEntry(
        val providerName: String,
        val entry: StationBoardEntry
    )

    private data class MatchCandidate(
        val entry: StationBoardEntry,
        val scheduleDeltaMinutes: Long,
        val destinationPenalty: Int
    )
}
