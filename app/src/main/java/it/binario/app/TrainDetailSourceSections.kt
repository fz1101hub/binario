package it.binario.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.data.Station
import it.binario.app.data.TrainService

/**
 * Public-source sections of the train detail (notices, destination weather). The single call site in the detail
 * screen passes the open service and the station catalogue; each section loads lazily and independently.
 */
@Composable
fun TrainDetailSourceSections(service: TrainService, stations: List<Station>) {
    val viewModel: RailNoticesViewModel = viewModel()
    Column {
        TrainNoticesSection(service, viewModel)
    }
}
