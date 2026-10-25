package it.binario.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.weather.DailyForecast
import it.binario.app.data.weather.OpenMeteoForecastAdapter
import java.time.LocalDate
import it.binario.app.data.notices.GeneralNotices
import it.binario.app.data.notices.StationCoordinateIndex
import it.binario.app.data.notices.StationNotices
import it.binario.app.data.notices.TrainDetailNotices
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.repository.BinarioDataGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loading state of a lazily requested public-source section. */
sealed interface SourceSectionState<out T> {
    data object Idle : SourceSectionState<Nothing>
    data object Loading : SourceSectionState<Nothing>
    data class Ready<T>(val value: T) : SourceSectionState<T>
}

/** Outcome of the destination weather request. */
sealed interface DestinationWeatherResult {
    data class Forecast(val forecast: DailyForecast, val stale: Boolean) : DestinationWeatherResult
    /** The arrival day is outside the published forecast (today and tomorrow). */
    data object OutOfRange : DestinationWeatherResult
    data object Unavailable : DestinationWeatherResult
}

/**
 * State of the notice and weather sections. Nothing is fetched until a section is shown: each composable asks
 * for its data when it enters the composition, and every request runs off the main thread. A section shown again
 * after [RELOAD_AFTER_MILLIS] asks again; the adapters' own caches decide whether that reaches the network.
 */
class RailNoticesViewModel(application: Application) : AndroidViewModel(application) {
    private val notices = BinarioDataGraph.railNoticesService()
    private val weather = BinarioDataGraph.weatherForecastProvider()
    private val settings = SourcesSettingsStore(application)

    var destinationWeatherEnabled by mutableStateOf(settings.destinationWeatherEnabled)
        private set

    fun updateDestinationWeather(enabled: Boolean) {
        settings.destinationWeatherEnabled = enabled
        destinationWeatherEnabled = enabled
        if (!enabled) {
            weatherJob?.cancel()
            weatherKey = null
            weatherState = SourceSectionState.Idle
        }
    }

    private var weatherState by mutableStateOf<SourceSectionState<DestinationWeatherResult>>(SourceSectionState.Idle)
    private var weatherKey: String? = null
    private var weatherLoadedAt = 0L
    private var weatherJob: Job? = null

    val hasWeatherSource: Boolean get() = weather != null

    fun weatherFor(key: String): SourceSectionState<DestinationWeatherResult> =
        if (key == weatherKey) weatherState else SourceSectionState.Loading

    /** Requests the forecast only while the setting is on; a day outside today/tomorrow causes no request. */
    fun loadWeather(key: String, latitude: Double, longitude: Double, date: LocalDate) {
        val source = weather ?: return
        if (!destinationWeatherEnabled) return
        if (key == weatherKey && weatherState != SourceSectionState.Idle && recent(weatherLoadedAt)) return
        // A refresh of the same place keeps showing the previous forecast until the new answer arrives.
        val keepShown = key == weatherKey && weatherState is SourceSectionState.Ready
        weatherKey = key
        weatherLoadedAt = now()
        weatherJob?.cancel()
        val today = LocalDate.now(RailTime.defaultZone)
        if (date < today || date > today.plusDays(OpenMeteoForecastAdapter.FORECAST_DAYS - 1L)) {
            weatherState = SourceSectionState.Ready(DestinationWeatherResult.OutOfRange)
            return
        }
        if (!keepShown) weatherState = SourceSectionState.Loading
        weatherJob = viewModelScope.launch {
            val result = when (val read = source.forecast(latitude, longitude, date)) {
                is ProviderResult.Success -> DestinationWeatherResult.Forecast(read.value, stale = false)
                is ProviderResult.Partial -> DestinationWeatherResult.Forecast(read.value, stale = true)
                is ProviderResult.Failure -> if (read.error.kind == ProviderErrorKind.NOT_FOUND) DestinationWeatherResult.OutOfRange
                    else DestinationWeatherResult.Unavailable
                is ProviderResult.NotModified -> DestinationWeatherResult.Unavailable
            }
            if (weatherKey == key) weatherState = SourceSectionState.Ready(result)
        }
    }

