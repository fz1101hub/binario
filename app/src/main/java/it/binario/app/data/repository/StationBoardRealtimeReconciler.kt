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
import kotlinx.coroutines.coroutineScope
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
    private val liveRouteSources: List<TrainDataSource> = emptyList()
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val today = clock.instant().atZone(zone).toLocalDate()
        if (query.serviceDate == today) {
            for (source in liveRouteSources) {
                val live = source.loadRoute(route, direction, query)
                if (live is DataResult.Success) return live
            }
        }
        val planned = plannedSource.loadRoute(route, direction, query)

        if (planned is DataResult.Success) {
            if (query.serviceDate != today || boardProviders.isEmpty()) return planned
            val snapshot = StationBoardRealtimeReconciler(
                providers = boardProviders,
                clock = clock,
                zone = zone
            ).enrich(planned.snapshot, query)
            return DataResult.Success(snapshot)
        }

        if (query.serviceDate == today) {
            boardProviders.forEach { provider ->
                val legacy = provider as? TrainDataSource ?: return@forEach
                when (val live = legacy.loadRoute(route, direction, query)) {
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
                }
            }
        }
        return planned
    }
}

class StationBoardRealtimeReconciler(
    private val providers: List<StationBoardProvider>,
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val zone: ZoneId = RailTime.defaultZone,
    private val scheduleTolerance: Duration = Duration.ofMinutes(20),
    private val conflictTolerance: Duration = Duration.ofMinutes(5)
) {
    suspend fun enrich(snapshot: RouteSnapshot, query: JourneyQuery): RouteSnapshot {
        if (snapshot.services.isEmpty()) return snapshot

        val origin = snapshot.route.origin(snapshot.direction)
        val destination = snapshot.route.destination(snapshot.direction)
        val requested = query.requestedAt.atZone(zone)
        val request = StationBoardRequest(
            stationId = StationId(origin.id),
            stationName = origin.name,
            stationAliases = origin.aliases,
            railwayCode = origin.railwayCode,
            from = requested.minusMinutes(30).toInstant(),
            to = requested.plusHours(8).toInstant(),
            kind = BoardKind.DEPARTURES
        )

        val boards = coroutineScope {
            providers.map { provider ->
                async {
                    when (val result = provider.getStationBoard(request)) {
                        is ProviderResult.Success -> ProviderBoard(provider.descriptor.displayName, result.value)
                        is ProviderResult.Partial -> ProviderBoard(provider.descriptor.displayName, result.value)
                        is ProviderResult.Failure,
                        is ProviderResult.NotModified -> null
                    }
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

            val liveStatus = statusMatch?.entry?.status?.value?.toLegacyStatus()
                ?: if (expected != null) {
                    if (delayMinutes > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME
                } else null

            val platform = platformMatch?.entry?.platform?.value ?: service.platform
            val verified = liveStatus != null || expected != null
            val routeChange = routeChangeMatch?.entry?.routeChange?.value ?: service.routeChange
            val originStop = service.stops.firstOrNull()
            val timingUpdatedStops = if (originStop != null) {
                listOf(
                    originStop.copy(
                        scheduled = scheduledDeparture,
                        expected = expectedDeparture,
                        platform = platform,
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
                }
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
        "%02d:%02d".format(value.hour, value.minute)

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
