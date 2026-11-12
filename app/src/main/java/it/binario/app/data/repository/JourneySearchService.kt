package it.binario.app.data.repository

import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.DataWarning
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ExpiredTimetable
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.WheelchairDataAware
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.TimetableValidityAware
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A journeys-with-changes search: two stations and a railway-local departure date and time. */
data class JourneySearchQuery(
    val origin: Station,
    val destination: Station,
    val departure: LocalDateTime,
    val maxTransfers: Int = 2,
    val maxResults: Int = 6,
    /** "Preferenze di viaggio" (walks, ranking, service kinds, wheelchair access); null: the planner's defaults. */
    val preferences: JourneyPreferences? = null
)

enum class JourneySearchStatus {
    /** At least one option. */
    FOUND,
    /** A source covers both stations but has nothing in the searched window. */
    NO_SOLUTION,
    /** No source knows one of the stations. */
    NOT_COVERED,
    /** The sources covering the stations end before the requested day (the offline packs need an app update). */
    SCHEDULE_EXPIRED,
    /** Every source failed, timed out, or the request was invalid. */
    UNAVAILABLE
}

/** One option with the source that computed it; kept apart from the direct-train [it.binario.app.data.TrainService]s. */
data class SourcedJourneyOption(
    val option: JourneyOption,
    val sourceId: SourceId,
    /** The provider's own name (Italian, as in the provider registry); the UI shows a localized source label. */
    val sourceName: String,
    /** Scheduled times only: no delay, platform or cancellation has been checked. */
    val scheduledOnly: Boolean
)

data class JourneySearchResult(
    val status: JourneySearchStatus,
    val options: List<SourcedJourneyOption>,
    /** Sources asked, in order, up to the one that answered. */
    val consultedSources: List<SourceId>,
    /** The answering source reported that part of its data could not be read. */
    val incompleteCoverage: Boolean,
    val generatedAt: Instant,
    /**
     * An "accessible only" search found nothing and the timetables have no wheelchair data around its two ends:
     * "Dati di accessibilità non disponibili per questa zona".
     */
    val accessibilityDataUnavailable: Boolean = false,
    /**
     * The answering source left out a timetable that ends before the searched day: the options are those of the other
     * networks only. [expiredTimetables] names the missing ones when the source knows them.
     */
    val staleTimetables: Boolean = false,
    val expiredTimetables: List<ExpiredTimetable> = emptyList()
)

/**
 * Journeys with changes, an addition next to the direct-train route search: it never feeds, reorders or gates the
 * existing route, board or realtime sources.
 *
 * [providers] are asked in order (the registry order for `JOURNEY_SEARCH`); the first that returns at least one
 * option answers, and every later one is a fallback for a provider that is disabled (absent from the list), fails,
 * times out after [providerTimeoutMillis] or returns nothing. Today the list holds only the offline planner on the
 * embedded GTFS packs: ViaggiaTreno's journey-solution endpoint was probed on 2 November 2026 and is no longer
 * published, so there is no online journey source.
 */
class JourneySearchService(
    private val providers: List<JourneyProvider>,
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val zone: ZoneId = RailTime.defaultZone
) {
    init {
        require(providerTimeoutMillis > 0) { "Il timeout deve essere positivo" }
    }

    suspend fun search(query: JourneySearchQuery): JourneySearchResult {
        val request = try {
            JourneySearchRequest(
                origin = StationId(query.origin.id),
                destination = StationId(query.destination.id),
                departureAt = query.departure.atZone(zone),
                maxTransfers = query.maxTransfers,
                originEndpoint = query.origin.endpoint(),
                destinationEndpoint = query.destination.endpoint(),
                maxResults = query.maxResults,
                preferences = query.preferences
            )
        } catch (_: IllegalArgumentException) {
            return JourneySearchResult(JourneySearchStatus.UNAVAILABLE, emptyList(), emptyList(), false, clock.instant())
        }
        val consulted = ArrayList<SourceId>()
        var searchedWithoutResult = false
        var expired = false
        var notCovered = false
        var accessibilityDataUnavailable = false
        for (provider in providers) {
            consulted += provider.descriptor.id
            val result = try {
                withTimeoutOrNull(providerTimeoutMillis) { provider.searchJourneys(request) }
            } catch (error: CancellationException) {
                // Our own cancellation propagates; a provider's internal timeout is just a failed provider.
                currentCoroutineContext().ensureActive()
                null
            } catch (_: RuntimeException) {
                null
            }
            when (result) {
                null, is ProviderResult.NotModified -> Unit
                is ProviderResult.Failure -> if (result.error.kind == ProviderErrorKind.NOT_SUPPORTED) notCovered = true
                is ProviderResult.Success, is ProviderResult.Partial -> {
                    val options = if (result is ProviderResult.Success) result.value else (result as ProviderResult.Partial).value
                    val warnings = (result as? ProviderResult.Partial)?.warnings.orEmpty()
                    if (options.isNotEmpty()) {
                        val stale = ProviderWarning.STALE_SOURCE in warnings
                        val expiredTimetables = if (stale && provider is TimetableValidityAware) {
                            try {
                                withTimeoutOrNull(providerTimeoutMillis) { provider.expiredTimetables(request) }.orEmpty()
                            } catch (error: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                emptyList()
                            } catch (_: RuntimeException) {
                                emptyList()
                            }
                        } else emptyList()
                        return JourneySearchResult(
                            status = JourneySearchStatus.FOUND,
                            options = options.map { option ->
                                SourcedJourneyOption(
                                    option = option,
                                    sourceId = provider.descriptor.id,
                                    sourceName = provider.descriptor.displayName,
                                    scheduledOnly = option.snapshot.completeness == Completeness.STATIC_ONLY ||
                                        DataWarning.STATIC_ONLY in option.snapshot.warnings
                                )
                            },
                            consultedSources = consulted.toList(),
                            incompleteCoverage = ProviderWarning.INCOMPLETE_COVERAGE in warnings,
                            generatedAt = clock.instant(),
                            staleTimetables = stale,
                            expiredTimetables = expiredTimetables
                        )
                    }
                    if (ProviderWarning.STALE_SOURCE in warnings) expired = true else searchedWithoutResult = true
                    if (query.preferences?.wheelchairOnly == true && provider is WheelchairDataAware &&
                        !provider.hasWheelchairData(request)
                    ) accessibilityDataUnavailable = true
                }
            }
        }
        val status = when {
            searchedWithoutResult -> JourneySearchStatus.NO_SOLUTION
            expired -> JourneySearchStatus.SCHEDULE_EXPIRED
            notCovered -> JourneySearchStatus.NOT_COVERED
            else -> JourneySearchStatus.UNAVAILABLE
        }
        return JourneySearchResult(
            status, emptyList(), consulted.toList(), false, clock.instant(),
            accessibilityDataUnavailable = accessibilityDataUnavailable && status == JourneySearchStatus.NO_SOLUTION
        )
    }

    private fun Station.endpoint(): JourneyEndpoint? = name.takeIf(String::isNotBlank)?.let {
        JourneyEndpoint(name = it, aliases = aliases, railwayCode = railwayCode)
    }

    companion object {
        /**
         * Generous: the offline planner is local, but its first search on a slow phone reads every pack's stops and
         * parses a compact index. A timed-out search keeps the pack data it had finished reading for the next one.
         */
        const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 45_000L
    }
}
