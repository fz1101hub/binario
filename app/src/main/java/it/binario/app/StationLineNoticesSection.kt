package it.binario.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.data.Station
import it.binario.app.data.notices.TrenordLineMatcher

/**
 * Trenord line notices for the station of the open board, shown only for stations in the Trenord area (a Trenord
 * GTFS station or catalogue coordinates in Lombardy and its border), so other boards cause no request.
 */
@Composable
fun StationLineNoticesSection(station: Station, viewModel: RailNoticesViewModel = viewModel()) {
    if (!viewModel.hasTrenordSource || !station.inTrenordArea()) return
    val key = station.id
    LaunchedEffect(key) { viewModel.loadStationNotices(key, station.name) }
    when (val state = viewModel.stationNoticesFor(key)) {
        SourceSectionState.Idle, SourceSectionState.Loading -> Unit
        is SourceSectionState.Ready -> {
            val value = state.value
            if (value.notices.isEmpty() && !value.unavailable) return
            SourceSectionCard(title = stringResource(R.string.notices_station_trenord_title)) {
                value.notices.forEachIndexed { index, notice ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RailNoticeItem(notice)
                }
                if (value.stale) SourceSectionNote(stringResource(R.string.notices_stale))
                if (value.unavailable) SourceSectionNote(stringResource(R.string.notices_unavailable))
            }
        }
    }
}

private fun Station.inTrenordArea(): Boolean {
    if (id.startsWith("gtfs:lombardia-trenord-gtfs:")) return true
    val lat = latitude ?: return false
    val lon = longitude ?: return false
    return TrenordLineMatcher.inTrenordArea(lat, lon)
}
