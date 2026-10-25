package it.binario.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.notices.StationCoordinateIndex

/**
 * Public-source sections of the train detail (notices, destination weather). The single call site in the detail
 * screen passes the open service and the station catalogue; each section loads lazily and independently.
 */
@Composable
fun TrainDetailSourceSections(service: TrainService, stations: List<Station>) {
    val viewModel: RailNoticesViewModel = viewModel()
    // The catalogue index is built off the main thread; until it exists the sections show their loading state.
    val index: StationCoordinateIndex? by produceState<StationCoordinateIndex?>(null, stations) {
        value = viewModel.coordinateIndex(stations)
    }
    Column {
        TrainNoticesSection(service, viewModel, index)
    }
}
