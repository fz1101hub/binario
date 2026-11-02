package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.binario.app.data.gtfs.EmbeddedGtfsPacks

/** Settings of the additional public sources: the "Meteo a destinazione" switch and what each source receives. */
@Composable
fun SourcesSettingsPanel(viewModel: RailNoticesViewModel = viewModel()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_sources_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (viewModel.hasWeatherSource) {
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = viewModel.destinationWeatherEnabled,
                    role = Role.Switch,
                    onValueChange = viewModel::updateDestinationWeather
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.weather_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.settings_weather_description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The whole row toggles; the switch only mirrors the state.
                Switch(checked = viewModel.destinationWeatherEnabled, onCheckedChange = null)
            }
        }
        if (EmbeddedGtfsPacks.urban.isNotEmpty()) UrbanStopsSettingRow()
        // What each additional source receives: the in-app counterpart of PRIVACY.md.
        Text(stringResource(R.string.settings_privacy_new_sources), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Includi fermate di metro e tram nella ricerca": the city packs' stops among the station suggestions. */
@Composable
private fun UrbanStopsSettingRow() {
    val context = LocalContext.current
    val store = remember(context) { SourcesSettingsStore(context) }
    var enabled by remember(store) { mutableStateOf(store.urbanStopsInSearch) }
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = enabled,
            role = Role.Switch,
            onValueChange = { value ->
                store.urbanStopsInSearch = value
                enabled = value
            }
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_urban_stops_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_urban_stops_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The whole row toggles; the switch only mirrors the state.
        Switch(checked = enabled, onCheckedChange = null)
    }
}
