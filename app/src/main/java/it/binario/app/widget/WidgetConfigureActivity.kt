package it.binario.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.BinarioTheme
import it.binario.app.TrainViewModel

class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            BinarioTheme {
                val model: TrainViewModel = viewModel()
                var targetType by remember { mutableStateOf(WidgetTargetType.ROUTE) }
                var selectedId by remember { mutableStateOf(model.activeRoute.id) }
                var selectedLabel by remember { mutableStateOf(model.activeRoute.name) }
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Configura widget", style = MaterialTheme.typography.headlineSmall)
                    Text("Scegli una superficie per questa sola istanza. Ogni widget può mostrare un contenuto diverso.", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(WidgetTargetType.entries) { type ->
                            FilterChip(selected = targetType == type, onClick = {
                                targetType = type
                                when (type) {
                                    WidgetTargetType.ROUTE -> { selectedId = model.activeRoute.id; selectedLabel = model.activeRoute.name }
                                    WidgetTargetType.STATION -> model.stations.firstOrNull()?.let { selectedId = it.id; selectedLabel = it.name }
                                    WidgetTargetType.TRAIN -> (model.monitoredServices.firstOrNull()?.let { it.number to it.label }
                                        ?: model.snapshot?.services?.firstOrNull()?.let { it.number to "${it.operator.shortLabel} · ${it.destination}" })?.let { choice -> selectedId = choice.first; selectedLabel = choice.second }
                                }
                            }, label = { Text(type.label) })
                        }
                    }
                    when (targetType) {
                        WidgetTargetType.ROUTE -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(model.savedRoutes) { route -> FilterChip(selected = selectedId == route.id, onClick = { selectedId = route.id; selectedLabel = route.name }, label = { Text(route.name) }) }
                        }
                        WidgetTargetType.STATION -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(model.stations) { station -> FilterChip(selected = selectedId == station.id, onClick = { selectedId = station.id; selectedLabel = station.name }, label = { Text(station.name) }) }
                        }
                        WidgetTargetType.TRAIN -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((model.monitoredServices.map { it.number to it.label } + model.snapshot?.services.orEmpty().map { it.number to "${it.operator.shortLabel} · ${it.destination}" }).distinctBy { it.first }) { choice ->
                                val number = choice.first
                                val label = choice.second
                                FilterChip(selected = selectedId == number, onClick = { selectedId = number; selectedLabel = label }, label = { Text(number) })
                            }
                        }
                    }
                    Text("Scelto: $selectedLabel", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        WidgetConfigurationStore(this@WidgetConfigureActivity).save(widgetId, WidgetDisplayConfiguration(targetType, selectedId, selectedLabel))
                        RouteWidgetProvider.refreshAll(this@WidgetConfigureActivity)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }, enabled = selectedId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Salva widget") }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Annulla") }
                }
            }
        }
    }
}
