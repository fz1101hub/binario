package it.binario.app.ui.departures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.RailNoticesArea
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.UiMessage
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainService
import it.binario.app.localized
import it.binario.app.ui.common.BoardColumnHeader
import it.binario.app.ui.common.DataStatusLine
import it.binario.app.ui.common.DepartureListRow
import it.binario.app.ui.common.ServiceStatusBadge
import it.binario.app.ui.common.isCachedCopy
import it.binario.app.ui.common.localizedAccessibleSummary
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedName
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.common.presentedAsDemonstration

// Partenze: the active route board and the switch to the station boards.

internal enum class OperationsSection { TRAINS, STATIONS }

@Composable
internal fun OperationsSection.localizedLabel(): String = stringResource(
    if (this == OperationsSection.TRAINS) R.string.operations_section_trains
    else R.string.operations_section_stations
)

/**
 * "Partenze": the board of the active route (one highlighted next departure, the rest compact) or the
 * station boards, with the network notices.
 *
 * @param stationRunDetailsInSheet false when a side pane shows the run details of a station board entry.
 */
@Composable
internal fun DeparturesScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    selectedSection: OperationsSection,
    onSectionSelected: (OperationsSection) -> Unit,
    openDetails: (TrainService) -> Unit,
    manageRoutes: () -> Unit,
    stationRunDetailsInSheet: Boolean = true
) {
    Column(modifier = modifier.fillMaxSize().testTag("departures-screen")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OperationsSectionCard(
                modifier = Modifier.weight(1f).testTag("departures-section-trains"),
                section = OperationsSection.TRAINS,
                selected = selectedSection == OperationsSection.TRAINS,
                onClick = { onSectionSelected(OperationsSection.TRAINS) }
            )
            OperationsSectionCard(
                modifier = Modifier.weight(1f).testTag("departures-section-stations"),
                section = OperationsSection.STATIONS,
                selected = selectedSection == OperationsSection.STATIONS,
                onClick = { onSectionSelected(OperationsSection.STATIONS) }
            )
        }
        when (selectedSection) {
            OperationsSection.TRAINS -> RouteBoard(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                openDetails = openDetails,
                manageRoutes = manageRoutes
            )
            OperationsSection.STATIONS -> StationBoardScreen(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                runDetailsInSheet = stationRunDetailsInSheet
            )
        }
    }
}

