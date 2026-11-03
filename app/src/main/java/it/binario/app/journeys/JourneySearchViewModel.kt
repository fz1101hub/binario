package it.binario.app.journeys

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.binario.app.TravelPreferencesRepository
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.Station
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.JourneySearchQuery
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchService
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
    val liveLegs: Map<String, JourneyLegLive> = emptyMap()
)

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
    private val preferences: StateFlow<JourneyPreferences>? = null
) : ViewModel() {
    private val mutableState = MutableStateFlow(JourneySearchUiState())
    val state: StateFlow<JourneySearchUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var searchGeneration = 0L

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
        searchGeneration += 1
        mutableState.value = JourneySearchUiState()
    }

    private fun start(query: JourneySearchQuery) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        mutableState.update {
            it.copy(loading = true, query = query, result = null, presentation = null, validationMessage = null, liveLegs = emptyMap())
        }
        searchJob = viewModelScope.launch {
            val result = service.search(query)
            if (generation != searchGeneration) return@launch
            mutableState.update { current ->
                current.copy(loading = false, result = result, presentation = JourneyResultsPresenter.present(result, current.filter))
            }
            // The scheduled results are shown first; the live check of the changes follows when it answers. The metro
            // and tram legs of the city networks with an open realtime feed are checked at the same time.
            val live = coroutineScope {
                val trains = async { liveLookup?.takeIf { result.options.any { it.option.transfers > 0 } }?.lookup(result.options).orEmpty() }
                val urban = async { urbanLiveLookup?.takeIf { it.wants(result.options) }?.lookup(result.options).orEmpty() }
                trains.await() + urban.await()
            }
            if (generation != searchGeneration || live.isEmpty()) return@launch
            mutableState.update { current ->
                current.copy(liveLegs = live, presentation = JourneyResultsPresenter.present(result, current.filter, live = live))
            }
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
