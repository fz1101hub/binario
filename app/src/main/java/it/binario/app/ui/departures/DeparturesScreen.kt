package it.binario.app.ui.departures

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.RailNoticesArea
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.UiMessage
import it.binario.app.data.Direction
import it.binario.app.data.RailTime
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainService
import it.binario.app.localized
import it.binario.app.maps.RouteLiveTrainsEntry
import it.binario.app.journeys.LastOfDayMarks
import it.binario.app.ui.common.DataStatusLine
import it.binario.app.ui.common.ReliabilityAggregateLines
import it.binario.app.ui.common.RoutePunctualityLines
import it.binario.app.ui.common.rememberNetworkReliability
import it.binario.app.ui.common.rememberReliabilityAggregateStrings
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityAggregates
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.LastOfDayChip
import it.binario.app.ui.common.rememberStaticDayDepartures
import it.binario.app.ui.common.DepartureListRow
import it.binario.app.ui.common.HeaderBlock
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.HeaderOverline
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.LocalWideLayout
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.planner.journeyLabel
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.ServiceGroup
import it.binario.app.ui.common.ServiceStatusBadge
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.DataState
import it.binario.app.ui.common.DataStateChip
import it.binario.app.ui.common.dataState
import it.binario.app.ui.common.localizedProviderLabel
import it.binario.app.ui.common.localizedAccessibleSummary
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedName
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.common.platformColor
import it.binario.app.ui.common.platformText
import it.binario.app.ui.common.presentedAsDemonstration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Partenze: the active route board and the switch to the station boards.

internal enum class OperationsSection { TRAINS, STATIONS }

@Composable
internal fun OperationsSection.localizedLabel(): String = stringResource(
    if (this == OperationsSection.TRAINS) R.string.operations_section_trains
    else R.string.operations_section_stations
)

/**
 * "Partenze": the board of the active route (one highlighted next departure, the rest compact) or the
 * station boards, with the network notices. The header carries the title and the switch between the two.
 *
 * @param stationRunDetailsInSheet false when a side pane shows the run details of a station board entry.
 * @param selectedServiceId the train whose detail is open beside the list on wide windows.
 */
@Composable
internal fun DeparturesScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    selectedSection: OperationsSection,
    onSectionSelected: (OperationsSection) -> Unit,
    openDetails: (TrainService) -> Unit,
    manageRoutes: () -> Unit,
    stationRunDetailsInSheet: Boolean = true,
    selectedServiceId: String? = null,
    /** Opens the train-number search for a train of the map of trains in motion that no board row lists. */
    openTrainNumber: (number: String, origin: String) -> Unit = { _, _ -> },
    /** Shows the route results after a search started here («Collegamenti diretti»). */
    openRouteResults: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize().testTag("departures-screen")) {
        ScreenHeader(
            title = stringResource(if (selectedSection == OperationsSection.TRAINS) R.string.departures_title else R.string.nav_departures),
            bottomPadding = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(
                        if (LocalWideLayout.current) BinarioTheme.colors.segmentTrack else BinarioTheme.colors.headerTonal,
                        RoundedCornerShape(50)
                    )
                    .padding(4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OperationsSection.entries.forEach { section ->
                    OperationsSectionCard(
                        modifier = Modifier.weight(1f).testTag(
                            if (section == OperationsSection.TRAINS) "departures-section-trains" else "departures-section-stations"
                        ),
                        section = section,
                        selected = selectedSection == section,
                        onClick = { onSectionSelected(section) }
                    )
                }
            }
        }
        when (selectedSection) {
            OperationsSection.TRAINS -> RouteBoard(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                openDetails = openDetails,
                manageRoutes = manageRoutes,
                selectedServiceId = selectedServiceId,
                openTrainNumber = openTrainNumber
            )
            OperationsSection.STATIONS -> StationBoardScreen(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                runDetailsInSheet = stationRunDetailsInSheet,
                openTrainNumber = openTrainNumber,
                openRouteResults = openRouteResults
            )
        }
    }
}

