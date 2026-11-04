package it.binario.app.ui.departures

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.binario.app.BikeSharingNearbySection
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.StationLineNoticesSection
import it.binario.app.StationMonitorMode
import it.binario.app.TrainViewModel
import it.binario.app.data.PlatformChange
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.urban.UrbanRealtimeFeeds
import it.binario.app.data.repository.StationBoardRunDetails
import it.binario.app.links.OfficialLinks
import it.binario.app.localized
import it.binario.app.requestSingleLocationFix
import it.binario.app.maps.GeoPoint
import it.binario.app.maps.NearbyStationsMapCard
import it.binario.app.maps.RouteMapPlanner
import it.binario.app.maps.StationMapCard
import it.binario.app.maps.rememberOpenMapStationIndex
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SearchInfoState
import it.binario.app.ui.common.StationField
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedProviderName
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.detail.StopTimelinePosition
import it.binario.app.ui.detail.StopTimelineRow
import it.binario.app.updateTimeMessage
import it.binario.app.journeys.LastOfDayMarks
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.OperatorNames
import it.binario.app.ui.common.LastOfDayChip
import it.binario.app.ui.common.StationPunctualityCard
import it.binario.app.ui.common.WheelchairMark
import it.binario.app.ui.common.rememberNetworkReliability
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityAggregates
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.ui.common.rememberStaticDayDepartures
import it.binario.app.ui.common.rememberWheelchairAccessibleStations
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Partenze: departures and arrivals of a station, with the run details of a board entry.