    val hasTrainNoticeSources: Boolean get() = notices.hasTrainSources

    fun trainNoticesApply(context: TrainNoticeContext): Boolean = notices.appliesTo(context)
    val hasNetworkNoticeSources: Boolean get() = notices.hasNetworkSources
    val hasTrenordSource: Boolean get() = notices.hasTrenordSource

    private var indexedStations: List<Station>? = null
    private var stationIndex: StationCoordinateIndex? = null

    /** Name index of the catalogue, built once per catalogue instance off the main thread. */
    suspend fun coordinateIndex(stations: List<Station>): StationCoordinateIndex {
        stationIndex?.takeIf { indexedStations === stations }?.let { return it }
        val index = withContext(Dispatchers.Default) { StationCoordinateIndex(stations) }
        indexedStations = stations
        stationIndex = index
        return index
    }

    var stationNotices by mutableStateOf<SourceSectionState<StationNotices>>(SourceSectionState.Idle)
        private set
    private var stationKey: String? = null
    private var stationLoadedAt = 0L
    private var stationJob: Job? = null

    fun stationNoticesFor(key: String): SourceSectionState<StationNotices> =
        if (key == stationKey) stationNotices else SourceSectionState.Loading

    fun loadStationNotices(key: String, stationName: String) {
        if (key == stationKey && stationNotices != SourceSectionState.Idle && recent(stationLoadedAt)) return
        val keepShown = key == stationKey && stationNotices is SourceSectionState.Ready
        stationKey = key
        stationLoadedAt = now()
        stationJob?.cancel()
        if (!keepShown) stationNotices = SourceSectionState.Loading
        stationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { notices.forStation(stationName) }
            if (stationKey == key) stationNotices = SourceSectionState.Ready(result)
        }
    }

    var generalNotices by mutableStateOf<SourceSectionState<GeneralNotices>>(SourceSectionState.Idle)
        private set
    private var generalLoadedAt = 0L
    private var generalJob: Job? = null

    /** Loads the notices area; [force] refreshes it (the adapters still apply their own TTL). */
    fun loadGeneralNotices(force: Boolean = false) {
        if (!force && generalNotices != SourceSectionState.Idle && recent(generalLoadedAt)) return
        generalLoadedAt = now()
        generalJob?.cancel()
        if (generalNotices !is SourceSectionState.Ready) generalNotices = SourceSectionState.Loading
        generalJob = viewModelScope.launch {
            generalNotices = SourceSectionState.Ready(withContext(Dispatchers.Default) { notices.general() })
        }
    }

    var trainNotices by mutableStateOf<SourceSectionState<TrainDetailNotices>>(SourceSectionState.Idle)
        private set
    private var trainKey: String? = null
    private var trainLoadedAt = 0L
    private var trainJob: Job? = null

    /** The state for [key] only: a previous train's notices are never shown while another train loads. */
    fun trainNoticesFor(key: String): SourceSectionState<TrainDetailNotices> =
        if (key == trainKey) trainNotices else SourceSectionState.Loading

    fun loadTrainNotices(context: TrainNoticeContext) = loadTrainNotices(context, force = false)

    fun loadTrainNotices(context: TrainNoticeContext, force: Boolean) {
        if (!force && context.key == trainKey && trainNotices != SourceSectionState.Idle && recent(trainLoadedAt)) return
        val keepShown = context.key == trainKey && trainNotices is SourceSectionState.Ready
        trainKey = context.key
        trainLoadedAt = now()
        trainJob?.cancel()
        if (!keepShown) trainNotices = SourceSectionState.Loading
        trainJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { notices.forTrain(context) }
            if (trainKey == context.key) trainNotices = SourceSectionState.Ready(result)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun recent(loadedAt: Long): Boolean = now() - loadedAt in 0 until RELOAD_AFTER_MILLIS

    private companion object {
        const val RELOAD_AFTER_MILLIS = 120_000L
    }
}