@Composable
private fun RouteBoard(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openDetails: (TrainService) -> Unit,
    manageRoutes: () -> Unit,
    selectedServiceId: String?,
    openTrainNumber: (number: String, origin: String) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.reconcileWidgetState() }
    var showRoutePicker by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf<String?>(null) }
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
    val listState = viewModel.dataState()
    val locale = LocalConfiguration.current.locales[0]
    // Train types hidden at the departure station of the route (Partenze only; unknown types always stay).
    val boardStation = viewModel.activeRoute.origin(viewModel.direction).takeIf { viewModel.hasActiveRoute }
    val (excludedTypes, setExcludedTypes) = rememberTrainTypeExclusions(boardStation?.id)
    val allServices = viewModel.homeServices()
    val services = PartenzeTrainTypes.visible(allServices, excludedTypes, PartenzeTrainTypes::groupOf)
    val nextService = services.firstOrNull()
    val laterServices = services.drop(1)
    val groups = ServiceGroup.chipsFor(laterServices)
    val group = groupName?.let { name -> ServiceGroup.entries.firstOrNull { it.name == name } }?.takeIf { it in groups }
    val shownLater = JourneyTimes.filtered(laterServices, group)
    // "Ultimo di oggi" and "Non ci sono più treni oggi" from the static timetable of the packs, never from live data.
    val today = LocalDate.now(RailTime.defaultZone)
    val boardDestination = viewModel.activeRoute.destination(viewModel.direction).takeIf { viewModel.hasActiveRoute }
    val staticDay by rememberStaticDayDepartures(boardStation, boardDestination, today)
    fun lastOfDay(service: TrainService): Boolean = LastOfDayMarks.isLast(
        staticDay, service.number,
        service.scheduledDepartureDateTime ?: runCatching { LocalDateTime.of(today, LocalTime.parse(service.scheduledDeparture.trim())) }.getOrNull()
    )
    val firstTomorrow = LastOfDayMarks.firstNextDay(staticDay, LocalDateTime.now(RailTime.defaultZone))
    // «Questa tratta: …» from the embedded network statistics; never on demonstration data.
    val reliabilityStats = rememberNetworkReliability().takeUnless { isDemonstration }
    val aggregateStrings = rememberReliabilityAggregateStrings()
    val routePunctuality = remember(reliabilityStats, boardStation, boardDestination, aggregateStrings) {
        reliabilityStats?.let { stats ->
            NetworkReliabilityAggregates.route(stats, NetworkReliability.stationCode(boardStation), NetworkReliability.stationCode(boardDestination))
                ?.let { ReliabilityAggregateLines.route(it, aggregateStrings) }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("route-board"),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            BoardRouteHeader(
                route = viewModel.activeRoute,
                direction = viewModel.direction,
                onSwitchDirection = viewModel::switchDirection,
                onChooseRoute = { showRoutePicker = true },
                bottomPadding = if (nextService != null) 12.dp else 20.dp,
                fromSearch = viewModel.activeRouteIsSearch,
                // A planned search shows its date and time, with the way back to the next trains from now.
                plannedFrom = if (viewModel.journeyFollowsCurrentTime) null else journeyLabel(viewModel.journeyQuery, locale),
                onBackToNow = viewModel::followCurrentTime
            )
        }
        if (nextService != null) {
            item(key = "next-${nextService.id}") {
                HeaderOverlapBox {
                    HeroDepartureCard(
                        nextService,
                        isDemonstration,
                        heading = if (viewModel.journeyFollowsCurrentTime) null else stringResource(R.string.departures_first_departure),
                        onOpen = { openDetails(nextService) },
                        modifier = Modifier.padding(horizontal = ScreenMargin),
                        selected = nextService.id == selectedServiceId,
                        lastOfDay = lastOfDay(nextService),
                        listState = listState
                    )
                }
            }
        }
        item {
            DataStatusLine(
                state = listState,
                freshness = viewModel.snapshot?.freshness,
                provider = viewModel.localizedProviderLabel(),
                confidence = viewModel.localizedQualityLabel(),
                refreshedAt = viewModel.lastRefreshEpochMillis(),
                message = viewModel.refreshMessage
            )
        }
        // "Treni in movimento": the route's trains on a map, at positions estimated from the timetable.
        if (boardStation != null && boardDestination != null) {
            item(key = "live-train-map") {
                RouteLiveTrainsEntry(
                    viewModel = viewModel,
                    origin = boardStation,
                    destination = boardDestination,
                    onOpenService = openDetails,
                    onOpenTrainNumber = openTrainNumber,
                    modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 4.dp).testTag("route-board-live-map")
                )
            }
        }
        if (reliabilityStats != null && routePunctuality != null) {
            item(key = "route-punctuality") {
                RoutePunctualityLines(
                    routePunctuality,
                    windowEnd = reliabilityStats.windowEnd,
                    modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp).testTag("route-board-punctuality")
                )
            }
        }
        firstTomorrow?.let { first ->
            item(key = "no-more-today") {
                InfoNoticeCard(
                    text = stringResource(R.string.journey_no_more_today, first.departure.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))),
                    modifier = Modifier.testTag("route-board-no-more-today")
                )
            }
        }
        if (boardStation != null && allServices.isNotEmpty()) {
            item(key = "train-types") {
                TrainTypeFilterControl(
                    stationName = boardStation.name,
                    excluded = excludedTypes,
                    hiddenCount = allServices.size - services.size,
                    onChange = setExcludedTypes
                )
            }
        }
        if (nextService != null) {
            if (laterServices.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.departures_other_services), Modifier.padding(top = 4.dp)) }
                if (groups.isNotEmpty()) {
                    item {
                        CategoryChips(groups = groups, selected = group, onSelect = { groupName = it?.name })
                    }
                }
                items(shownLater, key = { it.id }) { service ->
                    DepartureListRow(
                        service, isDemonstration = isDemonstration, selected = service.id == selectedServiceId,
                        lastOfDay = lastOfDay(service), listState = listState, onOpen = { openDetails(service) }
                    )
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
        // Works and interruptions the network notices name on this saved route (none: nothing shown).
        if (viewModel.hasActiveRoute && !viewModel.activeRouteIsSearch) item(key = "route-works") {
            RouteWorksSection(
                routes = listOf(viewModel.activeRoute),
                catalogue = viewModel.stations,
                title = R.string.route_works_title_route,
                showWhenNone = false
            )
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

/** "Tutte / Regionali / Intercity / Frecce": only the groups present among the listed trains. */
@Composable
private fun CategoryChips(groups: List<ServiceGroup>, selected: ServiceGroup?, onSelect: (ServiceGroup?) -> Unit) {
    val colors = BinarioTheme.colors
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = colors.segmentTrack,
        labelColor = colors.onSegmentTrack,
        selectedContainerColor = colors.segmentSelected,
        selectedLabelColor = colors.onSegmentSelected
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("departures-category-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = ScreenMargin, vertical = 4.dp)
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.filter_all_services), fontWeight = FontWeight.Bold) }, colors = chipColors, border = null)
        }
        items(groups) { group ->
            FilterChip(selected = selected == group, onClick = { onSelect(group) }, label = { Text(group.localizedLabel()) }, colors = chipColors, border = null)
        }
    }
}

