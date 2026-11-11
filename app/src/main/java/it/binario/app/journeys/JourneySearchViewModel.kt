package it.binario.app.journeys

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.binario.app.TravelPreferencesRepository
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.JourneySearchQuery
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchService
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.SourcedJourneyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the journeys-with-changes results need; [presentation] is null before the first answer. */
data class JourneySearchUiState(
    val loading: Boolean = false,
    val query: JourneySearchQuery? = null,
    val result: JourneySearchResult? = null,
    val filter: JourneyResultsFilter = JourneyResultsFilter.ALL,
    val presentation: JourneyResultsPresentation? = null,
    /** A request the screen should not have sent (same station twice), localized at render. */
    val validationMessage: UiMessage? = null,
    /** Live data of the legs running now or soon ([JourneyLiveLookup]); empty until it arrives or without live data. */
    val liveLegs: Map<String, JourneyLegLive> = emptyMap(),
    /** «Più presto» and «Più tardi»: their windows are merged into [result], which keeps answering [query]. */
    val earlier: ResultsPageState = ResultsPageState(),
    val later: ResultsPageState = ResultsPageState()
)

/**
 * How the live data of the legs is kept up to date while the results or a journey stay on screen: the lookup is
 * repeated (never more often than [MIN_INTERVAL] by the periodic refresh), its answers replace the earlier ones and
 * an earlier answer that is not confirmed again is shown for [MAX_AGE] at most, then the leg is back on its timetable.
 */
object JourneyLiveRefresh {
    /** A leg's live times are shown this long after the lookup that found them, unless a newer lookup replaces them. */
    val MAX_AGE: Duration = Duration.ofMinutes(5)

    /** The periodic refresh repeats the lookup (up to eight train numbers) once a minute, not at every 30-second tick. */
    val MIN_INTERVAL: Duration = Duration.ofSeconds(60)

    /** The legs of [legs] whose lookup ([fetchedAt], by leg key) is not older than [MAX_AGE] at [now]. */
    fun current(legs: Map<String, JourneyLegLive>, fetchedAt: Map<String, Instant>, now: Instant): Map<String, JourneyLegLive> =
        legs.filterKeys { key -> fetchedAt[key]?.let { Duration.between(it, now) <= MAX_AGE } == true }

    /** A periodic lookup is due at [now] when none started in the last [MIN_INTERVAL]. */
    fun isDue(lastStartedAt: Instant?, now: Instant): Boolean =
        lastStartedAt == null || Duration.between(lastStartedAt, now) >= MIN_INTERVAL

    /** [options] with the journey on screen ([focusedId]) first, so its trains are always among those looked up. */
    fun <T> focusedFirst(options: List<T>, focusedId: String?, idOf: (T) -> String): List<T> =
        if (focusedId == null) options else options.sortedByDescending { idOf(it) == focusedId }
}

/**
 * Entry point of the journeys-with-changes results, separate from [it.binario.app.TrainViewModel] so the direct-
 * train search keeps working exactly as before. The screen obtains it with `viewModel(factory =
 * JourneySearchViewModel.factory(context))`, calls [searchJourneys] with the planner's stations, date and time, and
 * renders [state]. Searching runs off the main thread (the providers switch dispatcher); a new search cancels the
 * previous one and a late answer of a cancelled search is never shown.
 */
