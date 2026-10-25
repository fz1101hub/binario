package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope

/**
 * Canonical station-board fan-out.
 *
 * Providers are queried in parallel, then entries referring to the same public
 * train are clustered. Every operational field is selected with the same
 * category-aware source policy used by route reconciliation; provider list
 * order is never authoritative.
 */
class StationBoardAggregateService(
    private val providers: List<StationBoardProvider>,
    private val clock: Clock = Clock.systemUTC(),
    private val window: Duration = Duration.ofHours(6),
    private val identityTolerance: Duration = Duration.ofMinutes(5),
    private val conflictTolerance: Duration = Duration.ofMinutes(5),
    private val providerTimeoutMillis: Long = 12_000L,
    private val providerDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val hasProviders: Boolean get() = providers.isNotEmpty()

    suspend fun load(
        station: Station,
        kind: BoardKind = BoardKind.DEPARTURES,
        from: Instant = clock.instant()
    ): StationBoardAggregateResult = observe(station, kind, from).last()

    /** Publish useful rows immediately; optional/slow sources must not hold the board blank. */
    fun observe(
        station: Station,
        kind: BoardKind = BoardKind.DEPARTURES,
        from: Instant = clock.instant()
    ): Flow<StationBoardAggregateResult> = flow {
        if (providers.isEmpty()) {
            emit(StationBoardAggregateResult.Unavailable("Nessuna fonte tabellone autorizzata è attiva in questa build."))
            return@flow
        }
        val request = StationBoardRequest(
            stationId = StationId(station.id), stationName = station.name,
            stationAliases = station.aliases, railwayCode = station.railwayCode,
            from = from.minus(Duration.ofMinutes(10)), to = from.plus(window), kind = kind,
            trenitaliaLocationId = station.trenitaliaLocationId, coordinates = station.catalogueCoordinates()
        )
        coroutineScope {
            val completed = Channel<Pair<Int, ProviderAttempt>>(providers.size)
            providers.forEachIndexed { index, provider ->
                launch(providerDispatcher) { completed.send(index to query(provider, request)) }
            }
            val attempts = arrayOfNulls<ProviderAttempt>(providers.size)
            repeat(providers.size) { completedCount ->
                val (index, attempt) = completed.receive()
                attempts[index] = attempt
                val result = aggregate(station, request, attempts.filterNotNull())
                val complete = completedCount == providers.lastIndex
                if (result is StationBoardAggregateResult.Success) {
                    emit(result.copy(isComplete = complete))
                } else if (complete) emit(result)
            }
        }
    }.flowOn(providerDispatcher)

    private suspend fun query(provider: StationBoardProvider, request: StationBoardRequest): ProviderAttempt {
        return try {
            withTimeout(providerTimeoutMillis) {
                when (val result = provider.getStationBoard(request)) {
                    is ProviderResult.Success -> ProviderAttempt.Board(provider.descriptor.displayName, result.value, emptySet())
                    is ProviderResult.Partial -> ProviderAttempt.Board(provider.descriptor.displayName, result.value, result.warnings)
                    is ProviderResult.Failure -> ProviderAttempt.Failure(provider.descriptor.displayName, result.error.safeMessage,
                        unsupported = result.error.kind == it.binario.app.data.provider.ProviderErrorKind.NOT_SUPPORTED)
                    is ProviderResult.NotModified -> ProviderAttempt.Failure(provider.descriptor.displayName, "La fonte non ha restituito un nuovo tabellone.")
                }
            }
        } catch (_: TimeoutCancellationException) {
            ProviderAttempt.Failure(provider.descriptor.displayName, "La fonte non ha risposto in tempo.")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            ProviderAttempt.Failure(provider.descriptor.displayName, "La fonte non è momentaneamente disponibile.")
        }
    }

    private fun aggregate(
        station: Station,
        request: StationBoardRequest,
        attempts: List<ProviderAttempt>
    ): StationBoardAggregateResult {
        val returnedBoards = attempts.filterIsInstance<ProviderAttempt.Board>()
        val invalidBoards = returnedBoards.filter { attempt ->
            attempt.board.stationId != request.stationId || attempt.board.kind != request.kind
        }
        val boards = returnedBoards - invalidBoards.toSet()
        if (boards.isEmpty()) {
            val details = attempts.filterIsInstance<ProviderAttempt.Failure>()
                .map { attempt ->
                    listOf(attempt.providerName, attempt.message.trim())
                        .filter { it.isNotBlank() }
                        .joinToString(": ")
                }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
                .joinToString(" ")
            return StationBoardAggregateResult.Unavailable(
                if (invalidBoards.isNotEmpty()) {
                    "Le fonti tabellone hanno restituito dati con identità stazione o tipo di tabellone incoerenti; il risultato è stato scartato."
                } else {
                    details.ifBlank { "Le fonti tabellone non hanno restituito dati utilizzabili." }
                }
            )
        }

        val candidates = boards.flatMap { providerBoard ->
            providerBoard.board.entries.map { entry ->
                EntryCandidate(providerBoard.providerName, providerBoard.board, entry)
            }
        }

        val clusters = cluster(candidates)
        val identityAmbiguous = hasAmbiguousIdentity(candidates)
        var sourceConflict = false
        val entries = clusters.map { group ->
            if (hasConflict(group)) sourceConflict = true
            merge(group)
        }.sortedBy { it.expectedTime?.value?.toInstant() ?: it.scheduledTime.toInstant() }

        val warnings = buildSet {
            boards.flatMapTo(this) { it.warnings }
            if (attempts.filterIsInstance<ProviderAttempt.Failure>().any { !it.unsupported }) {
                add(ProviderWarning.REALTIME_UNAVAILABLE)
            }
            if (invalidBoards.isNotEmpty() || identityAmbiguous) add(ProviderWarning.IDENTITY_AMBIGUOUS)
            if (sourceConflict) add(ProviderWarning.SOURCE_CONFLICT)
            if (entries.any { it.routeChange?.value?.kind == RouteChangeKind.REPLACEMENT_SERVICE }) {
                add(ProviderWarning.REPLACEMENT_SERVICE)
            }
        }

        return StationBoardAggregateResult.Success(
            StationBoardAggregate(
                station = station,
                kind = request.kind,
                entries = entries,
                providerNames = boards.map { it.providerName }.distinct(),
                warnings = warnings,
                fetchedAt = clock.instant()
            )
        )
    }

    private fun cluster(candidates: List<EntryCandidate>): List<List<EntryCandidate>> {
        val groups = mutableListOf<MutableList<EntryCandidate>>()
        candidates.sortedBy { it.entry.scheduledTime.toInstant() }.forEach { candidate ->
            val target = groups.firstOrNull { existing ->
                samePublicRun(existing.first(), candidate)
            }
            if (target != null) target += candidate else groups += mutableListOf(candidate)
        }
        return groups
    }

    private fun samePublicRun(first: EntryCandidate, second: EntryCandidate): Boolean {
        val firstNumber = normalize(first.entry.publicNumber.orEmpty())
        val secondNumber = normalize(second.entry.publicNumber.orEmpty())
        val timeDelta = Duration.between(
            first.entry.scheduledTime.toInstant(),
            second.entry.scheduledTime.toInstant()
        ).abs()

        if (firstNumber.isNotBlank() && secondNumber.isNotBlank()) {
            if (!sameNumberTimeAndEndpoint(first, second, firstNumber, secondNumber, timeDelta)) return false
            if (knownOperatorsDiffer(first, second)) return false
            if (sameSourceReportsDifferentRun(first, second)) return false
            return true
        }

        return first.entry.runId == second.entry.runId &&
            timeDelta <= identityTolerance &&
            normalize(endpoint(first.entry)) == normalize(endpoint(second.entry))
    }

    private fun sameNumberTimeAndEndpoint(
        first: EntryCandidate,
        second: EntryCandidate,
        firstNumber: String = normalize(first.entry.publicNumber.orEmpty()),
        secondNumber: String = normalize(second.entry.publicNumber.orEmpty()),
        timeDelta: Duration = Duration.between(
            first.entry.scheduledTime.toInstant(),
            second.entry.scheduledTime.toInstant()
        ).abs()
    ): Boolean = firstNumber.isNotBlank() &&
        secondNumber.isNotBlank() &&
        firstNumber == secondNumber &&
        timeDelta <= identityTolerance &&
        normalize(endpoint(first.entry)) == normalize(endpoint(second.entry))

    private fun knownOperatorsDiffer(first: EntryCandidate, second: EntryCandidate): Boolean {
        val firstOperator = normalize(first.entry.operatorId?.value.orEmpty())
        val secondOperator = normalize(second.entry.operatorId?.value.orEmpty())
        return firstOperator.isNotBlank() && secondOperator.isNotBlank() && firstOperator != secondOperator
    }

    private fun sameSourceReportsDifferentRun(first: EntryCandidate, second: EntryCandidate): Boolean {
        val firstSources = first.board.snapshot.contributingSources.mapTo(mutableSetOf()) { it.sourceId }
        val secondSources = second.board.snapshot.contributingSources.mapTo(mutableSetOf()) { it.sourceId }
        return first.entry.runId != second.entry.runId && firstSources.any(secondSources::contains)
    }

    private fun hasAmbiguousIdentity(candidates: List<EntryCandidate>): Boolean =
        candidates.indices.any { firstIndex ->
            ((firstIndex + 1) until candidates.size).any { secondIndex ->
                val first = candidates[firstIndex]
                val second = candidates[secondIndex]
                sameNumberTimeAndEndpoint(first, second) &&
                    (knownOperatorsDiffer(first, second) || sameSourceReportsDifferentRun(first, second))
            }
        }

    private fun merge(group: List<EntryCandidate>): StationBoardEntry {
        val operatorHint = group.mapNotNull { it.entry.operatorId?.value }.firstOrNull()

        val base = group.maxWithOrNull(
            compareBy<EntryCandidate> { candidate ->
                candidate.bestSource(RailInformationCategory.STATION_BOARD, operatorHint)
                    ?.let { RailSourceSelectionPolicy.score(RailInformationCategory.STATION_BOARD, it.sourceId, operatorHint) }
                    ?: 0
            }.thenBy { candidate ->
                candidate.bestSource(RailInformationCategory.STATION_BOARD, operatorHint)
                    ?.let(::sourceTimestamp) ?: 0L
            }
        ) ?: group.first()

        val expected = bestSourced(group, RailInformationCategory.TRAIN_REALTIME, operatorHint) {
            it.entry.expectedTime
        }
        val platform = bestSourced(group, RailInformationCategory.STATION_BOARD, operatorHint) {
            it.entry.platform
        }
        val status = bestSourced(group, RailInformationCategory.TRAIN_REALTIME, operatorHint) {
            it.entry.status
        }
        val routeChange = bestSourced(group, RailInformationCategory.ROUTE_VARIATIONS, operatorHint) {
            it.entry.routeChange
        }

        val labelCandidate = group
            .filter { !it.entry.destinationOrOriginLabel.isNullOrBlank() }
            .maxWithOrNull(compareBy<EntryCandidate> { candidate ->
                candidate.bestSource(RailInformationCategory.STATION_BOARD, operatorHint)
                    ?.let { RailSourceSelectionPolicy.score(RailInformationCategory.STATION_BOARD, it.sourceId, operatorHint) }
                    ?: 0
            }.thenBy { candidate ->
                candidate.bestSource(RailInformationCategory.STATION_BOARD, operatorHint)
                    ?.let(::sourceTimestamp) ?: 0L
            })

        return base.entry.copy(
            destinationOrOrigin = labelCandidate?.entry?.destinationOrOrigin ?: base.entry.destinationOrOrigin,
            destinationOrOriginLabel = labelCandidate?.entry?.destinationOrOriginLabel
                ?: base.entry.destinationOrOriginLabel,
            publicNumber = base.entry.publicNumber
                ?: group.firstNotNullOfOrNull { it.entry.publicNumber },
            operatorId = base.entry.operatorId
                ?: group.firstNotNullOfOrNull { it.entry.operatorId },
            expectedTime = expected,
            platform = platform,
            status = status,
            routeChange = routeChange,
            nativeRunIds = group.flatMap { it.entry.nativeRunIds.entries }.associate { it.key to it.value },
            lineLabel = base.entry.lineLabel ?: group.firstNotNullOfOrNull { it.entry.lineLabel }
        )
    }

    private fun <T> bestSourced(
        group: List<EntryCandidate>,
        category: RailInformationCategory,
        operatorHint: String?,
        value: (EntryCandidate) -> SourcedValue<T>?
    ): SourcedValue<T>? = group
        .mapNotNull { candidate -> value(candidate)?.let { candidate to it } }
        .maxWithOrNull(
            compareBy<Pair<EntryCandidate, SourcedValue<T>>> { (_, sourced) ->
                RailSourceSelectionPolicy.score(category, sourced.source.sourceId, operatorHint)
            }.thenBy { (_, sourced) -> sourceTimestamp(sourced.source) }
        )
        ?.second

    private fun hasConflict(group: List<EntryCandidate>): Boolean {
        val expected = group.mapNotNull { it.entry.expectedTime?.value?.toInstant() }
        if (expected.size > 1) {
            val earliest = expected.minOrNull()
            val latest = expected.maxOrNull()
            if (earliest != null && latest != null && Duration.between(earliest, latest) > conflictTolerance) {
                return true
            }
        }

        val platforms = group.mapNotNull { it.entry.platform?.value?.trim()?.takeIf(String::isNotBlank) }
            .map(::normalize)
            .distinct()
        if (platforms.size > 1) return true

        val statuses = group.mapNotNull { it.entry.status?.value }.distinct()
        if (statuses.size > 1) return true

        val routeChanges = group.mapNotNull { it.entry.routeChange?.value?.summary?.trim() }
            .map(::normalize)
            .distinct()
        return routeChanges.size > 1
    }

    private fun EntryCandidate.bestSource(
        category: RailInformationCategory,
        operatorHint: String?
    ): SourceRef? {
        val fieldSources = listOfNotNull(
            entry.expectedTime?.source,
            entry.platform?.source,
            entry.status?.source,
            entry.routeChange?.source
        )
        val candidates = (fieldSources + board.snapshot.contributingSources)
            .distinctBy { it.sourceId }
        return candidates.maxWithOrNull(
            compareBy<SourceRef> {
                RailSourceSelectionPolicy.score(category, it.sourceId, operatorHint)
            }.thenBy(::sourceTimestamp)
        )
    }

    private fun endpoint(entry: StationBoardEntry): String =
        entry.destinationOrOriginLabel?.takeIf(String::isNotBlank)
            ?: entry.destinationOrOrigin.value

    private fun sourceTimestamp(source: SourceRef): Long =
        (source.observedAt ?: source.fetchedAt).toEpochMilli()

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("ö", "oe")
        .replace("ä", "ae")
        .replace("ü", "ue")
        .replace(Regex("[^a-z0-9]"), "")
}