/**
 * The only highlighted service of the board: the next departure, as the white card of the render ("Prossima
 * corsa", the big time, destination, train, status pill, platform and "Dettagli"). Demonstration data shows
 * the timetable alone, never a delay, an expected time or a platform as if they were real.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroDepartureCard(
    service: TrainService,
    isDemonstration: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Prossima corsa" by default; a planned board starts with "Prima corsa" of its date and time. */
    heading: String? = null,
    selected: Boolean = false,
    /** "Ultimo di oggi" from the static timetable. */
    lastOfDay: Boolean = false,
    /** The state of the board: a run of a saved copy is labelled as such, not as a timetable. */
    listState: DataState? = null
) {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val savedCopy = listState == DataState.SAVED_COPY && !shown.operationalStatusVerified
    val heading = heading ?: stringResource(R.string.departures_next_departure)
    val serviceState = if (shown.operationalStatusVerified) {
        stringResource(R.string.service_state_live, shown.status.localizedLabel())
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val summary = stringResource(R.string.accessibility_open_service, shown.localizedAccessibleSummary(), serviceState)
    val description = listOfNotNull(
        heading,
        if (isDemonstration) stringResource(R.string.data_status_demo) else null,
        summary,
        if (savedCopy) stringResource(R.string.data_chip_saved_copy) else null,
        if (lastOfDay) stringResource(R.string.journey_last_of_day) else null,
        if (selected) stringResource(R.string.accessibility_row_selected) else null
    ).joinToString(". ")
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("departures-hero")
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            }
    ) {
        // One column: at 200 % font scale the large time never squeezes the destination.
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(heading, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (savedCopy) DataStateChip(DataState.SAVED_COPY) else ServiceStatusBadge(shown)
            }
            Text(shown.scheduledDeparture, style = MaterialTheme.typography.displaySmall)
            if (shown.operationalStatusVerified && shown.expectedDeparture != shown.scheduledDeparture) {
                Text(stringResource(R.string.expected_time, shown.expectedDeparture), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Text(shown.destination, style = MaterialTheme.typography.titleLarge)
            if (lastOfDay) LastOfDayChip()
            Text(
                stringResource(R.string.service_operator_category_number, shown.displayOperator, shown.trainCategory.localizedLabel(), shown.number),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isDemonstration) {
                Text(stringResource(R.string.data_status_demo), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            }
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // A demonstration catalogue has no platform: a dash, never a number.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            platformText(shown.platform, shown.scheduledPlatform, long = true)
                                ?: stringResource(R.string.platform_label, "—"),
                            style = MaterialTheme.typography.labelLarge,
                            color = platformColor(shown.platform, shown.scheduledPlatform, MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.action_details), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * One segment of the "Treni | Stazioni" switch in the Partenze header: the selected one is filled and
 * announced as selected, the label always says which board it opens.
 */
@Composable
internal fun OperationsSectionCard(
    modifier: Modifier,
    section: OperationsSection,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = section.localizedLabel()
    val selectionState = stringResource(
        if (selected) R.string.accessibility_section_selected else R.string.accessibility_section_not_selected
    )
    val subtitle = stringResource(
        if (section == OperationsSection.TRAINS) R.string.operations_section_trains_subtitle
        else R.string.operations_section_stations_subtitle
    )
    val icon = if (section == OperationsSection.TRAINS) Icons.Default.Train else Icons.Default.Route
    val colors = BinarioTheme.colors
    val flat = LocalWideLayout.current
    val (container, content) = when {
        selected && flat -> colors.segmentSelected to colors.onSegmentSelected
        selected -> colors.headerSelected to colors.onHeaderSelected
        flat -> Color.Transparent to colors.onSegmentTrack
        else -> Color.Transparent to colors.onHeaderTonal
    }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(container, RoundedCornerShape(50))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                stateDescription = selectionState
                contentDescription = "$label. $subtitle"
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/**
 * "TRATTA ATTIVA" (or "RICERCA DA PIANIFICA" for a route only searched in the planner), the route, the time the board
 * starts from ("Adesso", or the planned "mar 4 nov · dalle 07:30" with "Torna ad adesso") and the two commands,
 * continuing the green header of Partenze.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardRouteHeader(
    route: SavedRoute,
    direction: Direction,
    onSwitchDirection: () -> Unit,
    onChooseRoute: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    fromSearch: Boolean = false,
    plannedFrom: String? = null,
    onBackToNow: () -> Unit = {}
) {
    val locale = LocalConfiguration.current.locales[0]
    HeaderBlock(topPadding = 8.dp, bottomPadding = bottomPadding) {
        HeaderOverline(stringResource(if (fromSearch) R.string.departures_route_from_search else R.string.shortcut_active_route).uppercase(locale))
        Text(ltrUnit(route.localizedDisplayName(direction)), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp).testTag("departures-time-context")
        ) {
            Pill(plannedFrom ?: stringResource(R.string.planner_time_now), icon = Icons.Default.Schedule)
            if (plannedFrom != null) {
                TonalButton(
                    text = stringResource(R.string.departures_back_to_now),
                    onClick = onBackToNow,
                    leadingIcon = Icons.Default.Update,
                    onHeader = true,
                    modifier = Modifier.testTag("departures-back-to-now")
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TonalButton(
                text = stringResource(R.string.action_direction),
                onClick = onSwitchDirection,
                leadingIcon = Icons.Default.SwapHoriz,
                onHeader = true,
                modifier = Modifier.testTag("departures-switch-direction")
            )
            TonalButton(
                text = stringResource(R.string.action_change_route),
                onClick = onChooseRoute,
                leadingIcon = Icons.Default.Route,
                onHeader = true,
                modifier = Modifier.testTag("departures-choose-route")
            )
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
                        Text(ltrUnit(route.localizedDisplayName(Direction.OUTBOUND)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
