package it.binario.app.ui.foryou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.ActiveTrip
import it.binario.app.R
import it.binario.app.RecentRoute
import it.binario.app.StationMonitor
import it.binario.app.TrainViewModel
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.NavigationRow
import it.binario.app.ui.common.RowDivider
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.SegmentedControl
import it.binario.app.ui.common.StationField
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedName
import it.binario.app.ui.common.localizedSummary
import it.binario.app.ui.departures.StationPreferenceRow

// Per te: "Le tue tratte" (favourite and recent routes), the agenda, favourite stations, the trip being
// followed, monitored trains and the way to "Impostazioni". Everything is stored on this device.

private enum class RoutesTab { FAVOURITES, RECENT }

/**
 * "Per te" as in the approved render: "Le tue tratte" with Preferite / Recenti and "Nuova tratta", then the
 * other personal sections. The settings that used to follow them are grouped in "Impostazioni".
 */
@Composable
internal fun ForYouScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openRoute: (SavedRoute) -> Unit,
    openRecent: (RecentRoute) -> Unit,
    openAgenda: () -> Unit,
    openStationBoard: (Station) -> Unit,
    openSettings: () -> Unit
) {
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(RoutesTab.FAVOURITES.name) }
    val tab = RoutesTab.valueOf(tabName)
    if (showComposer) {
        RouteComposerSheet(viewModel = viewModel, onDismiss = { showComposer = false })
    }
    LazyColumn(modifier = modifier.fillMaxSize().testTag("for-you-screen"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { ScreenHeader(title = stringResource(R.string.for_you_routes_title), bottomPadding = 8.dp) }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        SegmentedControl(
                            options = RoutesTab.entries,
                            selected = tab,
                            onSelect = { tabName = it.name },
                            label = { stringResource(if (it == RoutesTab.FAVOURITES) R.string.for_you_tab_favourites else R.string.for_you_tab_recent) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            tagOf = { if (it == RoutesTab.FAVOURITES) "for-you-tab-favourites" else "for-you-tab-recent" }
                        )
                        Spacer(Modifier.height(8.dp))
                        when (tab) {
                            RoutesTab.FAVOURITES -> {
                                if (viewModel.savedRoutes.isEmpty()) {
                                    Text(
                                        stringResource(R.string.settings_personal_journeys_description),
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                viewModel.savedRoutes.forEachIndexed { index, route ->
                                    if (index > 0) RowDivider()
                                    SavedRouteRow(
                                        route = route,
                                        isActive = route.id == viewModel.activeRoute.id,
                                        onOpen = { openRoute(route) },
                                        onPin = { viewModel.toggleRoutePin(route) },
                                        onMove = { delta -> viewModel.moveRoute(route, delta) },
                                        canMoveUp = viewModel.canMoveRoute(route, -1),
                                        canMoveDown = viewModel.canMoveRoute(route, 1),
                                        onRemove = { viewModel.removeRoute(route) },
                                        canRemove = viewModel.savedRoutes.size > 1
                                    )
                                }
                            }
                            RoutesTab.RECENT -> {
                                if (viewModel.recentRoutes.isEmpty()) {
                                    Text(
                                        stringResource(R.string.for_you_no_recent),
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                viewModel.recentRoutes.forEachIndexed { index, recent ->
                                    if (index > 0) RowDivider()
                                    NavigationRow(
                                        title = recent.label,
                                        detail = stringResource(R.string.planner_recent_search),
                                        icon = Icons.Default.History,
                                        onClick = { openRecent(recent) }
                                    )
                                }
                            }
                        }
                        TonalButton(
                            text = stringResource(R.string.route_composer_title),
                            onClick = { showComposer = true },
                            leadingIcon = Icons.Default.Add,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("for-you-new-route")
                        )
                    }
                }
            }
        }
        item {
            BinarioCard(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp)) {
                NavigationRow(
                    title = stringResource(R.string.screen_title_agenda),
                    detail = stringResource(R.string.screen_subtitle_agenda),
                    icon = Icons.Default.CalendarMonth,
                    onClick = openAgenda,
                    modifier = Modifier.testTag("for-you-agenda")
                )
                RowDivider()
                NavigationRow(
                    title = stringResource(R.string.settings_title),
                    detail = stringResource(R.string.settings_row_detail),
                    icon = Icons.Default.Settings,
                    onClick = openSettings,
                    modifier = Modifier.testTag("for-you-settings")
                )
            }
        }
        if (viewModel.favouriteStations.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.station_board_favourites)) }
            item {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        viewModel.favouriteStations.forEach { station ->
                            StationPreferenceRow(
                                station = station,
                                favourite = true,
                                onSelect = { openStationBoard(station) },
                                onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                            )
                        }
                    }
                }
            }
        }
        item {
            BinarioCard(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp)) {
                PersonalTravelPanel(
                    activeTrip = viewModel.activeTrip,
                    stationMonitors = viewModel.stationMonitors,
                    onAdvanceTrip = viewModel::advanceActiveTrip,
                    onEndTrip = viewModel::endActiveTrip,
                    onRemoveStationMonitor = viewModel::removeStationMonitor
                )
            }
        }
        if (viewModel.monitoredServices.isNotEmpty()) {
            item {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_monitored_trains), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.settings_monitored_trains_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        viewModel.monitoredServices.forEach { monitor ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(stringResource(R.string.train_number_label, monitor.number), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(monitor.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.removeServiceMonitoring(monitor.number) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_remove_train_monitoring, monitor.number))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * A saved route of "Preferite": tapping it makes it active and opens its departures; the star pins it (the
 * pinned routes come first, also in the widget); the menu moves or deletes it.
 */
@Composable
private fun SavedRouteRow(
    route: SavedRoute,
    isActive: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onMove: (Int) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = route.localizedName()
    val detail = listOfNotNull(
        route.localizedDisplayName(Direction.OUTBOUND).takeIf { it != name },
        if (isActive) stringResource(R.string.shortcut_active_route) else null,
        if (route.isPinned) stringResource(R.string.route_pinned) else null
    ).joinToString(" · ")
    NavigationRow(
        title = name,
        detail = detail.ifBlank { null },
        icon = if (route.isPinned) Icons.Default.Star else Icons.Default.Route,
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPin) {
                    Icon(
                        if (route.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(if (route.isPinned) R.string.accessibility_unpin_route else R.string.accessibility_pin_route),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.accessibility_route_actions, name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accessibility_move_route_up, name)) },
                            onClick = { menuOpen = false; onMove(-1) },
                            enabled = canMoveUp
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accessibility_move_route_down, name)) },
                            onClick = { menuOpen = false; onMove(1) },
                            enabled = canMoveDown
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.accessibility_delete_route, name)) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            onClick = { menuOpen = false; onRemove() },
                            enabled = canRemove
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun PersonalTravelPanel(
    activeTrip: ActiveTrip?,
    stationMonitors: List<StationMonitor>,
    onAdvanceTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onRemoveStationMonitor: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.personal_travel_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        activeTrip?.let { trip ->
            Text(stringResource(R.string.personal_travel_active, trip.trainNumber), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val nextStopLabel = trip.nextStop?.let { stop ->
                stop.platform?.let { platform ->
                    stringResource(R.string.personal_travel_next_stop_platform, stop.station, stop.expected, platform)
                } ?: stringResource(R.string.personal_travel_next_stop, stop.station, stop.expected)
            } ?: stringResource(R.string.personal_travel_completed)
            Text(nextStopLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdvanceTrip, enabled = !trip.isComplete) { Text(stringResource(R.string.action_stop_reached)) }
                TextButton(onClick = onEndTrip) { Text(stringResource(R.string.action_end)) }
            }
        } ?: Text(stringResource(R.string.personal_travel_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stationMonitors.isNotEmpty()) {
            Text(stringResource(R.string.personal_travel_monitored_stations), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            stationMonitors.forEach { monitor ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(monitor.localizedSummary(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onRemoveStationMonitor(monitor.stationId) }) { Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.accessibility_remove_station_monitoring, monitor.stationName)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteComposerSheet(viewModel: TrainViewModel, onDismiss: () -> Unit) {
    var originText by rememberSaveable { mutableStateOf("") }
    var destinationText by rememberSaveable { mutableStateOf("") }
    var routeName by rememberSaveable { mutableStateOf("") }
    val origin = viewModel.stationFromName(originText)
    val destination = viewModel.stationFromName(destinationText)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.route_composer_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.route_composer_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            StationField(
                            label = stringResource(R.string.planner_origin),
                            value = originText,
                            onValueChange = { originText = it; viewModel.updateStationSuggestions("route-composer-origin", it) },
                            matches = viewModel.stationSuggestions("route-composer-origin"),
                            onSelect = { originText = it.name; viewModel.clearStationSuggestions("route-composer-origin") }
                        )
            TextButton(onClick = { val current = originText; originText = destinationText; destinationText = current }) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.route_swap_stations))
            }
            StationField(
                            label = stringResource(R.string.planner_destination),
                            value = destinationText,
                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("route-composer-destination", it) },
                            matches = viewModel.stationSuggestions("route-composer-destination"),
                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("route-composer-destination") }
                        )
            OutlinedTextField(value = routeName, onValueChange = { routeName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.route_name_optional)) }, placeholder = { Text(stringResource(R.string.route_name_example)) })
            Button(
                onClick = { if (viewModel.createRoute(origin!!, destination!!, routeName)) onDismiss() },
                enabled = origin != null && destination != null && origin != destination,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.route_save_and_activate)) }
        }
    }
}

