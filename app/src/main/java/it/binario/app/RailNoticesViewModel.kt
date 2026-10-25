package it.binario.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.binario.app.data.Station
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

/**
 * State of the notice and weather sections. Nothing is fetched until a section is shown: each composable asks
 * for its data when it enters the composition, and every request runs off the main thread.
 */
class RailNoticesViewModel(application: Application) : AndroidViewModel(application) {
    private val notices = BinarioDataGraph.railNoticesService()

    val hasTrainNoticeSources: Boolean get() = notices.hasTrainSources
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
    private var stationJob: Job? = null

    fun stationNoticesFor(key: String): SourceSectionState<StationNotices> =
        if (key == stationKey) stationNotices else SourceSectionState.Loading

    fun loadStationNotices(key: String, stationName: String) {
        if (key == stationKey && stationNotices != SourceSectionState.Idle) return
        stationKey = key
        stationJob?.cancel()
        stationNotices = SourceSectionState.Loading
        stationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { notices.forStation(stationName) }
            if (stationKey == key) stationNotices = SourceSectionState.Ready(result)
        }
    }

    var generalNotices by mutableStateOf<SourceSectionState<GeneralNotices>>(SourceSectionState.Idle)
        private set
    private var generalJob: Job? = null

    /** Loads the notices area once; [force] refreshes it (the adapters still apply their own TTL). */
    fun loadGeneralNotices(force: Boolean = false) {
        if (!force && generalNotices != SourceSectionState.Idle) return
        generalJob?.cancel()
        generalNotices = SourceSectionState.Loading
        generalJob = viewModelScope.launch {
            generalNotices = SourceSectionState.Ready(withContext(Dispatchers.Default) { notices.general() })
        }
    }

    var trainNotices by mutableStateOf<SourceSectionState<TrainDetailNotices>>(SourceSectionState.Idle)
        private set
    private var trainKey: String? = null
    private var trainJob: Job? = null

    /** The state for [key] only: a previous train's notices are never shown while another train loads. */
    fun trainNoticesFor(key: String): SourceSectionState<TrainDetailNotices> =
        if (key == trainKey) trainNotices else SourceSectionState.Loading

    fun loadTrainNotices(context: TrainNoticeContext) = loadTrainNotices(context, force = false)

    fun loadTrainNotices(context: TrainNoticeContext, force: Boolean) {
        if (!force && context.key == trainKey && trainNotices != SourceSectionState.Idle) return
        trainKey = context.key
        trainJob?.cancel()
        trainNotices = SourceSectionState.Loading
        trainJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { notices.forTrain(context) }
            if (trainKey == context.key) trainNotices = SourceSectionState.Ready(result)
        }
    }
}
