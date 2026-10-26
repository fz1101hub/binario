package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainService

/** A thin view of the canonical ViewModel: no second cache, catalogue or provider graph. */
@Composable
internal fun CommuterHomeScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openSearch: () -> Unit,
    openBoard: () -> Unit,
    openAgenda: () -> Unit,
    openSettings: () -> Unit,
    openService: (TrainService) -> Unit
) {
    val snapshot = viewModel.snapshot
    val loading = viewModel.refreshState == RefreshState.LOADING
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.commuter_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
                Text(stringResource(R.string.commuter_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(20.dp)) {
                    Icon(Icons.Default.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    if (viewModel.hasActiveRoute) {
                        Text(stringResource(R.string.commuter_active_route), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(viewModel.activeRoute.origin(viewModel.direction).name, style = MaterialTheme.typography.titleLarge)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(viewModel.activeRoute.destination(viewModel.direction).name, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = viewModel::switchDirection, enabled = !loading) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.commuter_return))
                        }
                        FilledTonalButton(onClick = viewModel::resumeCurrentDepartures, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.commuter_departures_now))
                        }
                    } else {
                        Text(stringResource(R.string.commuter_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.commuter_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = openSearch, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.commuter_choose_route))
                        }
                    }
                }
            }
        }
        if (viewModel.hasActiveRoute) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (viewModel.followsCurrentDepartures) R.string.commuter_next_trains else R.string.screen_title_results), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SnapshotStatus(snapshot, viewModel.refreshMessage)
                    Text(stringResource(R.string.commuter_refresh_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (viewModel.quickServices().isEmpty() && !loading) {
                item { Text(stringResource(R.string.commuter_no_trains), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(viewModel.quickServices(), key = { "next-${it.id}" }) { service ->
                DepartureListRow(service, isNext = true, isCurrent = snapshot?.freshness != it.binario.app.data.Freshness.STALE) { openService(service) }
            }
            item { OutlinedButton(onClick = openBoard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.commuter_all_trains)) } }
        }
        if (viewModel.savedRoutes.isNotEmpty()) {
            item { Text(stringResource(R.string.commuter_saved_routes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            items(viewModel.savedRoutes.take(6), key = { "saved-${it.id}" }) { route ->
                CommuterRouteShortcut(route, onOpen = { viewModel.activateRoute(route) })
            }
            item { TextButton(onClick = openSettings) { Text(stringResource(R.string.action_manage)) } }
        }
        item {
            OutlinedButton(onClick = openAgenda, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.commuter_agenda))
            }
        }
    }
}

@Composable
private fun CommuterRouteShortcut(route: SavedRoute, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (route.isPinned) Icons.Default.Star else Icons.Default.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(route.displayName(it.binario.app.data.Direction.OUTBOUND), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
