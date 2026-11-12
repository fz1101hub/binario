package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.provider.KnownProviderIds
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy
import it.binario.app.data.urban.StopScopedBoardProvider
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
 *
 * Live first (owner decision): the station's live boards decide which trains
 * the board lists. A board of [scheduledFallbackSources] (the RFI programmed
 * board) is asked at the same time but is only the fallback: a train it alone
 * lists is shown when no station live board answered with rows, or, with
 * [programmedRowsAfterLiveSpan], after the last row of the live boards. Its
 * rows never appear while a station live board may still answer.
 */
class StationBoardAggregateService(
    private val providers: List<StationBoardProvider>,
    private val clock: Clock = Clock.systemUTC(),
    private val window: Duration = Duration.ofHours(6),
    private val identityTolerance: Duration = Duration.ofMinutes(5),
    private val conflictTolerance: Duration = Duration.ofMinutes(5),
    private val providerTimeoutMillis: Long = 12_000L,
    private val providerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Boards of city stops (metro, tram: `gtfs:<city pack>:<stop>`). For a stop one of them serves, they are asked
     * instead of [providers]; every other station gets exactly the [providers] board as before.
     */
    private val stopProviders: List<StopScopedBoardProvider> = emptyList(),
    /** A city board reads its pack's timetable on first use, which can take longer than a network board. */
    private val stopProviderTimeoutMillis: Long = 30_000L,
    /** How far back a late train may be scheduled and still be shown while its expected time is ahead. */
    private val lateLookback: Duration = Duration.ofHours(2),
    /** Boards that publish the timetable only: asked in parallel with the live boards, shown only as the fallback. */
    private val scheduledFallbackSources: Set<SourceId> = setOf(KnownProviderIds.RFI_PROGRAMMED),
    /**
     * Live boards that list one operator's trains only (Italo in Viaggio). Their rows are always shown, but they do not
     * stand for the station's live board: they neither end the fallback nor set the end of the live span.
     */
    private val operatorOnlySources: Set<SourceId> = setOf(KnownProviderIds.ITALO_STATUS),
    /** See [PROGRAMMED_ROWS_AFTER_LIVE_SPAN]. */
    private val programmedRowsAfterLiveSpan: Boolean = PROGRAMMED_ROWS_AFTER_LIVE_SPAN
) {
    val hasProviders: Boolean get() = providers.isNotEmpty()

    suspend fun load(
        station: Station,
        kind: BoardKind = BoardKind.DEPARTURES,
        from: Instant = clock.instant()
    ): StationBoardAggregateResult = observe(station, kind, from).last()

    /**
     * Publish live rows as soon as a board answers; optional/slow sources must not hold the board blank. Programmed
     * rows wait until no station live board can still answer (answered, failed or timed out), with no extra request.
     * A partial board without rows is not published: the final result always is.
     */
    fun observe(
        station: Station,
        kind: BoardKind = BoardKind.DEPARTURES,
        from: Instant = clock.instant()
    ): Flow<StationBoardAggregateResult> = flow {
        val scoped = stopProviders.filter { it.servesStation(StationId(station.id)) }
        val active: List<StationBoardProvider> = scoped.ifEmpty { providers }
        val timeoutMillis = if (scoped.isNotEmpty()) stopProviderTimeoutMillis else providerTimeoutMillis
        if (active.isEmpty()) {
            emit(StationBoardAggregateResult.Unavailable("Nessuna fonte tabellone autorizzata è attiva in questa build."))
            return@flow
        }
        val request = StationBoardRequest(
            stationId = StationId(station.id), stationName = station.name,
            stationAliases = station.aliases, railwayCode = station.railwayCode,
            from = from.minus(Duration.ofMinutes(10)), to = from.plus(window), kind = kind,
            trenitaliaLocationId = station.trenitaliaLocationId, coordinates = station.catalogueCoordinates(),
            // Delayed trains stay on the board until their expected time, as on the station's own monitor.
            lateSince = from.minus(lateLookback)
        )
        coroutineScope {
            val roles = active.map(::roleOf)
            val completed = Channel<Pair<Int, ProviderAttempt>>(active.size)
            active.forEachIndexed { index, provider ->
                launch(providerDispatcher) { completed.send(index to query(provider, roles[index], request, timeoutMillis)) }
            }
            val attempts = arrayOfNulls<ProviderAttempt>(active.size)
            repeat(active.size) { completedCount ->
                val (index, attempt) = completed.receive()
                attempts[index] = attempt
                val stationLiveMayAnswer = attempts.indices.any { attempts[it] == null && roles[it] == BoardRole.STATION_LIVE }
                val result = aggregate(station, request, attempts.filterNotNull(), stationLiveMayAnswer)
                val complete = completedCount == active.lastIndex
                if (result is StationBoardAggregateResult.Success) {
                    if (complete || result.board.entries.isNotEmpty()) emit(result.copy(isComplete = complete))
                } else if (complete) emit(result)
            }
        }
    }.flowOn(providerDispatcher)

    private fun roleOf(provider: StationBoardProvider): BoardRole = when (provider.descriptor.id) {
        in scheduledFallbackSources -> BoardRole.PROGRAMMED
        in operatorOnlySources -> BoardRole.OPERATOR_LIVE
        else -> BoardRole.STATION_LIVE
    }

    private suspend fun query(
        provider: StationBoardProvider,
        role: BoardRole,
        request: StationBoardRequest,
        timeoutMillis: Long
    ): ProviderAttempt {
        val name = provider.descriptor.displayName
        return try {
            withTimeout(timeoutMillis) {
                when (val result = provider.getStationBoard(request)) {
                    is ProviderResult.Success -> ProviderAttempt.Board(name, result.value, emptySet(), role)
                    is ProviderResult.Partial -> ProviderAttempt.Board(name, result.value, result.warnings, role)
                    is ProviderResult.Failure -> ProviderAttempt.Failure(name, result.error.safeMessage,
                        unsupported = result.error.kind == it.binario.app.data.provider.ProviderErrorKind.NOT_SUPPORTED, role = role)
                    is ProviderResult.NotModified -> ProviderAttempt.Failure(name, "La fonte non ha restituito un nuovo tabellone.", role = role)
                }
            }
        } catch (_: TimeoutCancellationException) {
            ProviderAttempt.Failure(name, "La fonte non ha risposto in tempo.", role = role)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            ProviderAttempt.Failure(name, "La fonte non è momentaneamente disponibile.", role = role)
        }
    }

    /** [stationLiveMayAnswer]: a station live board has not answered yet, so no programmed-only row may be shown. */
    private fun aggregate(
        station: Station,
        request: StationBoardRequest,
        attempts: List<ProviderAttempt>,
        stationLiveMayAnswer: Boolean
    ): StationBoardAggregateResult {
        val returnedBoards = attempts.filterIsInstance<ProviderAttempt.Board>()
        val invalidBoards = returnedBoards.filter { attempt ->
            attempt.board.stationId != request.stationId || attempt.board.kind != request.kind
        }
        val boards = returnedBoards - invalidBoards.toSet()
        if (boards.isEmpty()) {
            val failures = attempts.filterIsInstance<ProviderAttempt.Failure>()
            val details = failures
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
                },
                allUnsupported = invalidBoards.isEmpty() && failures.isNotEmpty() && failures.all { it.unsupported }
            )
        }

        val candidates = boards.flatMap { providerBoard ->
            providerBoard.board.entries.map { entry ->
                EntryCandidate(providerBoard.providerName, providerBoard.board, entry, providerBoard)
            }
        }

        // Live first: rows of the same run still merge field by field, but a train only the programmed board lists is
        // shown when no station live board has rows (the fallback) or after the last of their rows.
        val liveSpanEnd = candidates.filter { it.origin.role == BoardRole.STATION_LIVE }
            .maxOfOrNull { it.entry.scheduledTime.toInstant() }
        val clusters = cluster(candidates).filter { group ->
            when {
                group.any { it.origin.role != BoardRole.PROGRAMMED } -> true
                stationLiveMayAnswer -> false
                liveSpanEnd == null -> true
                else -> programmedRowsAfterLiveSpan && group.all { it.entry.scheduledTime.toInstant().isAfter(liveSpanEnd) }
            }
        }
        val shownCandidates = clusters.flatten()
        val identityAmbiguous = hasAmbiguousIdentity(shownCandidates)
        var sourceConflict = false
        val entries = clusters.map { group ->
            if (hasConflict(group)) sourceConflict = true
            merge(group)
        }
            // A late row stays only while the expected time chosen across the sources is still ahead.
            .filter { request.keeps(it.scheduledTime.toInstant(), it.expectedTime?.value?.toInstant()) }
            .sortedBy { it.expectedTime?.value?.toInstant() ?: it.scheduledTime.toInstant() }

        // The programmed board is named only when its rows are on the board, after the live boards.
        val usedProgrammed = shownCandidates.filter { it.origin.role == BoardRole.PROGRAMMED }.map { it.origin }
        val shownBoards = boards.filter { it.role != BoardRole.PROGRAMMED } +
            boards.filter { board -> board.role == BoardRole.PROGRAMMED && usedProgrammed.any { it === board } }
        val programmedFallback = liveSpanEnd == null && usedProgrammed.isNotEmpty()
        // Nothing on the board is live (an operator-only board such as Italo's may still add live rows to a fallback).
        val programmedOnly = programmedFallback && shownCandidates.all { it.origin.role == BoardRole.PROGRAMMED }

        val warnings = buildSet {
            shownBoards.flatMapTo(this) { it.warnings }
            if (attempts.filterIsInstance<ProviderAttempt.Failure>().any { !it.unsupported && it.role != BoardRole.PROGRAMMED }) {
                add(ProviderWarning.REALTIME_UNAVAILABLE)
            }
            // The station's live boards gave no rows: the board is the programmed one and says so.
            if (programmedFallback) add(ProviderWarning.REALTIME_UNAVAILABLE)
            if (invalidBoards.isNotEmpty() || identityAmbiguous) add(ProviderWarning.IDENTITY_AMBIGUOUS)
            if (sourceConflict) add(ProviderWarning.SOURCE_CONFLICT)
            if (entries.any { it.routeChange?.value?.kind == RouteChangeKind.REPLACEMENT_SERVICE }) {
                add(ProviderWarning.REPLACEMENT_SERVICE)
            }
            // «Sono disponibili solo orari programmati», not «Realtime parzialmente non disponibile».
            if (programmedOnly) {
                remove(ProviderWarning.REALTIME_UNAVAILABLE)
                add(ProviderWarning.PROGRAMMED_ONLY)
            }
        }

        return StationBoardAggregateResult.Success(
            StationBoardAggregate(
                station = station,
                kind = request.kind,
                entries = entries,
                providerNames = shownBoards.map { it.providerName }.distinct(),
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
        // The timetable platform of the entry that gave [platform]: two sources never make a change together.
        val scheduledPlatform = platform?.let { chosen -> group.firstOrNull { it.entry.platform === chosen }?.entry?.scheduledPlatform }
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
            scheduledPlatform = scheduledPlatform,
            status = status,
            routeChange = routeChange,
            nativeRunIds = group.flatMap { it.entry.nativeRunIds.entries }.associate { it.key to it.value },
            lineLabel = base.entry.lineLabel ?: group.firstNotNullOfOrNull { it.entry.lineLabel },
            serviceKind = base.entry.serviceKind.takeIf { kind -> kind != ServiceKind.UNKNOWN }
                ?: group.firstOrNull { candidate -> candidate.entry.serviceKind != ServiceKind.UNKNOWN }?.entry?.serviceKind
                ?: ServiceKind.UNKNOWN
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

    companion object {
        /**
         * Owner default, to confirm: past the last row of the station's live boards (the RFI monitor lists about the
         * next two hours, the programmed board six) the programmed rows follow, marked as programmed, so the board
         * does not shrink to two hours. False ends the board with the live boards' span.
         */
        const val PROGRAMMED_ROWS_AFTER_LIVE_SPAN: Boolean = true
    }
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
    scheduledPlatform = scheduledPlatform?.takeIf { it.source.expiresAt?.isAfter(now) != false },
    status = status?.takeIf { it.source.expiresAt?.isAfter(now) != false },
    routeChange = routeChange?.takeIf { it.source.expiresAt?.isAfter(now) != false }
)

sealed interface StationBoardAggregateResult {
    data class Success(val board: StationBoardAggregate, val isComplete: Boolean = true) : StationBoardAggregateResult
    /** [allUnsupported]: every source answered that it does not cover the station, so asking again cannot help. */
    data class Unavailable(val message: String, val allUnsupported: Boolean = false) : StationBoardAggregateResult
}

/** What a board stands for on the station board: see [StationBoardAggregateService]. */
private enum class BoardRole {
    /** A live board of every train at the station (RFI monitor, ViaggiaTreno, Swiss board): it decides the board. */
    STATION_LIVE,
    /** A live board of one operator's trains: shown, but it does not stand for the station's live board. */
    OPERATOR_LIVE,
    /** The timetable only (RFI programmed board): the fallback. */
    PROGRAMMED
}

private sealed interface ProviderAttempt {
    data class Board(
        val providerName: String,
        val board: StationBoard,
        val warnings: Set<ProviderWarning>,
        val role: BoardRole = BoardRole.STATION_LIVE
    ) : ProviderAttempt

    data class Failure(
        val providerName: String,
        val message: String,
        val unsupported: Boolean = false,
        val role: BoardRole = BoardRole.STATION_LIVE
    ) : ProviderAttempt
}

private data class EntryCandidate(
    val providerName: String,
    val board: StationBoard,
    val entry: StationBoardEntry,
    val origin: ProviderAttempt.Board
)
