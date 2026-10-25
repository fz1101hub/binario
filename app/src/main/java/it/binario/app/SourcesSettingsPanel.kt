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
    }
}