@Composable
internal fun StationBoardScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    runDetailsInSheet: Boolean = true,
    /** Shows the route results of a search to a destination of «Collegamenti diretti». */
    openRouteResults: () -> Unit = {}
) {
    LaunchedEffect(Unit) { viewModel.refreshStationBoardIfIdle() }
    // From 840 dp the run details sit in the side pane instead (StationBoardRunDetailsPane).
    if (runDetailsInSheet) viewModel.selectedBoardEntry?.let { selected ->
        StationBoardRunDetailsSheet(
            station = viewModel.stationBoardStation?.name.orEmpty(),
            entry = viewModel.stationBoardRunDetails?.entry ?: selected,
            kind = viewModel.stationBoardKind,
            details = viewModel.stationBoardRunDetails,
            state = viewModel.stationBoardRunRefreshState,
            onRefresh = viewModel::refreshStationBoardRun,
            onDismiss = viewModel::closeStationBoardRun
        )
    }
    var stationText by rememberSaveable(viewModel.stationBoardStation?.id) {
        mutableStateOf(viewModel.stationBoardStation?.name.orEmpty())
    }
    var locationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // In memory only (not saved state): the nearby map may mark it, nothing persists it.
    var nearbyPosition by remember { mutableStateOf<GeoPoint?>(null) }
    val mapStationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName)
    var monitorMode by rememberSaveable { mutableStateOf(StationMonitorMode.BOTH.name) }
    var directConnectionsOf by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current

    fun locateNearbyStations() {
        requestSingleLocationFix(context) { location ->
            if (location == null) {
                locationMessage = resources.getString(R.string.location_unavailable)
            } else {
                viewModel.updateNearbyStations(location.latitude, location.longitude)
                nearbyPosition = GeoPoint(location.latitude, location.longitude)
                locationMessage = resources.getString(R.string.location_sorted_not_saved)
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateNearbyStations()
        else locationMessage = resources.getString(R.string.location_permission_denied)
    }

    val selectedStation = viewModel.stationBoardStation?.takeIf { it.name.equals(stationText.trim(), ignoreCase = true) }
        ?: viewModel.stationFromName(stationText)
    val board = viewModel.stationBoard?.takeIf { current ->
        selectedStation != null &&
            current.station.id == selectedStation.id &&
            current.kind == viewModel.stationBoardKind
    }
    // Train types hidden at this station (on this device); a train of unknown type is never hidden.
    val (excludedTypes, setExcludedTypes) = rememberTrainTypeExclusions(board?.station?.id)
    val shownEntries = board?.entries.orEmpty().let { entries -> PartenzeTrainTypes.visible(entries, excludedTypes, PartenzeTrainTypes::groupOf) }
    // "Ultimo di oggi" per direction and "Non ci sono più treni oggi" from the static timetable (departures only).
    val today = LocalDate.now(BOARD_ZONE)
    val staticDay by rememberStaticDayDepartures(board?.takeIf { it.kind == BoardKind.DEPARTURES }?.station, null, today)
    val firstTomorrow = LastOfDayMarks.firstNextDay(staticDay, LocalDateTime.now(BOARD_ZONE))
    // «Puntualità degli arrivi» from the embedded network statistics: every observed train arriving at the station.
    val reliabilityStats = rememberNetworkReliability().takeUnless { viewModel.isDemonstrationData() }
    val reliabilityStations = remember(viewModel.stations) { NetworkReliabilityStations(viewModel.stations) }
    val stationPunctuality = remember(reliabilityStats, selectedStation?.id) {
        reliabilityStats?.let { NetworkReliabilityAggregates.station(it, NetworkReliability.stationCode(selectedStation)) }
    }
    val accessibleStations = rememberWheelchairAccessibleStations(
        enabled = selectedStation != null || viewModel.favouriteStations.isNotEmpty() || viewModel.nearbyStations.isNotEmpty()
    )

    // «Collegamenti diretti» of the chosen station; a destination searches the route from it, today from now.
    selectedStation?.takeIf { it.id == directConnectionsOf }?.let { origin ->
        DirectConnectionsSheet(
            station = origin,
            onSearch = { destination, date ->
                directConnectionsOf = null
                val target = viewModel.stations.firstOrNull { it.id == destination.stationId }
                    ?: Station(id = destination.stationId, name = destination.name, city = destination.name)
                val (day, time) = DirectConnectionSearch.departure(date, destination.firstDepartureMinute, today)
                if (viewModel.searchRoute(origin, target, day, time)) openRouteResults()
            },
            onDismiss = { directConnectionsOf = null }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("station-board-list"),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            HeaderOverlapBox {
            BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.station_board_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StationField(
                    label = stringResource(R.string.station_label),
                    value = stationText,
                    onValueChange = { stationText = it; viewModel.updateStationSuggestions("station-board", it) },
                    matches = viewModel.stationSuggestions("station-board"),
                    onSelect = {
                        stationText = it.name
                        viewModel.clearStationSuggestions("station-board")
                        viewModel.selectStationBoardStation(it)
                    }
                )

                selectedStation?.let { station ->
                    StationPreferenceRow(
                        station = station,
                        favourite = viewModel.isStationFavourite(station),
                        wheelchairAccessible = station.id in accessibleStations,
                        onSelect = {
                            stationText = station.name
                            viewModel.selectStationBoardStation(station)
                        },
                        onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                    )

                    Text(stringResource(R.string.station_board_section), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BoardKind.entries) { kind ->
                            FilterChip(
                                selected = viewModel.stationBoardKind == kind,
                                onClick = { viewModel.loadStationBoard(station, kind) },
                                enabled = viewModel.hasStationBoardProviders,
                                label = { Text(stringResource(if (kind == BoardKind.DEPARTURES) R.string.station_board_departures else R.string.station_board_arrivals)) }
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.loadStationBoard(station, viewModel.stationBoardKind) },
                        enabled = viewModel.hasStationBoardProviders &&
                            viewModel.stationBoardRefreshState != RefreshState.LOADING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (board == null) R.string.station_board_load else R.string.station_board_refresh))
                    }

                    if (viewModel.stationBoardRefreshState == RefreshState.LOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.station_board_local_monitoring), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StationMonitorMode.entries) { mode ->
                            FilterChip(
                                selected = monitorMode == mode.name,
                                onClick = { monitorMode = mode.name },
                                label = { Text(mode.localizedLabel()) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.toggleStationMonitor(station, StationMonitorMode.valueOf(monitorMode)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(if (viewModel.isStationMonitored(station)) R.string.station_board_stop_monitoring else R.string.station_board_start_monitoring))
                    }
                    OutlinedButton(
                        onClick = { directConnectionsOf = station.id },
                        modifier = Modifier.fillMaxWidth().testTag("station-direct-connections")
                    ) {
                        Text(stringResource(R.string.direct_connections_title))
                    }
                    StationMapCard(station, mapStationIndex, onOpenStation = {
                        stationText = it.name
                        viewModel.selectStationBoardStation(it)
                    })
                }

                val findNearbyStationsDescription = stringResource(R.string.accessibility_find_nearby_stations)
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateNearbyStations()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = findNearbyStationsDescription
                    }
                ) {
                    Icon(Icons.Default.Route, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.station_board_find_nearby))
                }

                locationMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
            }
        }

        // Shared bikes near the chosen station, on request only (GBFS of the systems serving that place).
        selectedStation?.let { station ->
            item(key = "bike-sharing-${station.id}") { BikeSharingNearbySection(station) }
            // RFI's Sala Blu: its accessibility page and national numbers, for the stations of the Italian network.
            if (OfficialLinks.SalaBlu.appliesTo(station)) item(key = "sala-blu-${station.id}") { SalaBluAssistanceSection(station) }
            if (reliabilityStats != null && stationPunctuality != null) {
                item(key = "station-punctuality-${station.id}") {
                    StationPunctualityCard(stationPunctuality, reliabilityStats.windowEnd, originName = reliabilityStations::name)
                }
            }
        }

        if (!viewModel.hasStationBoardProviders) {
            item {
                SearchInfoState(
                    stringResource(R.string.station_board_live_disabled_title),
                    stringResource(R.string.station_board_live_disabled_message)
                )
            }
        } else {
            when {
                viewModel.stationBoardRefreshState == RefreshState.LOADING && board?.entries.isNullOrEmpty() -> item {
                    SearchInfoState(stringResource(R.string.station_board_loading_title), stringResource(R.string.station_board_loading_message))
                }
                viewModel.stationBoardRefreshState == RefreshState.ERROR && board == null -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_unavailable_title),
                        viewModel.stationBoardMessages.firstOrNull()?.localized() ?: stringResource(R.string.station_board_unavailable_message)
                    )
                }
                board != null && board.entries.isEmpty() -> item {
                    SearchInfoState(
                        stringResource(R.string.station_board_empty_title),
                        viewModel.stationBoardMessages.firstOrNull()?.localized() ?: stringResource(R.string.station_board_empty_message)
                    )
                }
                board != null -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                stringResource(if (board.kind == BoardKind.DEPARTURES) R.string.station_board_departures_uppercase else R.string.station_board_arrivals_uppercase),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                board.providerNames.map { localizedProviderName(it) }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // A metro or tram stop: the city network's realtime feed and when it last observed the runs.
                            UrbanRealtimeFeeds.observation(board.entries)?.let { (source, observedAt) ->
                                Text(
                                    stringResource(R.string.urban_realtime_source, source, observedAt.atZone(BOARD_ZONE).format(BOARD_TIME_FORMATTER)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("station-board-urban-realtime")
                                )
                            }
                            viewModel.stationBoardMessages.forEach { message ->
                                Text(
                                    message.localized(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    firstTomorrow?.let { first ->
                        item(key = "no-more-today") {
                            InfoNoticeCard(
                                text = stringResource(R.string.journey_no_more_today, first.departure.format(BOARD_TIME_FORMATTER)),
                                modifier = Modifier.testTag("station-board-no-more-today")
                            )
                        }
                    }
                    item { StationLineNoticesSection(board.station) }
                    item(key = "train-types") {
                        TrainTypeFilterControl(
                            stationName = board.station.name,
                            excluded = excludedTypes,
                            hiddenCount = board.entries.size - shownEntries.size,
                            onChange = setExcludedTypes
                        )
                    }
                    items(
                        shownEntries.take(60),
                        key = { entry ->
                            listOf(
                                entry.runId.value,
                                entry.scheduledTime.toInstant().toEpochMilli().toString(),
                                entry.destinationOrOrigin.value
                            ).joinToString("|")
                        }
                    ) { entry ->
                        val selected = entry == viewModel.selectedBoardEntry
                        Box(
                            Modifier
                                .padding(horizontal = ScreenMargin, vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(16.dp)
                                )
                        ) {
                            // A metro or tram run has no run details source: its row shows everything known.
                            StationBoardEntryRow(
                                entry, board.kind,
                                onOpen = if (entry.serviceKind == ServiceKind.URBAN) null else {
                                    { viewModel.openStationBoardRun(entry) }
                                },
                                selected = selected,
                                lastOfDay = board.kind == BoardKind.DEPARTURES && LastOfDayMarks.isLast(
                                    staticDay, entry.publicNumber, entry.scheduledTime.withZoneSameInstant(BOARD_ZONE).toLocalDateTime()
                                )
                            )
                        }
                    }
                }
            }
        }

        if (viewModel.favouriteStations.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.station_board_favourites),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            items(viewModel.favouriteStations, key = { "fav-${it.id}" }) { station ->
                StationPreferenceRow(
                    station = station,
                    favourite = true,
                    wheelchairAccessible = station.id in accessibleStations,
                    onSelect = {
                        stationText = station.name
                        viewModel.selectStationBoardStation(station)
                    },
                    onToggleFavourite = { viewModel.toggleStationFavourite(station) }
                )
            }
        }

        if (viewModel.nearbyStations.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.station_board_nearby),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            item(key = "nearby-map") {
                NearbyStationsMapCard(viewModel.nearbyStations, nearbyPosition, onOpenStation = {
                    stationText = it.name
                    viewModel.selectStationBoardStation(it)
                }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            items(viewModel.nearbyStations, key = { "near-${it.station.id}" }) { nearby ->
                StationPreferenceRow(
                    station = nearby.station,
                    favourite = viewModel.isStationFavourite(nearby.station),
                    wheelchairAccessible = nearby.station.id in accessibleStations,
                    distanceLabel = stringResource(R.string.distance_kilometers, nearby.distanceKm),
                    onSelect = {
                        stationText = nearby.station.name
                        viewModel.selectStationBoardStation(nearby.station)
                    },
                    onToggleFavourite = { viewModel.toggleStationFavourite(nearby.station) }
                )
            }
        }
    }
}

