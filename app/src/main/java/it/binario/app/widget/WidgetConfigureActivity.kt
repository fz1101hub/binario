package it.binario.app.widget

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.BinarioTheme
import it.binario.app.ThemeSettings
import it.binario.app.TrainViewModel
import it.binario.app.data.Station

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
                val model: TrainViewModel = viewModel()
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
                var validationMessage by remember { mutableStateOf<String?>(null) }
                val routeOrigin = model.stationFromName(routeOriginText)
                val routeDestination = model.stationFromName(routeDestinationText)

                fun saveConfiguration() {
                    val configuration = when (targetType) {
                        WidgetTargetType.ROUTE -> when {
                            routeOrigin == null || routeDestination == null -> {
                                validationMessage = "Inserisci partenza e arrivo: puoi scegliere un suggerimento oppure cercare il nome digitato nelle fonti disponibili."
                                null
                            }
                            routeOrigin.id == routeDestination.id -> {
                                validationMessage = "Partenza e arrivo devono essere stazioni diverse."
                                null
                            }
                            else -> WidgetDisplayConfiguration(
                                targetType = WidgetTargetType.ROUTE,
                                targetId = "route:${routeOrigin.id}:${routeDestination.id}",
                                targetLabel = "${routeOrigin.name} → ${routeDestination.name}",
                                displayName = displayName.trim(),
                                routeOrigin = routeOrigin,
                                routeDestination = routeDestination
                            )
                        }
                    }
                    configuration?.let {
                        // Uno stato/snapshot di una configurazione precedente non deve mai
                        // comparire per la nuova tratta o direzione dell'istanza.
                        WidgetStateStore.clear(this@WidgetConfigureActivity, widgetId)
                        WidgetConfigurationStore(this@WidgetConfigureActivity).save(widgetId, it)
                        RouteWidgetProvider.refreshAll(this@WidgetConfigureActivity)
                        WidgetRefreshWorker.ensurePeriodicRefresh(this@WidgetConfigureActivity)
                        WidgetRefreshWorker.enqueue(this@WidgetConfigureActivity)
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
                            Text("Configura widget", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Ogni istanza è indipendente: inserisci una tratta senza prima salvarla nell’app. Il widget mostra solo dati che la fonte restituisce e non promette aggiornamenti in tempo reale.",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { if (it.length <= 42) displayName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Nome del widget (facoltativo)") },
                                supportingText = { Text("Sostituisce il nome della ricerca nella testata e resta locale a questa istanza.") },
                                singleLine = true
                            )
                        }
                        item {
                            WidgetTargetCard(
                                type = WidgetTargetType.ROUTE,
                                selected = targetType == WidgetTargetType.ROUTE,
                                description = "Cerca una tratta digitando liberamente partenza e arrivo. Mostra le prossime corse, con paginazione, direzione e refresh.",
                                onSelect = { targetType = WidgetTargetType.ROUTE; validationMessage = null }
                            )
                            if (targetType == WidgetTargetType.ROUTE) {
                                Text("Inserisci la tratta", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                                WidgetStationField("Partenza", routeOriginText, { routeOriginText = it }, model)
                                WidgetStationField("Arrivo", routeDestinationText, { routeDestinationText = it }, model)
                                if (model.savedRoutes.isNotEmpty()) {
                                    Text("Tratte salvate (facoltative)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                                    model.savedRoutes.take(4).forEach { route ->
                                        WidgetChoiceButton(route.displayName(it.binario.app.data.Direction.OUTBOUND), false) {
                                            routeOriginText = route.outwardOrigin.name
                                            routeDestinationText = route.outwardDestination.name
                                        }
                                    }
                                }
                            }
                        }
                        validationMessage?.let { message ->
                            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                val selectedLabel = when (targetType) {
                                    WidgetTargetType.ROUTE -> listOf(routeOriginText, routeDestinationText).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "Tratta da inserire" }
                                }
                                Text("Scelto: ${targetType.label} — $selectedLabel", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        item {
                            Button(onClick = ::saveConfiguration, modifier = Modifier.fillMaxWidth()) { Text("Salva widget") }
                        }
                        item {
                            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Annulla") }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetStationField(label: String, value: String, onValueChange: (String) -> Unit, model: TrainViewModel) {
    val matches = model.searchStations(value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
        matches.take(4).forEach { station ->
            WidgetChoiceButton(
                if (station.id.startsWith("manual:")) "Cerca “${station.name}” nelle fonti disponibili" else station.name,
                false
            ) { onValueChange(station.name) }
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
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(if (selected) "Selezionato · $label" else label, modifier = Modifier.fillMaxWidth())
    }
}