class JourneySearchViewModel(
    private val service: JourneySearchService,
    /** Live lookup of the legs for "Coincidenza a rischio"; null keeps the scheduled times only. */
    private val liveLookup: JourneyLiveLookup? = null,
    /** Live times of the metro and tram legs from their city's open realtime feed; null keeps them scheduled. */
    private val urbanLiveLookup: UrbanJourneyLiveLookup? = null,
    /** "Preferenze di viaggio" sent with every search; a change searches the shown journeys again. Null: none. */
    private val preferences: StateFlow<JourneyPreferences>? = null,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val mutableState = MutableStateFlow(JourneySearchUiState())
    val state: StateFlow<JourneySearchUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var pageJob: Job? = null
    private var liveJob: Job? = null
    private var searchGeneration = 0L
    /** When the lookup that answered each live leg started (by leg key), and when the last lookup started. */
    private val liveFetchedAt = HashMap<String, Instant>()
    private var liveLookupStartedAt: Instant? = null
    /** A search or a live lookup is running: [refreshLive] starts nothing meanwhile. */
    internal val isBusy: Boolean get() = searchJob?.isActive == true || liveJob?.isActive == true
    private val zone = RailTime.defaultZone

    init {
        preferences?.let { flow ->
            viewModelScope.launch {
                flow.drop(1).collect { changed ->
                    state.value.query?.takeIf { it.preferences != changed }?.let { start(it.copy(preferences = changed)) }
                }
            }
        }
    }

    /** Starts a search; false (with [JourneySearchUiState.validationMessage]) when origin and destination coincide. */
    fun searchJourneys(
        origin: Station,
        destination: Station,
        serviceDate: LocalDate,
        departureTime: LocalTime,
        maxTransfers: Int = 2
    ): Boolean {
        if (origin.id == destination.id) {
            mutableState.update { it.copy(validationMessage = UiMessage(UiMessageKey.ROUTE_STATIONS_MUST_DIFFER)) }
            return false
        }
        val query = JourneySearchQuery(
            origin = origin,
            destination = destination,
            departure = LocalDateTime.of(serviceDate, departureTime.withSecond(0).withNano(0)),
            maxTransfers = maxTransfers.coerceIn(0, 6),
            preferences = preferences?.value
        )
        start(query)
        return true
    }

    /** Repeats the last search (for example after "not available"). */
    fun retry() {
        state.value.query?.let(::start)
    }

    fun selectFilter(filter: JourneyResultsFilter) {
        mutableState.update { current ->
            current.copy(filter = filter, presentation = current.result?.let { JourneyResultsPresenter.present(it, filter, live = current.liveLegs) })
        }
    }

    fun reset() {
        searchJob?.cancel()
        pageJob?.cancel()
        liveJob?.cancel()
        searchGeneration += 1
        liveFetchedAt.clear()
        liveLookupStartedAt = null
        mutableState.value = JourneySearchUiState()
    }

    /**
     * Repeats only the live lookup of the journeys shown (same legs, window and train cap as after the search), so the
     * expected times and «Coincidenza a rischio» follow the trains while the results or a journey stay open. The
     * journey on screen ([focusedOptionId]) is looked up first. Live times older than [JourneyLiveRefresh.MAX_AGE] are
     * dropped at once, before the answer. The periodic caller is limited to one lookup per
     * [JourneyLiveRefresh.MIN_INTERVAL]; [force] (the refresh button) is not, but nothing starts while a search or
     * another lookup is running.
     */
    fun refreshLive(focusedOptionId: String? = null, force: Boolean = false) {
        val current = state.value
        val result = current.result ?: return
        if (liveLookup == null && urbanLiveLookup == null) return
        val instant = now()
        dropExpiredLive(instant)
        if (current.loading || isBusy) return
        if (!force && !JourneyLiveRefresh.isDue(liveLookupStartedAt, instant)) return
        val options = JourneyLiveRefresh.focusedFirst(result.options, focusedOptionId) { it.option.id.value }
        val generation = searchGeneration
        liveLookupStartedAt = instant
        liveJob = viewModelScope.launch {
            val live = lookupLive(options)
            if (generation != searchGeneration) return@launch
            // Also without an answer: what the earlier lookups found is not shown past its age.
            publishLive(live, instant)
        }
    }

    /** Adds [live] (found by the lookup started at [startedAt]) to the legs shown and drops those past their age. */
    private fun publishLive(live: Map<String, JourneyLegLive>, startedAt: Instant) {
        live.keys.forEach { liveFetchedAt[it] = startedAt }
        val instant = now()
        mutableState.update { state ->
            val legs = JourneyLiveRefresh.current(state.liveLegs + live, liveFetchedAt, instant)
            if (legs == state.liveLegs) state
            else state.copy(liveLegs = legs, presentation = state.result?.let { JourneyResultsPresenter.present(it, state.filter, live = legs) })
        }
        liveFetchedAt.keys.retainAll(state.value.liveLegs.keys)
    }

    private fun dropExpiredLive(instant: Instant) {
        if (state.value.liveLegs.isEmpty()) return
        mutableState.update { state ->
            val legs = JourneyLiveRefresh.current(state.liveLegs, liveFetchedAt, instant)
            if (legs.size == state.liveLegs.size) state
            else state.copy(liveLegs = legs, presentation = state.result?.let { JourneyResultsPresenter.present(it, state.filter, live = legs) })
        }
        liveFetchedAt.keys.retainAll(state.value.liveLegs.keys)
    }

    /** The live data of [options]: the train legs of the journeys with changes and the city legs with a realtime feed. */
    private suspend fun lookupLive(options: List<SourcedJourneyOption>): Map<String, JourneyLegLive> = coroutineScope {
        val trains = async { liveLookup?.takeIf { options.any { it.option.transfers > 0 } }?.lookup(options).orEmpty() }
        val urban = async { urbanLiveLookup?.takeIf { it.wants(options) }?.lookup(options).orEmpty() }
        trains.await() + urban.await()
    }

    /**
     * «Più tardi»: the options of the window from the last departure found, merged into the result (same query, so
     * the screen keeps showing it). Nothing happens while a search or another page is loading, or without options.
     */
    fun loadLater() = page(later = true)

    /** «Più presto»: the options leaving before the first departure found ([ResultsPaging.earlier]), merged likewise. */
    fun loadEarlier() = page(later = false)

    private fun page(later: Boolean) {
        val current = state.value
        val query = current.query ?: return
        val result = current.result ?: return
        if (current.loading || current.earlier.loading || current.later.loading) return
        val departures = result.options.map { it.option.departure.withZoneSameInstant(zone).toLocalDateTime() }
        val anchor = (if (later) departures.maxOrNull() else departures.minOrNull()) ?: return
        val generation = searchGeneration
        mutableState.update { if (later) it.copy(later = ResultsPageState.LOADING) else it.copy(earlier = ResultsPageState.LOADING) }
        pageJob = viewModelScope.launch {
            val departureOf = { option: SourcedJourneyOption -> option.option.departure.withZoneSameInstant(zone).toLocalDateTime() }
            val loaded = if (later) {
                ResultsPaging.later(anchor, departureOf) { from -> searchPage(query, from, query.maxResults) }
            } else {
                // Never before the day searched: «Più presto» at 06:00 is not yesterday evening.
                ResultsPaging.earlier(anchor, departureOf, notBefore = query.departure.toLocalDate().atStartOfDay()) { from ->
                    searchPage(query, from, ResultsPaging.EARLIER_JOURNEY_RESULTS)
                }
            }
            if (generation != searchGeneration) return@launch
            val shown = state.value.result ?: return@launch
            // "Nessun treno fino alle …" belongs to the searched window only, never to a page.
            val fresh = ResultsPaging.newOptions(shown.options, loaded.orEmpty())
                .map { it.copy(option = it.option.copy(firstAfterEmptyWindow = false)) }
            val merged = if (fresh.isEmpty()) shown else shown.copy(
                status = JourneySearchStatus.FOUND,
                options = (shown.options + fresh).sortedWith(compareBy({ it.option.departure.toInstant() }, { it.option.arrival.toInstant() }))
            )
            val pageState = ResultsPageState(nothingNew = fresh.isEmpty())
            mutableState.update { state ->
                state.copy(
                    result = merged,
                    presentation = JourneyResultsPresenter.present(merged, state.filter, live = state.liveLegs),
                    earlier = if (later) state.earlier else pageState,
                    later = if (later) pageState else state.later
                )
            }
            if (fresh.isEmpty()) return@launch
            val lookupStartedAt = now()
            val live = lookupLive(fresh)
            if (generation != searchGeneration || live.isEmpty()) return@launch
            publishLive(live, lookupStartedAt)
        }
    }

    /** One window of [query] from [from]; null when no source could answer it. */
    private suspend fun searchPage(query: JourneySearchQuery, from: LocalDateTime, maxResults: Int): List<SourcedJourneyOption>? {
        val page = service.search(query.copy(departure = from.withSecond(0).withNano(0), maxResults = maxResults.coerceIn(1, 20)))
        return if (page.status == JourneySearchStatus.FOUND || page.status == JourneySearchStatus.NO_SOLUTION) page.options else null
    }

    private fun start(query: JourneySearchQuery) {
        searchJob?.cancel()
        pageJob?.cancel()
        liveJob?.cancel()
        val generation = ++searchGeneration
        liveFetchedAt.clear()
        liveLookupStartedAt = null
        mutableState.update {
            it.copy(
                loading = true, query = query, result = null, presentation = null, validationMessage = null, liveLegs = emptyMap(),
                earlier = ResultsPageState(), later = ResultsPageState()
            )
        }
        searchJob = viewModelScope.launch {
            val result = service.search(query)
            if (generation != searchGeneration) return@launch
            mutableState.update { current ->
                current.copy(loading = false, result = result, presentation = JourneyResultsPresenter.present(result, current.filter))
            }
            // The scheduled results are shown first; the live check of the changes follows when it answers. The metro
            // and tram legs of the city networks with an open realtime feed are checked at the same time.
            val lookupStartedAt = now()
            liveLookupStartedAt = lookupStartedAt
            val live = lookupLive(result.options)
            if (generation != searchGeneration || live.isEmpty()) return@launch
            publishLive(live, lookupStartedAt)
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                val app = context.applicationContext
                JourneySearchViewModel(
                    BinarioDataGraph.journeySearchService(app),
                    JourneyLiveLookup(BinarioDataGraph.trainNumberSearchService(app)),
                    urbanLiveLookup = BinarioDataGraph.urbanRealtimeFeeds(app).takeIf { it.isNotEmpty() }?.let(::UrbanJourneyLiveLookup),
                    preferences = TravelPreferencesRepository.state(app)
                )
            }
        }
    }
}
