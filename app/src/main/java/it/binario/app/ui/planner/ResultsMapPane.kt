package it.binario.app.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Map
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.data.TrainService
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.maps.RouteOverviewMap
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.compactDurationLabel
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.common.presentedAsDemonstration

/**
 * Second pane of the route results on wide windows: the open map of the highlighted solution with a floating
 * summary card ("FRECCIA 9558", the route, times and duration, "Vedi dettagli"). The render's stock photo is
 * replaced by this map; no third-party image is shown. As promised in the privacy policy, the map (and its
 * requests to OpenFreeMap) starts only after "Mostra mappa".
 */
@Composable
internal fun ResultsMapPane(
    service: TrainService,
    isDemonstration: Boolean,
    stationIndex: OpenMapStationIndex,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val locale = LocalConfiguration.current.locales[0]
    // Like the other maps, nothing is requested from the map provider until the traveller asks for it.
    var mapShown by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().padding(12.dp).testTag("results-map-pane")) {
        if (mapShown) {
            RouteOverviewMap(shown, stationIndex, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.map_route_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.results_map_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp)
                )
                FilledTonalButton(onClick = { mapShown = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("results-map-show")) {
                    Text(stringResource(R.string.map_action_show), fontWeight = FontWeight.Bold)
                }
            }
        }
        BinarioCard(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .widthIn(max = 420.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.service_category_number, shown.trainCategory.localizedLabel(), shown.number).uppercase(locale),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    ltrUnit("${shown.origin} → ${shown.destination}"),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge
                )
                val arrival = JourneyTimes.arrival(shown)
                val duration = JourneyTimes.durationMinutes(shown)
                Text(
                    listOfNotNull(
                        ltrUnit(listOfNotNull(shown.scheduledDeparture, arrival).joinToString(" → ")),
                        duration?.let { "(" + compactDurationLabel(it) + ")" }
                    ).joinToString("  "),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures)
                )
                if (isDemonstration) {
                    Pill(
                        stringResource(R.string.data_status_demo),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                TextButton(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth().testTag("results-map-details")) {
                    Text(stringResource(R.string.action_see_details), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}
