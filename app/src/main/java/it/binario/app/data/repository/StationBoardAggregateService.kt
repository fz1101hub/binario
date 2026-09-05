package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.SourceId
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val zone: ZoneId = ZoneId.of("Europe/Rome"),
    private val window: Duration = Duration.ofHours(6),
    private val identityTolerance: Duration = Duration.ofMinutes(5),
    private val conflictTolerance: Duration = Duration.ofMinutes(5)
) {
    val hasProviders: Boolean get() = providers.isNotEmpty()

    suspend fun load(
        station: Station,
        kind: BoardKind = BoardKind.DEPARTURES,
        from: Instant = clock.instant()
    ): StationBoardAggregateResult {
        if (providers.isEmpty()) {
            return StationBoardAggregateResult.Unavailable(
                "Nessuna fonte tabellone autorizzata è attiva in questa build."
            )
        }

        val request = StationBoardRequest(
            stationId = StationId(station.id),
            stationName = station.name,
            from = from.minus(Duration.ofMinutes(10)),
            to = from.plus(window),
            kind = kind
        )

        val attempts = coroutineScope {
            providers.map { provider ->
                async {
                    when (val result = provider.getStationBoard(request)) {
                        is ProviderResult.Success -> ProviderAttempt.Board(
                            providerName = provider.descriptor.displayName,
                            board = result.value,
                            warnings = emptySet()
                        )
                        is ProviderResult.Partial -> ProviderAttempt.Board(
                            providerName = provider.descriptor.displayName,
                            board = result.value,
                            warnings = result.warnings
                        )
                        is ProviderResult.Failure -> ProviderAttempt.Failure(
                            providerName = provider.descriptor.displayName,
                            message = result.error.safeMessage
                        )
                        is ProviderResult.NotModified -> ProviderAttempt.Failure(
                            providerName = provider.descriptor.displayName,
                            message = "La fonte non ha restituito un nuovo tabellone."
                        )
                    }
                }
            }.awaitAll()
        }

        val boards = attempts.filterIsInstance<ProviderAttempt.Board>()
        if (boards.isEmpty()) {
            val details = attempts.filterIsInstance<ProviderAttempt.Failure>()
                .map { it.message.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
                .joinToString(" ")
            return StationBoardAggregateResult.Unavailable(
                details.ifBlank { "Le fonti tabellone non hanno restituito dati utilizzabili." }
            )
        }

        val candidates = boards.flatMap { providerBoard ->
            providerBoard.board.entries.map { entry ->
                EntryCandidate(providerBoard.providerName, providerBoard.board, entry)
            }
        }

        val clusters = cluster(candidates)
        var sourceConflict = false
        val entries = clusters.map { group ->
            if (hasConflict(group)) sourceConflict = true
            merge(group)
        }.sortedBy { it.expectedTime?.value?.toInstant() ?: it.scheduledTime.toInstant() }

        val warnings = buildSet {
            boards.flatMapTo(this) { it.warnings }
            if (sourceConflict) add(ProviderWarning.SOURCE_CONFLICT)
        }

        return StationBoardAggregateResult.Success(
            StationBoardAggregate(
                station = station,
                kind = kind,
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
            if (firstNumber != secondNumber || timeDelta > identityTolerance) return false
            return normalize(endpoint(first.entry)) == normalize(endpoint(second.entry))
        }

        return first.entry.runId == second.entry.runId &&
            timeDelta <= identityTolerance &&
            normalize(endpoint(first.entry)) == normalize(endpoint(second.entry))
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
            routeChange = routeChange
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

data class StationBoardAggregate(
    val station: Station,
    val kind: BoardKind,
    val entries: List<StationBoardEntry>,
    val providerNames: List<String>,
    val warnings: Set<ProviderWarning>,
    val fetchedAt: Instant
)

sealed interface StationBoardAggregateResult {
    data class Success(val board: StationBoardAggregate) : StationBoardAggregateResult
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
        val message: String
    ) : ProviderAttempt
}

private data class EntryCandidate(
    val providerName: String,
    val board: StationBoard,
    val entry: StationBoardEntry
)
