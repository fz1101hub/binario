package it.binario.app.data.repository

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
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
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
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
 * Keeps journey planning static/deterministic and applies realtime only after a
 * planned service can be matched by public number, destination and schedule.
 */
class StationBoardEnrichedTrainDataSource(
    private val plannedSource: TrainDataSource,
    private val boardProviders: List<StationBoardProvider>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val planned = plannedSource.loadRoute(route, direction, query)
        val today = clock.instant().atZone(clock.zone).toLocalDate()

        if (planned is DataResult.Success) {
            if (query.serviceDate != today || boardProviders.isEmpty()) return planned
            val snapshot = StationBoardRealtimeReconciler(
                providers = boardProviders,
                clock = clock,
                zone = zone
            ).enrich(planned.snapshot, query)
            return DataResult.Success(snapshot)
        }

        // Personal-use transitional fallback: if no static timetable covers the
        // route, reuse only board adapters that still expose the legacy route
        // bridge. Those adapters require an exact direct destination match.
        if (query.serviceDate == today) {
            boardProviders.forEach { provider ->
                val legacy = provider as? TrainDataSource ?: return@forEach
                when (val live = legacy.loadRoute(route, direction, query)) {
                    is DataResult.Success -> {
                        return DataResult.Success(
                            live.snapshot.copy(
                                notice = listOfNotNull(
                                    "Fallback realtime diretto: nessun GTFS embedded copre questa tratta.",
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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.of("Europe/Rome"),
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

            val expectedMatch = matches.firstOrNull { it.entry.expectedTime != null }
            val statusMatch = matches.firstOrNull { it.entry.status != null }
            val platformMatch = matches.firstOrNull { it.entry.platform != null }
            val routeChangeMatch = matches.firstOrNull { it.entry.routeChange != null }
            val selectedSources = listOfNotNull(
                expectedMatch?.entry?.expectedTime?.source,
                statusMatch?.entry?.status?.source,
                platformMatch?.entry?.platform?.source,
                routeChangeMatch?.entry?.routeChange?.source
            ).distinctBy { it.sourceId }

            selectedSources.forEach { source ->
                usedProviders += source.sourceId.value
                val observed = (source.observedAt ?: source.fetchedAt).toEpochMilli()
                newestObservedAt = maxOf(newestObservedAt ?: observed, observed)
                source.expiresAt?.toEpochMilli()?.let { expiry ->
                    earliestExpiry = minOf(earliestExpiry ?: expiry, expiry)
                }
            }

            val scheduled = scheduledDateTime(service, query)
            val expected = expectedMatch?.entry?.expectedTime?.value
            val expectedDeparture = expected?.let(::displayTime) ?: service.expectedDeparture
            val delayMinutes = if (expected != null && scheduled != null) {
                Duration.between(scheduled, expected).toMinutes().toInt()
            } else {
                service.delayMinutes
            }.coerceAtLeast(0)

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
                        expected = expectedDeparture,
                        platform = platform,
                        delayMinutes = delayMinutes
                    )
                ) + service.stops.drop(1)
            } else {
                service.stops
            }
            val updatedStops = applyRouteChange(timingUpdatedStops, routeChange)

            service.copy(
                expectedDeparture = expectedDeparture,
                platform = platform,
                delayMinutes = delayMinutes,
                officialDelayMinutes = if (verified) delayMinutes else service.officialDelayMinutes,
                status = liveStatus ?: service.status,
                stops = updatedStops,
                operationalStatusVerified = verified,
                operationalSourceIds = selectedSources
                    .filter { it !== routeChangeMatch?.entry?.routeChange?.source }
                    .mapTo(linkedSetOf()) { it.sourceId.value },
                operationalObservedAtEpochMillis = selectedSources.maxOfOrNull { (it.observedAt ?: it.fetchedAt).toEpochMilli() },
                routeChange = routeChange,
                routeChangeSourceIds = routeChangeMatch?.entry?.routeChange?.source
                    ?.let { setOf(it.sourceId.value) }
                    ?: service.routeChangeSourceIds
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
            noticeParts += "Alcune fonti realtime divergono; Binario usa la priorità configurata e segnala il conflitto."
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
        val scheduled = service.scheduledDepartureDateTime?.atZone(zone)
        val expectedOperator = operatorId(service.operator)
        return board.entries.asSequence()
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

    private fun hasConflict(matches: List<MatchedEntry>): Boolean {
        val statuses = matches.mapNotNull { it.entry.status?.value }.toSet()
        if (statuses.size > 1) return true

        val routeChanges = matches.mapNotNull { it.entry.routeChange?.value }
        if (routeChanges.map { it.kind to it.summary }.toSet().size > 1) return true

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
