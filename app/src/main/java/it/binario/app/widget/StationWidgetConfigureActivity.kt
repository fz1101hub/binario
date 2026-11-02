package it.binario.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.ThemeSettings
import it.binario.app.TrainViewModel

/** Picks the station of one station board widget, with the same station search as the route widget. */
class StationWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val themeSettings = remember { ThemeSettings(applicationContext) }
            BinarioTheme(themeSettings.mode) {
                val model: TrainViewModel = viewModel(factory = remember {
                    WidgetConfiguratorViewModelFactory(this@StationWidgetConfigureActivity.application)
                })
                val view = LocalView.current
                val background = MaterialTheme.colorScheme.background
                val foregroundIsLight = MaterialTheme.colorScheme.onBackground.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = foregroundIsLight.not()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = foregroundIsLight.not()
                }

                val existingConfiguration = remember { StationWidgetConfigurationStore(this@StationWidgetConfigureActivity).read(widgetId) }
                val existing = existingConfiguration?.station
                var stationText by remember { mutableStateOf(existing?.name.orEmpty()) }
                // A new widget starts with the new defaults (automatic refresh off); an existing one keeps its settings.
                var settings by remember { mutableStateOf(existingConfiguration?.settings ?: StationWidgetSettings()) }
                var validationMessage by remember { mutableStateOf<String?>(null) }
                val missingStation = stringResource(R.string.station_widget_validation_missing_station)

                fun saveConfiguration() {
                    // An unchanged text keeps the configured station: before the catalogue has loaded,
                    // resolving its name again could only produce a free-text station.
                    val station = existing?.takeIf { it.name.equals(stationText.trim(), ignoreCase = true) }
                        ?: model.stationFromName(stationText)
                    if (station == null) {
                        validationMessage = missingStation
                        return
                    }
                    val context = this@StationWidgetConfigureActivity
                    // Rows of a previous station must never appear under the new one.
                    if (station.id != existing?.id) StationWidgetStateStore.clear(context, widgetId)
                    StationWidgetConfigurationStore(context).save(widgetId, StationWidgetConfiguration(station, settings.copy(displayName = settings.displayName.trim())))
                    StationWidgetProvider.refreshAll(context)
                    // The refresh enqueued now counts as the latest one for the automatic schedule.
                    WidgetAutoRefreshStore.recordAttempt(context, WidgetKind.STATION, widgetId)
                    StationWidgetRefreshWorker.enqueue(context, widgetId)
                    WidgetAutoRefreshScheduler.reschedule(context)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }

                Surface(modifier = Modifier.fillMaxSize(), color = background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(stringResource(R.string.station_widget_configure_title), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.station_widget_configure_description),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = settings.displayName,
                                onValueChange = { if (it.length <= 42) settings = settings.copy(displayName = it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.widget_display_name_label)) },
                                supportingText = { Text(stringResource(R.string.station_widget_display_name_description)) },
                                singleLine = true
                            )
                        }
                        item {
                            WidgetStationField(
                                label = stringResource(R.string.station_label),
                                value = stationText,
                                onValueChange = { stationText = it; validationMessage = null },
                                model = model,
                                fieldKey = "station-widget"
                            )
                        }
                        if (model.favouriteStations.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.station_widget_favourites_optional),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                )
                                model.favouriteStations.take(4).forEach { station ->
                                    WidgetChoiceButton(station.name, station.name.equals(stationText.trim(), ignoreCase = true)) {
                                        stationText = station.name
                                        validationMessage = null
                                        model.clearStationSuggestions("station-widget")
                                    }
                                }
                            }
                        }
                        item { AutoRefreshSection(settings.autoRefresh) { settings = settings.copy(autoRefresh = it) } }
                        item { TrainTypeExclusionSection(settings.excludedTypes) { settings = settings.copy(excludedTypes = it) } }
                        item {
                            WidgetSettingsCard(stringResource(R.string.widget_settings_display_title)) {
                                WidgetSwitchRow(stringResource(R.string.widget_settings_show_platform), settings.showPlatform, { settings = settings.copy(showPlatform = it) })
                                WidgetSwitchRow(stringResource(R.string.widget_settings_show_train_name), settings.showTrainName, { settings = settings.copy(showTrainName = it) })
                                WidgetSwitchRow(
                                    stringResource(R.string.station_widget_settings_nearest),
                                    settings.showNearestButton,
                                    { settings = settings.copy(showNearestButton = it) },
                                    stringResource(R.string.station_widget_settings_nearest_description)
                                )
                            }
                        }
                        validationMessage?.let { message ->
                            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Text(
                                    stringResource(
                                        R.string.station_widget_selected_summary,
                                        stationText.trim().ifBlank { stringResource(R.string.station_widget_station_missing) }
                                    ),
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        item {
                            Button(onClick = ::saveConfiguration, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.widget_save)) }
                        }
                        item {
                            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
            }
        }
    }
}
