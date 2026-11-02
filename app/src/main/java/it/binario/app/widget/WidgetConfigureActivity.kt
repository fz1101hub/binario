package it.binario.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.ThemeSettings
import it.binario.app.TrainViewModel
import it.binario.app.data.Station
import it.binario.app.ui.common.ltrUnit

internal class WidgetConfiguratorViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TrainViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return TrainViewModel(application, enableStartupEffects = false) as T
    }
}

class WidgetConfigureActivity : ComponentActivity() {
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
                    WidgetConfiguratorViewModelFactory(this@WidgetConfigureActivity.application)
                })
                val view = LocalView.current
                val background = MaterialTheme.colorScheme.background
                val foregroundIsLight = MaterialTheme.colorScheme.onBackground.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = foregroundIsLight.not()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = foregroundIsLight.not()
                }

                val existing = remember { WidgetConfigurationStore(this@WidgetConfigureActivity).read(widgetId) }
                var targetType by remember { mutableStateOf(existing?.targetType ?: WidgetTargetType.ROUTE) }
                var routeOriginText by remember { mutableStateOf(existing?.routeOrigin?.name.orEmpty()) }
                var routeDestinationText by remember { mutableStateOf(existing?.routeDestination?.name.orEmpty()) }
                var displayName by remember { mutableStateOf(existing?.displayName.orEmpty()) }
                // A new widget starts with the new defaults (automatic refresh off); an existing one keeps its settings.
                var settings by remember { mutableStateOf(existing?.settings ?: RouteWidgetSettings()) }
                var validationMessage by remember { mutableStateOf<String?>(null) }
                val routeOrigin = model.stationFromName(routeOriginText)
                val routeDestination = model.stationFromName(routeDestinationText)
                val validationMissingStations = stringResource(R.string.widget_validation_missing_stations)
                val validationSameStation = stringResource(R.string.widget_validation_same_station)
                val routeTargetLabel = stringResource(R.string.widget_target_route)
                val routeMissingLabel = stringResource(R.string.widget_route_missing)

                fun saveConfiguration() {
                    val configuration = when (targetType) {
                        WidgetTargetType.ROUTE -> when {
                            routeOrigin == null || routeDestination == null -> {
                                validationMessage = validationMissingStations
                                null
                            }
                            routeOrigin.id == routeDestination.id -> {
                                validationMessage = validationSameStation
                                null
                            }
                            else -> WidgetDisplayConfiguration(
                                targetType = WidgetTargetType.ROUTE,
                                targetId = "route:${routeOrigin.id}:${routeDestination.id}",
                                targetLabel = "${routeOrigin.name} → ${routeDestination.name}",
                                displayName = displayName.trim(),
                                routeOrigin = routeOrigin,
                                routeDestination = routeDestination,
                                settings = settings
                            )
                        }
                    }
                    configuration?.let {
                        // Uno stato/snapshot di una configurazione precedente non deve mai
                        // comparire per la nuova tratta o direzione dell'istanza.
                        val context = this@WidgetConfigureActivity
                        WidgetStateStore.clear(context, widgetId)
                        WidgetConfigurationStore(context).save(widgetId, it)
                        RouteWidgetProvider.refreshAll(context)
                        // The refresh enqueued now counts as the latest one for the automatic schedule.
                        WidgetAutoRefreshStore.recordAttempt(context, WidgetKind.ROUTE, widgetId)
                        WidgetRefreshWorker.enqueue(context)
                        WidgetAutoRefreshScheduler.reschedule(context)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(stringResource(R.string.widget_configure_title), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.widget_configure_description),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { if (it.length <= 42) displayName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.widget_display_name_label)) },
                                supportingText = { Text(stringResource(R.string.widget_display_name_description)) },
                                singleLine = true
                            )
                        }
                        item {
                            WidgetTargetCard(
                                title = routeTargetLabel,
                                selected = targetType == WidgetTargetType.ROUTE,
                                description = stringResource(R.string.widget_route_description),
                                onSelect = { targetType = WidgetTargetType.ROUTE; validationMessage = null }
                            )
                            if (targetType == WidgetTargetType.ROUTE) {
                                Text(stringResource(R.string.widget_route_input_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                WidgetStationField(stringResource(R.string.widget_origin), routeOriginText, { routeOriginText = it }, model, "widget-origin")
                                WidgetStationField(stringResource(R.string.widget_destination), routeDestinationText, { routeDestinationText = it }, model, "widget-destination")
                                if (model.savedRoutes.isNotEmpty()) {
                                    Text(stringResource(R.string.widget_saved_routes_optional), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                                    model.savedRoutes.take(4).forEach { route ->
                                        WidgetChoiceButton(route.displayName(it.binario.app.data.Direction.OUTBOUND), false) {
                                            routeOriginText = route.outwardOrigin.name
                                            routeDestinationText = route.outwardDestination.name
                                        }
                                    }
                                }
                            }
                        }
                        item { AutoRefreshSection(settings.autoRefresh) { settings = settings.copy(autoRefresh = it) } }
                        item { RouteDirectionSection(settings) { settings = it } }
                        item { RouteTimeRangeSection(settings) { settings = it } }
                        item { TrainTypeExclusionSection(settings.excludedTypes) { settings = settings.copy(excludedTypes = it) } }
                        item {
                            WidgetSettingsCard(stringResource(R.string.widget_settings_display_title)) {
                                WidgetSwitchRow(
                                    stringResource(R.string.widget_settings_until_arrival),
                                    settings.untilArrival,
                                    { settings = settings.copy(untilArrival = it) },
                                    stringResource(R.string.widget_settings_until_arrival_description)
                                )
                                WidgetSwitchRow(stringResource(R.string.widget_settings_show_platform), settings.showPlatform, { settings = settings.copy(showPlatform = it) })
                                WidgetSwitchRow(stringResource(R.string.widget_settings_show_train_name), settings.showTrainName, { settings = settings.copy(showTrainName = it) })
                            }
                        }
                        validationMessage?.let { message ->
                            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                val selectedLabel = when (targetType) {
                                    WidgetTargetType.ROUTE -> listOf(routeOriginText, routeDestinationText).filter { it.isNotBlank() }.joinToString(" → ").let { ltrUnit(it) }.ifBlank { routeMissingLabel }
                                }
                                Text(stringResource(R.string.widget_selected_summary, routeTargetLabel, selectedLabel), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
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

@androidx.compose.runtime.Composable
internal fun WidgetStationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    model: TrainViewModel,
    fieldKey: String
) {
    val matches = model.stationSuggestions(fieldKey)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { query ->
                onValueChange(query)
                model.updateStationSuggestions(fieldKey, query)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true
        )
        matches.take(4).forEach { station ->
            WidgetChoiceButton(
                if (station.id.startsWith("manual:")) stringResource(R.string.station_suggestion_free_text, station.name) else station.name,
                false
            ) {
                onValueChange(station.name)
                model.clearStationSuggestions(fieldKey)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetTargetCard(title: String, selected: Boolean, description: String, onSelect: () -> Unit) {
    val accessibilityLabel = stringResource(R.string.widget_accessibility_target, title, description)
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun WidgetChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(if (selected) stringResource(R.string.widget_selected_option, label) else label, modifier = Modifier.fillMaxWidth())
    }
}

/** "Mostra il ritorno dalle HH:mm": automatic direction by time of day. */
@androidx.compose.runtime.Composable
private fun RouteDirectionSection(settings: RouteWidgetSettings, onChange: (RouteWidgetSettings) -> Unit) {
    WidgetSettingsCard(stringResource(R.string.widget_settings_direction_title), stringResource(R.string.widget_settings_return_description)) {
        val returnFrom = settings.returnFrom
        WidgetSwitchRow(
            stringResource(R.string.widget_settings_return_switch),
            returnFrom != null,
            { enabled -> onChange(settings.copy(returnFrom = if (enabled) DEFAULT_RETURN_FROM else null)) }
        )
        if (returnFrom != null) {
            val label = returnFrom.format(HH_MM)
            WidgetTimeButton(
                stringResource(R.string.widget_settings_return_from, label),
                stringResource(R.string.widget_settings_return_from_accessibility, label),
                returnFrom
            ) { onChange(settings.copy(returnFrom = it)) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RouteTimeRangeSection(settings: RouteWidgetSettings, onChange: (RouteWidgetSettings) -> Unit) {
    WidgetSettingsCard(stringResource(R.string.widget_settings_range_title)) {
        WidgetChoiceRow(stringResource(R.string.widget_settings_range_next), settings.timeRange == RouteTimeRange.NEXT, { onChange(settings.copy(timeRange = RouteTimeRange.NEXT)) })
        WidgetChoiceRow(stringResource(R.string.widget_settings_range_3h), settings.timeRange == RouteTimeRange.NEXT_3H, { onChange(settings.copy(timeRange = RouteTimeRange.NEXT_3H)) })
        WidgetChoiceRow(stringResource(R.string.widget_settings_range_6h), settings.timeRange == RouteTimeRange.NEXT_6H, { onChange(settings.copy(timeRange = RouteTimeRange.NEXT_6H)) })
        WidgetChoiceRow(
            stringResource(R.string.widget_settings_range_day),
            settings.timeRange == RouteTimeRange.WHOLE_DAY,
            { onChange(settings.copy(timeRange = RouteTimeRange.WHOLE_DAY)) },
            stringResource(R.string.widget_settings_range_day_description)
        )
    }
}

private val DEFAULT_RETURN_FROM: java.time.LocalTime = java.time.LocalTime.of(14, 0)
