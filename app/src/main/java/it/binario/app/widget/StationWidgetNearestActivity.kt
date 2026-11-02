package it.binario.app.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.ThemeSettings
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.NearestStationPicker
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.localized
import it.binario.app.requestSingleLocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "📍 Più vicina" of the station widget. Opened only by that tap: it asks for the foreground
 * (approximate) location permission when missing, reads one position, picks the nearest station of
 * the offline catalogue, saves it as this widget's station and finishes. The position is never
 * stored or sent; automatic refreshes keep using the chosen station and never read the location.
 * When the permission is denied or no position is available, the station stays and the reason is
 * shown here and in the widget.
 */
class StationWidgetNearestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val configuration = StationWidgetConfigurationStore(this).read(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || configuration == null) {
            finish()
            return
        }

        setContent {
            val themeSettings = remember { ThemeSettings(applicationContext) }
            BinarioTheme(themeSettings.mode) {
                val context = this@StationWidgetNearestActivity
                val scope = rememberCoroutineScope()
                // Saveable, so that a rotation neither asks twice nor loses the reason shown.
                var messageKey by rememberSaveable { mutableStateOf<String?>(null) }
                var awaitingPermission by rememberSaveable { mutableStateOf(false) }
                val message = messageKey?.let { key -> UiMessageKey.entries.firstOrNull { it.name == key } }
                    ?.let { UiMessage(it, configuration.station.name) }

                fun keepStation(key: UiMessageKey) {
                    StationWidgetStateStore.setNearestNote(context, widgetId, UiMessage(key, configuration.station.name))
                    StationWidgetProvider.refreshAll(context)
                    messageKey = key.name
                }

                fun locate() {
                    requestSingleLocationFix(context) { location ->
                        if (location == null) {
                            keepStation(UiMessageKey.WIDGET_NEAREST_LOCATION_UNAVAILABLE)
                            return@requestSingleLocationFix
                        }
                        val latitude = location.latitude
                        val longitude = location.longitude
                        scope.launch {
                            val nearest = withContext(Dispatchers.Default) {
                                val stations = runCatching { EmbeddedCuratedRailStationCatalog(openAsset = { assets.open(it) }).load() }.getOrDefault(emptyList())
                                NearestStationPicker.pick(stations, latitude, longitude)
                            }
                            if (nearest == null) {
                                keepStation(UiMessageKey.WIDGET_NEAREST_NONE_NEARBY)
                            } else {
                                applyStation(context, widgetId, configuration, nearest.station)
                                finish()
                            }
                        }
                    }
                }

                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    awaitingPermission = false
                    if (granted) locate() else keepStation(UiMessageKey.WIDGET_NEAREST_PERMISSION_DENIED)
                }
                LaunchedEffect(Unit) {
                    when {
                        messageKey != null -> Unit
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> locate()
                        // Asked only now, by this tap, with the same foreground permission as the app's nearby stations.
                        !awaitingPermission -> {
                            awaitingPermission = true
                            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.station_widget_nearest_title), style = MaterialTheme.typography.titleMedium)
                        val shown = message
                        if (shown == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text(
                                    stringResource(R.string.station_widget_nearest_locating),
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                shown.localized(),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyStation(context: Context, widgetId: Int, configuration: StationWidgetConfiguration, station: it.binario.app.data.Station) {
        if (station.id != configuration.station.id) {
            // Rows of the previous station must never appear under the new one.
            StationWidgetStateStore.clear(context, widgetId)
            StationWidgetConfigurationStore(context).save(widgetId, configuration.copy(station = station))
        } else {
            StationWidgetStateStore.setNearestNote(context, widgetId, null)
        }
        StationWidgetProvider.refreshAll(context)
        StationWidgetRefreshWorker.enqueue(context, widgetId)
    }
}