@Composable
private fun RouteBoard(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openDetails: (TrainService) -> Unit,
    manageRoutes: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.reconcileWidgetState() }
    var showRoutePicker by rememberSaveable { mutableStateOf(false) }
    if (showRoutePicker) {
        RoutePickerSheet(
            routes = viewModel.savedRoutes,
            activeId = viewModel.activeRoute.id,
            onSelect = { route ->
                showRoutePicker = false
                // The direction shown stays the one the user chose.
                viewModel.activateRoute(route)
            },
            onManage = {
                showRoutePicker = false
                manageRoutes()
            },
            onDismiss = { showRoutePicker = false }
        )
    }
    val isDemonstration = viewModel.isDemonstrationData()
    val services = viewModel.homeServices()
    val nextService = services.firstOrNull()
    val laterServices = services.drop(1)
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("route-board"),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            BoardRouteHeader(
                route = viewModel.activeRoute,
                direction = viewModel.direction,
                refreshState = viewModel.refreshState,
                onSwitchDirection = viewModel::switchDirection,
                onRefresh = viewModel::refresh,
                onChooseRoute = { showRoutePicker = true }
            )
        }
        item {
            DataStatusLine(
                freshness = viewModel.snapshot?.freshness,
                provider = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable),
                confidence = viewModel.localizedQualityLabel(),
                refreshedAt = viewModel.lastRefreshEpochMillis(),
                isDemonstration = isDemonstration,
                isCachedCopy = viewModel.isCachedCopy(),
                message = viewModel.refreshMessage
            )
        }
        if (nextService != null) {
            item(key = "next-${nextService.id}") {
                HeroDepartureCard(nextService, isDemonstration, onOpen = { openDetails(nextService) })
            }
            if (laterServices.isNotEmpty()) {
                item { BoardColumnHeader() }
                items(laterServices, key = { it.id }) { service ->
                    DepartureListRow(service, isDemonstration = isDemonstration, onOpen = { openDetails(service) })
                }
            }
        } else {
            item {
                TodayEmptyState(
                    state = viewModel.refreshState,
                    message = viewModel.refreshMessage,
                    onRefresh = viewModel::refresh,
                    onRoutes = { showRoutePicker = true }
                )
            }
        }
        if (viewModel.savedRoutes.size > 1) {
            item { MonitoredRoutesStrip(viewModel.savedRoutes, viewModel.activeRoute.id, viewModel::activateRoute, manageRoutes) }
        }
        item { RailNoticesArea() }
        item {
            Text(
                text = stringResource(R.string.official_information_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

/**
 * The only highlighted service of the board: the next departure. Demonstration data shows the timetable
 * alone, never a delay, an expected time or a platform as if they were real.
 */
@Composable
internal fun HeroDepartureCard(service: TrainService, isDemonstration: Boolean, onOpen: () -> Unit) {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val heading = stringResource(R.string.departures_next_departure)
    val serviceState = if (shown.operationalStatusVerified) {
        stringResource(R.string.service_state_live, shown.status.localizedLabel())
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val summary = stringResource(R.string.accessibility_open_service, shown.localizedAccessibleSummary(), serviceState)
    val description = listOfNotNull(
        heading,
        if (isDemonstration) stringResource(R.string.data_status_demo) else null,
        summary
    ).joinToString(". ")
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("departures-hero")
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(shown.scheduledDeparture, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(shown.destination, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.service_operator_category_number, shown.displayOperator, shown.trainCategory.localizedLabel(), shown.number),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (shown.operationalStatusVerified && shown.expectedDeparture != shown.scheduledDeparture) {
                Text(stringResource(R.string.expected_time, shown.expectedDeparture), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ServiceStatusBadge(shown)
                Text(
                    shown.platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isDemonstration) {
                Text(stringResource(R.string.data_status_demo), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun OperationsSectionCard(
    modifier: Modifier,
    section: OperationsSection,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = section.localizedLabel()
    val subtitle = stringResource(
        if (section == OperationsSection.TRAINS) R.string.operations_section_trains_subtitle
        else R.string.operations_section_stations_subtitle
    )
    val selectionState = stringResource(
        if (selected) R.string.accessibility_section_selected else R.string.accessibility_section_not_selected
    )
    val icon = if (section == OperationsSection.TRAINS) Icons.Default.Train else Icons.Default.Route
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = selectionState },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardRouteHeader(
    route: SavedRoute,
    direction: Direction,
    refreshState: RefreshState,
    onSwitchDirection: () -> Unit,
    onRefresh: () -> Unit,
    onChooseRoute: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val directionUppercase = stringResource(if (direction == Direction.OUTBOUND) R.string.planner_outbound else R.string.planner_return)
                    val directionLabel = stringResource(if (direction == Direction.OUTBOUND) R.string.direction_outbound else R.string.direction_return)
                    Text(stringResource(R.string.board_header_title, directionUppercase), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(route.localizedDisplayName(direction), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.board_route_direction, route.localizedName(), directionLabel), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh_departures, refreshState.localizedLabel()))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSwitchDirection, modifier = Modifier.testTag("departures-switch-direction")) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_direction))
                }
                TextButton(onClick = onChooseRoute, modifier = Modifier.testTag("departures-choose-route")) {
                    Text(stringResource(R.string.action_change_route), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** Saved routes to make active from the board, without leaving "Partenze". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutePickerSheet(
    routes: List<SavedRoute>,
    activeId: String,
    onSelect: (SavedRoute) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .testTag("route-picker"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.route_picker_title),
                modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (routes.isEmpty()) {
                Text(
                    stringResource(R.string.route_picker_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            routes.forEach { route ->
                val active = route.id == activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(route) })
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = active, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(route.localizedName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(route.localizedDisplayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val labels = listOfNotNull(
                            if (active) stringResource(R.string.shortcut_active_route) else null,
                            if (route.isPinned) stringResource(R.string.route_pinned) else null
                        )
                        if (labels.isNotEmpty()) {
                            Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (route.isPinned) Icon(Icons.Default.Star, contentDescription = null)
                }
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.route_picker_manage))
            }
        }
    }
}

@Composable
private fun MonitoredRoutesStrip(
    routes: List<SavedRoute>,
    activeId: String,
    onActivate: (SavedRoute) -> Unit,
    onManage: () -> Unit
) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.monitored_routes_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text(stringResource(R.string.action_manage)) }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
        ) {
            items(routes.take(6), key = { it.id }) { route ->
                FilterChip(
                    selected = route.id == activeId,
                    onClick = { onActivate(route) },
                    label = { Text(route.localizedName(), maxLines = 1) },
                    leadingIcon = if (route.isPinned) ({ Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayEmptyState(state: RefreshState, message: UiMessage?, onRefresh: () -> Unit, onRoutes: () -> Unit) {
    val messageText = message?.localized()
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        Text(stringResource(if (state == RefreshState.LOADING) R.string.today_empty_loading else R.string.today_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.today_empty_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        messageText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            OutlinedButton(onClick = onRoutes) { Text(stringResource(R.string.action_change_route)) }
        }
    }
}