@Composable
internal fun StationBoardEntryRow(
    entry: StationBoardEntry,
    kind: BoardKind,
    onOpen: (() -> Unit)? = null,
    selected: Boolean = false,
    /** "Ultimo di oggi" for its direction, from the static timetable. */
    lastOfDay: Boolean = false
) {
    val scheduled = entry.scheduledTime.withZoneSameInstant(BOARD_ZONE)
    val expected = entry.expectedTime?.value?.withZoneSameInstant(BOARD_ZONE)
    val delayedMinutes = expected?.let {
        java.time.Duration.between(scheduled, it).toMinutes().coerceAtLeast(0)
    } ?: 0L
    val endpoint = entry.destinationOrOriginLabel?.takeIf(String::isNotBlank)
        ?: entry.destinationOrOrigin.value
    val platform = entry.platform?.value?.takeIf(String::isNotBlank)
    val directionLabel = stringResource(
        if (kind == BoardKind.DEPARTURES) R.string.station_board_departure_to
        else R.string.station_board_arrival_from,
        endpoint
    )
    val scheduledLabel = stringResource(
        R.string.station_board_scheduled_time,
        scheduled.format(BOARD_TIME_FORMATTER)
    )
    val statusLabel = stationBoardStatusAnnouncement(entry)
    val platformChange = PlatformChange.of(platform, entry.scheduledPlatform?.value)
    val platformLabel = platformChange?.let { stringResource(R.string.platform_changed_accessible, it.actual, it.scheduled) }
        ?: platform?.let { stringResource(R.string.station_board_platform_accessible, it) }
        ?: stringResource(R.string.station_board_platform_unavailable)
    val trainLabel = entry.publicNumber?.takeIf(String::isNotBlank)
        ?.let { stringResource(R.string.station_board_train_number, it) }
        .orEmpty()
    val accessibilityDescription = stringResource(
        R.string.station_board_entry_accessibility,
        directionLabel,
        scheduledLabel,
        statusLabel,
        platformLabel,
        trainLabel
    ) + if (lastOfDay) ". " + stringResource(R.string.journey_last_of_day) else ""
    val selectedState = if (selected) stringResource(R.string.accessibility_row_selected) else null

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .clearAndSetSemantics {
                contentDescription = accessibilityDescription
                selectedState?.let { stateDescription = it }
                if (onOpen != null) {
                    role = Role.Button
                    onClick { onOpen(); true }
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(scheduled.format(BOARD_TIME_FORMATTER), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expected != null && expected.toInstant() != scheduled.toInstant()) {
                Text(
                    stringResource(R.string.expected_time_short, expected.format(BOARD_TIME_FORMATTER)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                directionLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    entry.lineLabel?.takeIf(String::isNotBlank),
                    entry.publicNumber?.takeIf(String::isNotBlank)?.let { stringResource(R.string.station_board_train_number, it) },
                    entry.operatorId?.value?.let(OperatorNames::displayName),
                    entry.status?.value?.stationBoardLabel()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (delayedMinutes > 0) {
                Text(stringResource(R.string.delay_minutes, delayedMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (lastOfDay) LastOfDayChip(Modifier.padding(top = 2.dp))
            entry.routeChange?.value?.let { change ->
                Text(
                    stringResource(R.string.station_board_route_change, change.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        platform?.let {
            // A platform the source moved: "Bin. 7 (era 5)" in the attention colour, never the colour alone.
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (platformChange != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (platformChange != null) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    platformChange?.let { change -> stringResource(R.string.platform_changed_short, change.actual, change.scheduled) }
                        ?: stringResource(R.string.station_board_platform, it),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (onOpen != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationBoardRunDetailsSheet(
    station: String,
    entry: StationBoardEntry,
    kind: BoardKind,
    details: StationBoardRunDetails?,
    state: RefreshState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        StationBoardRunDetailsContent(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            station = station,
            entry = entry,
            kind = kind,
            details = details,
            state = state,
            onRefresh = onRefresh,
            onDismiss = onDismiss
        )
    }
}

/** The run details of the selected board entry in the side pane of a wide window; empty without a selection. */
@Composable
internal fun StationBoardRunDetailsPane(modifier: Modifier, viewModel: TrainViewModel) {
    val selected = viewModel.selectedBoardEntry ?: return
    StationBoardRunDetailsContent(
        modifier = modifier.fillMaxSize(),
        station = viewModel.stationBoardStation?.name.orEmpty(),
        entry = viewModel.stationBoardRunDetails?.entry ?: selected,
        kind = viewModel.stationBoardKind,
        details = viewModel.stationBoardRunDetails,
        state = viewModel.stationBoardRunRefreshState,
        onRefresh = viewModel::refreshStationBoardRun,
        onDismiss = viewModel::closeStationBoardRun
    )
}

@Composable
private fun StationBoardRunDetailsContent(
    modifier: Modifier,
    station: String,
    entry: StationBoardEntry,
    kind: BoardKind,
    details: StationBoardRunDetails?,
    state: RefreshState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    LazyColumn(modifier.testTag("station-run-details")) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.station_board_run_details_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_close)) }
                }
                Text(station, style = MaterialTheme.typography.titleMedium)
                if (details != null) {
                    Text(ltrUnit("${details.origin} → ${details.destination}"), style = MaterialTheme.typography.bodyLarge)
                    Text(details.observedAt?.let {
                        stringResource(R.string.provider_updated_at, localizedProviderName(details.providerName), updateTimeMessage(it.toEpochMilli()).localized())
                    } ?: localizedProviderName(details.providerName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StationBoardEntryRow(entry, kind)
            TextButton(onClick = onRefresh, enabled = state != RefreshState.LOADING, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.action_refresh))
            }
            if (state == RefreshState.LOADING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.station_board_run_details_loading), modifier = Modifier.padding(20.dp))
            } else if (details == null) {
                Text(stringResource(R.string.station_board_run_details_unavailable), modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (details != null) {
            item { Text(stringResource(R.string.service_stops_title), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            val progress = RouteMapPlanner.progress(details.stops)
            itemsIndexed(details.stops) { index, stop ->
                StopTimelineRow(stop.station, stop.expected, stop.scheduled, stop.delayMinutes, stop.platform, stop.completed,
                    operationalStatusVerified = stop.operationalStatusVerified == true, routeChange = stop.routeChange, onSetAlarm = null,
                    position = StopTimelinePosition.of(index, progress, stop.completed),
                    isFirst = index == 0,
                    isLast = index == details.stops.lastIndex,
                    scheduledPlatform = stop.scheduledPlatform)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun stationBoardStatusAnnouncement(entry: StationBoardEntry): String {
    val runStatus = entry.status?.value
    return when {
        entry.expectedTime != null -> stringResource(
            R.string.station_board_status_live_prediction,
            entry.expectedTime.value.withZoneSameInstant(BOARD_ZONE).format(BOARD_TIME_FORMATTER)
        )
        runStatus != null && runStatus != RunStatus.SCHEDULED && runStatus != RunStatus.UNKNOWN ->
            stringResource(R.string.station_board_status_live, runStatus.stationBoardLabel())
        runStatus == RunStatus.UNKNOWN -> stringResource(R.string.station_board_status_unavailable)
        else -> stringResource(R.string.station_board_status_planned)
    }
}

@Composable
internal fun RunStatus.stationBoardLabel(): String = when (this) {
    RunStatus.SCHEDULED -> stringResource(R.string.run_status_scheduled)
    RunStatus.ON_TIME -> stringResource(R.string.run_status_on_time)
    RunStatus.DELAYED -> stringResource(R.string.run_status_delayed)
    RunStatus.CANCELLED -> stringResource(R.string.run_status_cancelled)
    RunStatus.PARTIALLY_CANCELLED -> stringResource(R.string.run_status_partially_cancelled)
    RunStatus.DIVERTED -> stringResource(R.string.run_status_diverted)
    RunStatus.ADDED -> stringResource(R.string.run_status_added)
    RunStatus.REPLACED_BY_BUS -> stringResource(R.string.run_status_bus_replacement)
    RunStatus.COMPLETED -> stringResource(R.string.run_status_completed)
    RunStatus.UNKNOWN -> stringResource(R.string.run_status_unknown)
}

private val BOARD_ZONE: ZoneId = ZoneId.of("Europe/Rome")

private val BOARD_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

@Composable
internal fun StationPreferenceRow(
    station: Station,
    favourite: Boolean,
    onSelect: () -> Unit,
    onToggleFavourite: () -> Unit,
    distanceLabel: String? = null,
    /** The packs publish the station as wheelchair accessible: the wheelchair mark, read with the name. */
    wheelchairAccessible: Boolean = false
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(station.city, distanceLabel).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (wheelchairAccessible) WheelchairMark(stringResource(R.string.wheelchair_station), Modifier.padding(start = 6.dp))
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(
                    if (favourite) R.string.accessibility_remove_favourite_station else R.string.accessibility_add_favourite_station,
                    station.name
                )
            )
        }
    }
}
