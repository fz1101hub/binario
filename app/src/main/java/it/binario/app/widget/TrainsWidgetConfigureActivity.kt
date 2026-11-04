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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import it.binario.app.BinarioTheme
import it.binario.app.FavouriteTrainStore
import it.binario.app.R
import it.binario.app.ThemeSettings

/** Settings of one "I miei treni" widget; the trains themselves are the favourites saved in the app. */
class TrainsWidgetConfigureActivity : ComponentActivity() {
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
                val view = LocalView.current
                val background = MaterialTheme.colorScheme.background
                val foregroundIsLight = MaterialTheme.colorScheme.onBackground.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = foregroundIsLight.not()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = foregroundIsLight.not()
                }
                val context = this@TrainsWidgetConfigureActivity
                var settings by remember { mutableStateOf(TrainsWidgetConfigurationStore(context).read(widgetId) ?: TrainsWidgetSettings()) }
                val savedTrains = remember { FavouriteTrainStore(context).all().size }

                fun save() {
                    TrainsWidgetConfigurationStore(context).save(widgetId, settings.copy(displayName = settings.displayName.trim()))
                    TrainsWidgetProvider.refreshAll(context)
                    WidgetAutoRefreshStore.recordAttempt(context, WidgetKind.TRAINS, widgetId)
                    TrainsWidgetRefreshWorker.enqueue(context)
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
                            Text(stringResource(R.string.trains_widget_configure_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.trains_widget_configure_description),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Text(
                                    if (savedTrains == 0) stringResource(R.string.trains_widget_settings_no_trains)
                                    else pluralStringResource(R.plurals.trains_widget_settings_saved, savedTrains, savedTrains),
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = settings.displayName,
                                onValueChange = { if (it.length <= 42) settings = settings.copy(displayName = it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.widget_display_name_label)) },
                                supportingText = { Text(stringResource(R.string.trains_widget_display_name_description)) },
                                singleLine = true
                            )
                        }
                        item {
                            WidgetSettingsCard(stringResource(R.string.trains_widget_settings_info_title)) {
                                WidgetChoiceRow(
                                    stringResource(R.string.trains_widget_settings_info_next),
                                    settings.info == TrainsWidgetInfo.NEXT_STATION,
                                    { settings = settings.copy(info = TrainsWidgetInfo.NEXT_STATION) }
                                )
                                WidgetChoiceRow(
                                    stringResource(R.string.trains_widget_settings_info_last),
                                    settings.info == TrainsWidgetInfo.LAST_DETECTION,
                                    { settings = settings.copy(info = TrainsWidgetInfo.LAST_DETECTION) }
                                )
                                WidgetSwitchRow(stringResource(R.string.widget_settings_show_platform), settings.showPlatform, { settings = settings.copy(showPlatform = it) })
                                WidgetReliabilitySwitchRow(settings.showReliability) { settings = settings.copy(showReliability = it) }
                            }
                        }
                        item {
                            WidgetSettingsCard(stringResource(R.string.trains_widget_settings_list_title)) {
                                WidgetSwitchRow(
                                    stringResource(R.string.trains_widget_settings_hide_not_running),
                                    settings.hideNotRunning,
                                    { settings = settings.copy(hideNotRunning = it) },
                                    stringResource(R.string.trains_widget_settings_hide_not_running_description)
                                )
                                Text(
                                    stringResource(R.string.trains_widget_settings_sort_title),
                                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                                    style = MaterialTheme.typography.labelLarge
                                )
                                WidgetChoiceRow(
                                    stringResource(R.string.trains_widget_settings_sort_departure),
                                    settings.sort == TrainsWidgetSort.DEPARTURE,
                                    { settings = settings.copy(sort = TrainsWidgetSort.DEPARTURE) }
                                )
                                WidgetChoiceRow(
                                    stringResource(R.string.trains_widget_settings_sort_name),
                                    settings.sort == TrainsWidgetSort.NAME,
                                    { settings = settings.copy(sort = TrainsWidgetSort.NAME) }
                                )
                            }
                        }
                        item { AutoRefreshSection(settings.autoRefresh) { settings = settings.copy(autoRefresh = it) } }
                        item {
                            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.widget_save)) }
                        }
                        item {
                            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
            }
        }
    }
}
