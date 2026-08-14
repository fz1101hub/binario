package it.binario.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.BinarioTheme
import it.binario.app.ThemeSettings
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
            val themeSettings = remember { ThemeSettings(applicationContext) }
            BinarioTheme(themeSettings.mode) {
                val model: TrainViewModel = viewModel()
                var targetType by remember { mutableStateOf(WidgetTargetType.ROUTE) }
                var selectedId by remember { mutableStateOf(model.activeRoute.id) }
                var selectedLabel by remember { mutableStateOf(model.activeRoute.name) }

                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text("Configura widget", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "La scelta vale solo per questo widget. Puoi modificarla in seguito dal pulsante Modifica nella testata.",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            WidgetTargetCard(
                                type = WidgetTargetType.ROUTE,
                                selected = targetType == WidgetTargetType.ROUTE,
                                description = "Mostra le prossime corse della tratta scelta, con paginazione, direzione e refresh.",
                                onSelect = {
                                    targetType = WidgetTargetType.ROUTE
                                    selectedId = model.activeRoute.id
                                    selectedLabel = model.activeRoute.name
                                }
                            )
                            if (targetType == WidgetTargetType.ROUTE) {
                                Text("Scegli la tratta", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                model.savedRoutes.forEach { route ->
                                    WidgetChoiceButton(route.name, route.id == selectedId) { selectedId = route.id; selectedLabel = route.name }
                                }
                            }
                        }
                        item {
                            WidgetTargetCard(
                                type = WidgetTargetType.STATION,
                                selected = targetType == WidgetTargetType.STATION,
                                description = "Mostra una stazione monitorata. Le partenze reali richiederanno un provider autorizzato.",
                                onSelect = {
                                    targetType = WidgetTargetType.STATION
                                    model.stations.firstOrNull()?.let { selectedId = it.id; selectedLabel = it.name }
                                }
                            )
                            if (targetType == WidgetTargetType.STATION) {
                                Text("Scegli la stazione", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                model.stations.forEach { station ->
                                    WidgetChoiceButton(station.name, station.id == selectedId) { selectedId = station.id; selectedLabel = station.name }
                                }
                            }
                        }
                        item {
                            WidgetTargetCard(
                                type = WidgetTargetType.TRAIN,
                                selected = targetType == WidgetTargetType.TRAIN,
                                description = "Mostra il treno selezionato nello snapshot locale. Stato e variazioni reali richiedono un provider autorizzato.",
                                onSelect = {
                                    targetType = WidgetTargetType.TRAIN
                                    trainChoices(model).firstOrNull()?.let { choice -> selectedId = choice.first; selectedLabel = choice.second }
                                }
                            )
                            if (targetType == WidgetTargetType.TRAIN) {
                                Text("Scegli il treno", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                val choices = trainChoices(model)
                                if (choices.isEmpty()) {
                                    Text("Nessun treno è ancora disponibile nello snapshot locale. Apri una tratta e aggiorna prima di configurare questa superficie.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                                } else {
                                    choices.forEach { choice ->
                                        WidgetChoiceButton("${choice.first} · ${choice.second}", choice.first == selectedId) { selectedId = choice.first; selectedLabel = choice.second }
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            "Scelto: ${targetType.label} — $selectedLabel",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Button(
                        onClick = {
                            WidgetConfigurationStore(this@WidgetConfigureActivity).save(widgetId, WidgetDisplayConfiguration(targetType, selectedId, selectedLabel))
                            RouteWidgetProvider.refreshAll(this@WidgetConfigureActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        },
                        enabled = selectedId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) { Text("Salva widget") }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Annulla") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetTargetCard(type: WidgetTargetType, selected: Boolean, description: String, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${type.label}. $description" },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(type.label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        enabled = true
    ) {
        Text(if (selected) "Selezionato · $label" else label, modifier = Modifier.fillMaxWidth())
    }
}

private fun trainChoices(model: TrainViewModel): List<Pair<String, String>> =
    (model.monitoredServices.map { it.number to it.label } +
        model.snapshot?.services.orEmpty().map { it.number to "${it.operator.shortLabel} · ${it.destination}" })
        .distinctBy { it.first }