/** Catalogue coordinates as a validated point; a station without both values, or with invalid ones, has none. */
internal fun Station.catalogueCoordinates(): it.binario.app.data.core.GeoPoint? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) it.binario.app.data.core.GeoPoint(lat, lon) else null
}

data class StationBoardAggregate(
    val station: Station,
    val kind: BoardKind,
    val entries: List<StationBoardEntry>,
    val providerNames: List<String>,
    val warnings: Set<ProviderWarning>,
    val fetchedAt: Instant
) {
    fun withoutExpiredObservations(now: Instant): StationBoardAggregate = copy(entries = entries.map { it.withoutExpiredObservations(now) })
}

fun StationBoardEntry.withoutExpiredObservations(now: Instant): StationBoardEntry = copy(
    expectedTime = expectedTime?.takeIf { it.source.expiresAt?.isAfter(now) != false },
    platform = platform?.takeIf { it.source.expiresAt?.isAfter(now) != false },
    status = status?.takeIf { it.source.expiresAt?.isAfter(now) != false },
    routeChange = routeChange?.takeIf { it.source.expiresAt?.isAfter(now) != false }
)

sealed interface StationBoardAggregateResult {
    data class Success(val board: StationBoardAggregate, val isComplete: Boolean = true) : StationBoardAggregateResult
    data class Unavailable(val message: String) : StationBoardAggregateResult
}

private sealed interface ProviderAttempt {
    data class Board(
        val providerName: String,
        val board: StationBoard,
        val warnings: Set<ProviderWarning>
    ) : ProviderAttempt

    data class Failure(
        val providerName: String,
        val message: String,
        val unsupported: Boolean = false
    ) : ProviderAttempt
}

private data class EntryCandidate(
    val providerName: String,
    val board: StationBoard,
    val entry: StationBoardEntry
)
