package it.binario.app.ui.detail

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import it.binario.app.ActiveTrip
import it.binario.app.ArrivalConnectionsSection
import it.binario.app.BinarioTheme
import it.binario.app.PunctualityCard
import it.binario.app.PunctualityStats
import it.binario.app.data.reliability.NetworkReliabilityDetail
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.TrainCalendarAction
import it.binario.app.TrainShareStrings
import it.binario.app.data.CompensationShare
import it.binario.app.data.DelayCompensation
import it.binario.app.data.DelayCompensationRule
import it.binario.app.data.Freshness
import it.binario.app.data.PlatformChange
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.links.OfficialLinks
import it.binario.app.links.OperatorLinkKind
import it.binario.app.localized
import it.binario.app.gpsalarm.GpsAlarmStation
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.maps.RouteMapCard
import it.binario.app.maps.RouteMapPlanner
import it.binario.app.maps.RouteProgress
import it.binario.app.maps.mapPoint
import it.binario.app.notifications.ApproachAlertControl
import it.binario.app.notifications.FollowedTripNotificationToggle
import it.binario.app.notifications.TrainAlertToggle
import it.binario.app.openExternalLink
import it.binario.app.timetables.TimetableShareFormatter
import it.binario.app.ui.common.BikesMark
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.DataState
import it.binario.app.ui.common.HeaderBlock
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.HeaderOverline
import it.binario.app.ui.common.LocalWideLayout
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SegmentedControl
import it.binario.app.ui.common.StationShortNames
import it.binario.app.ui.common.ServiceStatusBadge
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.co2Line
import it.binario.app.ui.common.headerMutedColor
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.common.platformColor
import it.binario.app.ui.common.platformSpoken
import it.binario.app.ui.common.platformText
import it.binario.app.ui.common.presentedAsDemonstration
import it.binario.app.ui.common.rememberCo2
import it.binario.app.ui.common.routeChangeLabel
import it.binario.app.ui.planner.startTextShare
import it.binario.app.ui.planner.timetableShareStrings
import it.binario.app.updateTimeMessage
import java.util.Locale

// Train detail: header, actions, stop timeline, alarms, map and share.

/** The share templates in the app language, resolved once per language (remember them with [resources]). */
internal fun trainShareStrings(resources: Resources, locale: Locale): TrainShareStrings = TrainShareStrings(
    locale = locale,
    title = resources.getString(R.string.share_train_title),
    detailTitle = resources.getString(R.string.share_detail_title),
    operatorCategory = resources.getString(R.string.share_operator_category),
    route = resources.getString(R.string.share_route),
    expectedDeparture = resources.getString(R.string.share_expected_departure),
    scheduledDeparture = resources.getString(R.string.share_scheduled_departure),
    platform = resources.getString(R.string.share_platform),
    platformToConfirm = resources.getString(R.string.platform_to_confirm),
    status = resources.getString(R.string.share_status),
    stopsTitle = resources.getString(R.string.service_stops_title),
    stop = resources.getString(R.string.share_stop),
    stopPlatform = resources.getString(R.string.share_stop_platform),
    stopDelay = resources.getString(R.string.share_stop_delay),
    data = resources.getString(R.string.share_data),
    source = resources.getString(R.string.share_source),
    updated = resources.getString(R.string.share_updated),
    neverUpdated = resources.getString(R.string.share_never_updated),
    demonstrationData = resources.getString(R.string.data_status_demo),
    scheduledData = resources.getString(R.string.share_scheduled_data),
    unavailableData = resources.getString(R.string.data_status_unavailable),
    liveData = resources.getString(R.string.share_live_data),
    statusLabels = mapOf(
        ServiceStatus.ON_TIME to resources.getString(R.string.share_status_on_time),
        ServiceStatus.DELAYED to resources.getString(R.string.share_status_delayed),
        ServiceStatus.CANCELLED to resources.getString(R.string.share_status_cancelled)
    ),
    categoryLabels = mapOf(
        TrainCategory.REGIONALE to resources.getString(R.string.share_category_regionale),
        TrainCategory.REGIONALE_VELOCE to resources.getString(R.string.share_category_regionale_veloce),
        TrainCategory.INTERCITY to resources.getString(R.string.share_category_intercity),
        TrainCategory.FRECCE to resources.getString(R.string.share_category_frecce),
        TrainCategory.AV_ITALO to resources.getString(R.string.share_category_av_italo),
        TrainCategory.TRENORD_REGIO to resources.getString(R.string.share_category_trenord_regio),
        TrainCategory.METROPOLITANO to resources.getString(R.string.share_category_metropolitano),
        TrainCategory.OTHER to resources.getString(R.string.share_category_other)
    ),
    freshnessLabels = mapOf(
        Freshness.FRESH to resources.getString(R.string.share_freshness_fresh),
        Freshness.AGING to resources.getString(R.string.share_freshness_aging),
        Freshness.STALE to resources.getString(R.string.share_freshness_stale),
        Freshness.UNAVAILABLE to resources.getString(R.string.share_freshness_unavailable)
    )
)


/** The three tabs of the train detail. */
internal enum class DetailTab { ROUTE, INFO, SERVICES }

/**
 * Train detail as in the approved render: a green header with the train name, the route, the timetable state
 * and the tabs Percorso / Info / Servizi, and "Segui questo viaggio" at the bottom. Percorso is the stop
 * timeline (passed, last confirmed and next stops named in text, not only by colour) with the route map;
 * Info carries operator, platform, status, data source and quality, notices, weather, the unofficial network
 * reliability statistics and the device's own punctuality history;
 * Servizi groups the actions the app really has: stop and GPS alarms, calendar, connections at arrival.
 * Save (the heart) and share live in the top bar.
 *
 * @param showShareButton true in the side pane of wide windows, where "Condividi" sits beside the follow button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServiceDetailScreen(
    modifier: Modifier,
    service: TrainService,
    provider: String,
    confidence: String,
    freshness: Freshness?,
    refreshedAt: Long,
    isDemonstration: Boolean,
    onRefresh: () -> Unit,
    isMonitored: Boolean,
    onScheduleStopAlarm: (TrainService, TrainStop) -> Unit,
    alarmFeedback: String?,
    gpsAlarmPanel: @Composable () -> Unit,
    activeTrip: ActiveTrip?,
    onStartActiveTrip: () -> Unit,
    onAdvanceActiveTrip: () -> Unit,
    onEndActiveTrip: () -> Unit,
    connectionStop: String?,
    hasBoardProviders: Boolean,
    loadConnectionBoard: (String, java.time.Instant) -> kotlinx.coroutines.flow.Flow<it.binario.app.data.repository.StationBoardAggregateResult>?,
    shareStrings: TrainShareStrings,
    punctuality: PunctualityStats?,
    onClearPunctuality: () -> Unit,
    mapStationIndex: OpenMapStationIndex,
    onShare: () -> Unit,
    showShareButton: Boolean = false,
    /** The catalogue station of a published name, for the short city form of the header ("Roma → Torino"). */
    stationLookup: (String) -> Station? = { null },
    sourceSections: @Composable () -> Unit = {},
    /** Live, scheduled timetable or saved copy for this run ([DataState]); null falls back to the freshness. */
    dataState: DataState? = null,
    /** «Affidabilità ultimi N giorni» from the embedded network statistics; null shows no card. */
    networkReliability: NetworkReliabilityDetail? = null
) {
    // Demonstration data shows the timetable only: no delay, expected time, platform or progress as if real.
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val dataLabel = when {
        isDemonstration -> stringResource(R.string.data_status_demo)
        dataState == DataState.LIVE -> stringResource(R.string.data_status_live, (freshness ?: Freshness.FRESH).localizedLabel())
        dataState == DataState.SCHEDULED -> stringResource(R.string.data_status_scheduled)
        dataState == DataState.SAVED_COPY -> stringResource(R.string.data_chip_saved_copy)
        else -> freshness?.takeIf { dataState != DataState.UNAVAILABLE }?.localizedLabel() ?: stringResource(R.string.data_status_unavailable)
    }
    val isThisTrip = activeTrip?.trainNumber == service.number
    val progress = RouteMapPlanner.progress(shown.stops)
    // Another train opens on Percorso again; the same train keeps its tab across recreation.
    var tabName by rememberSaveable(service.id) { mutableStateOf(DetailTab.ROUTE.name) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val purchaseSite = OfficialLinks.purchaseSiteOf(service.operator, service.operatorDisplayName)
    // The operator's official pages of Settings › Biglietti e assistenza (bikes on board, refunds and compensation).
    val operatorLinks = OfficialLinks.operatorOf(service.operator, service.operatorDisplayName)
    val bikesPage = operatorLinks?.let { links -> links.url(OperatorLinkKind.BIKES)?.let { links.name to it } }
    // Arrived 60 minutes late or more at the destination, on a verified actual arrival: the EU compensation rule.
    val compensation = DelayCompensationRule.of(service, isDemonstration)
    val refundsPage = operatorLinks?.url(OperatorLinkKind.REFUNDS)
    val co2 = rememberCo2(service, mapStationIndex)
    val stopShareStrings = timetableShareStrings()
    val stopShareChooser = stringResource(R.string.share_stop_chooser)
    val tab = DetailTab.valueOf(tabName)
    Column(modifier.fillMaxSize().testTag("train-detail-screen")) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("train-detail"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item { ServiceDetailHeader(shown, isDemonstration, tab, onTab = { tabName = it.name }, stationLookup = stationLookup) }
            when (tab) {
                DetailTab.ROUTE -> {
                    item(key = "timeline-${service.id}") {
                        HeaderOverlapBox {
                            BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                                Column(Modifier.padding(vertical = 12.dp).testTag("detail-timeline")) {
                                    shown.routeChange?.let { RouteChangeBanner(routeChange = it, sourceIds = shown.routeChangeSourceIds) }
                                    shown.stops.forEachIndexed { index, stop ->
                                        StopTimelineRow(
                                            station = stop.station,
                                            expected = stop.expected,
                                            scheduled = stop.scheduled,
                                            delay = stop.delayMinutes,
                                            platform = stop.platform,
                                            completed = stop.completed,
                                            operationalStatusVerified = stop.operationalStatusVerified ?: shown.operationalStatusVerified,
                                            routeChange = stop.routeChange,
                                            onSetAlarm = null,
                                            position = StopTimelinePosition.of(index, progress, stop.completed),
                                            isFirst = index == 0,
                                            isLast = index == shown.stops.lastIndex,
                                            scheduledPlatform = stop.scheduledPlatform,
                                            onShare = {
                                                val verified = stop.operationalStatusVerified ?: shown.operationalStatusVerified
                                                startTextShare(
                                                    context,
                                                    TimetableShareFormatter.stop(
                                                        train = shown.number,
                                                        station = stop.station,
                                                        scheduled = stop.scheduled,
                                                        expected = stop.expected.takeIf { verified },
                                                        departure = index == 0,
                                                        platform = stop.platform,
                                                        scheduledPlatform = stop.scheduledPlatform,
                                                        strings = stopShareStrings
                                                    ),
                                                    stopShareChooser
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { DataSummary(dataLabel, provider, confidence, refreshedAt, onRefresh) }
                    item(key = "route-map-${service.id}") {
                        RouteMapCard(shown, mapStationIndex, Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp))
                    }
                }
                DetailTab.INFO -> {
                    item {
                        HeaderOverlapBox {
                            BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                                Column(Modifier.padding(16.dp).testTag("detail-info"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    InfoRow(stringResource(R.string.detail_info_operator), shown.displayOperator)
                                    InfoRow(stringResource(R.string.detail_info_train), stringResource(R.string.service_category_number, shown.trainCategory.localizedLabel(), shown.number))
                                    InfoRow(stringResource(R.string.service_scheduled_departure_label), shown.scheduledDeparture)
                                    if (shown.operationalStatusVerified && shown.expectedDeparture != shown.scheduledDeparture) {
                                        InfoRow(stringResource(R.string.detail_info_expected), shown.expectedDeparture)
                                    }
                                    InfoRow(
                                        stringResource(R.string.detail_info_platform),
                                        when {
                                            isDemonstration -> stringResource(R.string.platform_unavailable)
                                            shown.platform != null -> platformText(shown.platform, shown.scheduledPlatform, long = true) ?: shown.platform
                                            else -> stringResource(R.string.platform_to_confirm)
                                        }
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.detail_info_status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        ServiceStatusBadge(shown)
                                    }
                                    compensation?.let { DelayCompensationNotice(it, refundsPage) }
                                    if (isDemonstration) {
                                        Pill(stringResource(R.string.data_status_demo), container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                    Text(
                                        stringResource(if (isMonitored) R.string.detail_monitored_state else R.string.detail_not_monitored_state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!isDemonstration) TrainAlertToggle(service, eligible = isMonitored || isThisTrip)
                                    if (!isDemonstration) ApproachAlertControl(service, eligible = isMonitored)
                                    // Bikes on board as the timetable publishes them; when it does not, the operator's own conditions.
                                    shown.bikesAllowed?.let { BikesMark(it, Modifier.testTag("detail-bikes")) }
                                        ?: bikesPage?.takeIf { !isDemonstration }?.let { (operatorName, url) ->
                                            TextButton(
                                                onClick = { openExternalLink(uriHandler, url) },
                                                modifier = Modifier.testTag("detail-bikes-conditions")
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.bikes_conditions_link, operatorName), modifier = Modifier.weight(1f, fill = false))
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    co2?.let { estimate ->
                                        Column(Modifier.testTag("detail-co2"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            InfoRow(stringResource(R.string.co2_estimate_title), co2Line(estimate))
                                            Text(stringResource(R.string.co2_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    // Trenitalia or Italo: their official site, without a prefilled search (none is documented).
                                    purchaseSite?.let { site ->
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TonalButton(
                                                text = stringResource(R.string.official_site_open, site.name),
                                                onClick = { site.url(OperatorLinkKind.BUY)?.let { openExternalLink(uriHandler, it) } },
                                                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                                modifier = Modifier.testTag("detail-official-site")
                                            )
                                            Text(stringResource(R.string.official_site_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { DataSummary(dataLabel, provider, confidence, refreshedAt, onRefresh) }
                    shown.routeChange?.let { routeChange ->
                        item { RouteChangeBanner(routeChange = routeChange, sourceIds = shown.routeChangeSourceIds) }
                    }
                    item { sourceSections() }
                    networkReliability?.let { detail ->
                        item(key = "network-reliability-${service.id}") { NetworkReliabilityCard(detail) }
                    }
                    punctuality?.let { stats ->
                        item { PunctualityCard(stats = stats, onClear = onClearPunctuality) }
                    }
                }
                DetailTab.SERVICES -> {
                    item {
                        HeaderOverlapBox {
                            BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                                Column(Modifier.padding(16.dp).testTag("detail-services"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.detail_stop_alarm_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.stop_alarm_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    alarmFeedback?.let { feedback ->
                                        Text(feedback, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    service.stops.forEachIndexed { index, original ->
                                        val stop = shown.stops.getOrNull(index) ?: original
                                        if (!stop.completed) {
                                            StopAlarmRow(stop.station, stop.scheduled, onClick = { onScheduleStopAlarm(service, original) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { gpsAlarmPanel() }
                    item { TrainCalendarAction(service = service, shareStrings = shareStrings) }
                    item {
                        ArrivalConnectionsSection(
                            service = service,
                            preferredStop = connectionStop,
                            hasBoardProviders = hasBoardProviders,
                            loadBoard = loadConnectionBoard
                        )
                    }
                    item {
                        Column(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.follow_trip_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.detail_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        DetailBottomBar(
            isThisTrip = isThisTrip,
            activeTrip = activeTrip,
            onStartActiveTrip = onStartActiveTrip,
            onAdvanceActiveTrip = onAdvanceActiveTrip,
            onEndActiveTrip = onEndActiveTrip,
            onShare = onShare.takeIf { showShareButton },
            followedService = service.takeIf { !isDemonstration },
            locateStop = { name -> mapStationIndex.locate(name)?.let { it.mapPoint()?.let { point -> GpsAlarmStation(it.id, it.name, point.latitude, point.longitude) } } }
        )
    }
}

/** "Segui questo viaggio" (the local active trip), or the trip being followed with its next stop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBottomBar(
    isThisTrip: Boolean,
    activeTrip: ActiveTrip?,
    onStartActiveTrip: () -> Unit,
    onAdvanceActiveTrip: () -> Unit,
    onEndActiveTrip: () -> Unit,
    onShare: (() -> Unit)?,
    /** The train whose opt-in ongoing notification is offered while it is followed; null offers none. */
    followedService: TrainService? = null,
    /** Catalogue coordinates of a stop, for "Stima dalla tua posizione". */
    locateStop: ((String) -> GpsAlarmStation?)? = null
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isThisTrip) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("detail-active-trip")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.active_trip_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val tripDescription = activeTrip?.nextStop?.let { stringResource(R.string.personal_travel_next_stop, it.station, it.expected) }
                            ?: stringResource(R.string.personal_travel_completed)
                        Text(tripDescription, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAdvanceActiveTrip, enabled = activeTrip?.isComplete == false) { Text(stringResource(R.string.action_stop_reached)) }
                            TextButton(onClick = onEndActiveTrip) { Text(stringResource(R.string.action_end)) }
                        }
                        followedService?.let { FollowedTripNotificationToggle(it, locate = locateStop) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isThisTrip) {
                    Button(
                        onClick = onStartActiveTrip,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("detail-follow-trip"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        // The narrower side pane uses the short label of the tablet render.
                        Text(stringResource(if (onShare != null) R.string.action_follow_trip else R.string.action_follow_this_trip), fontWeight = FontWeight.Bold)
                    }
                }
                onShare?.let { share ->
                    OutlinedButton(
                        onClick = share,
                        modifier = Modifier.weight(if (isThisTrip) 1f else 0.7f).heightIn(min = 56.dp).testTag("detail-share-button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Provenance of the detail: data state, source, quality and update time, with the refresh command. */
@Composable
private fun DataSummary(dataLabel: String, provider: String, confidence: String, refreshedAt: Long, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = ScreenMargin, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.service_data_summary, dataLabel, provider, confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(updateTimeMessage(refreshedAt).localized(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh_service)) }
    }
}

/** One stop of the alarm list: station, scheduled time and the alarm command named after the stop. */
@Composable
private fun StopAlarmRow(station: String, scheduled: String, onClick: () -> Unit) {
    val description = stringResource(R.string.accessibility_stop_alarm_at, station, scheduled)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(station, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(scheduled, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_stop_alarm))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceDetailHeader(
    service: TrainService,
    isDemonstration: Boolean,
    tab: DetailTab,
    onTab: (DetailTab) -> Unit,
    stationLookup: (String) -> Station?
) {
    val locale = LocalConfiguration.current.locales[0]
    val wide = LocalWideLayout.current
    val platformSpokenLabel = if (service.platform != null) platformSpoken(service.platform, service.scheduledPlatform) else stringResource(R.string.platform_to_confirm)
    HeaderBlock(bottomPadding = 12.dp) {
        if (wide) {
            Text(stringResource(R.string.screen_title_detail), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HeaderOverline(stringResource(R.string.service_category_number, service.trainCategory.localizedLabel(), service.number).uppercase(locale))
        // The render's short city form ("Roma → Torino"); screen readers get the full station names.
        val headline = remember(service.origin, service.destination) {
            StationShortNames.route(service.origin, service.destination, stationLookup(service.origin)?.city, stationLookup(service.destination)?.city)
        }
        Text(
            ltrUnit(headline.short),
            modifier = Modifier.semantics {
                heading()
                contentDescription = headline.full
            },
            style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.detail_departure_time, service.scheduledDeparture),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures),
                fontWeight = FontWeight.Bold
            )
            ServiceStatusBadge(service)
            if (service.operationalStatusVerified && service.expectedDeparture != service.scheduledDeparture) {
                Text(stringResource(R.string.expected_time, service.expectedDeparture), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            if (!isDemonstration) {
                Text(
                    platformText(service.platform, service.scheduledPlatform, long = true) ?: stringResource(R.string.platform_to_confirm),
                    modifier = Modifier.semantics { contentDescription = platformSpokenLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    color = headerMutedColor(),
                    fontWeight = if (PlatformChange.of(service.platform, service.scheduledPlatform) != null) FontWeight.Bold else null
                )
            }
        }
        if (isDemonstration) {
            Pill(
                stringResource(R.string.data_status_demo),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        SegmentedControl(
            options = DetailTab.entries,
            selected = tab,
            onSelect = onTab,
            labels = DetailTab.entries.map { it.localizedLabel() },
            onHeader = true,
            modifier = Modifier.padding(top = 8.dp),
            tagOf = {
                when (it) {
                    DetailTab.ROUTE -> "detail-tab-route"
                    DetailTab.INFO -> "detail-tab-info"
                    DetailTab.SERVICES -> "detail-tab-services"
                }
            }
        )
    }
}

@Composable
private fun DetailTab.localizedLabel(): String = stringResource(
    when (this) {
        DetailTab.ROUTE -> R.string.detail_tab_route
        DetailTab.INFO -> R.string.detail_tab_info
        DetailTab.SERVICES -> R.string.detail_tab_services
    }
)

/** Where a stop is on the run, from passages a source confirmed (see [RouteMapPlanner.progress]). */
internal enum class StopTimelinePosition {
    PASSED, LAST_CONFIRMED, NEXT, UPCOMING;

    companion object {
        fun of(index: Int, progress: RouteProgress, completed: Boolean): StopTimelinePosition = when {
            index == progress.lastConfirmedIndex -> LAST_CONFIRMED
            index == progress.nextIndex -> NEXT
            completed -> PASSED
            else -> UPCOMING
        }
    }
}

/**
 * One stop of the vertical timeline. The line joins the stops; the dot and a text label say whether the
 * stop was passed, is the last confirmed one or is the next one, so the state never relies on colour.
 */
@Composable
internal fun StopTimelineRow(
    station: String,
    expected: String,
    scheduled: String,
    delay: Int,
    platform: String?,
    completed: Boolean,
    operationalStatusVerified: Boolean,
    routeChange: StopRouteChange,
    onSetAlarm: (() -> Unit)?,
    position: StopTimelinePosition = StopTimelinePosition.UPCOMING,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    /** Timetable platform when the source moved the train ("Binario 7 (era 5)"). */
    scheduledPlatform: String? = null,
    /** Shares this stop as text ("Treno X, fermata Y: arr. 10:05, bin. 3"); null hides the command. */
    onShare: (() -> Unit)? = null
) {
    val changeLabel = when (routeChange) {
        StopRouteChange.NONE -> ""
        StopRouteChange.ADDED -> stringResource(R.string.stop_change_added_accessibility) + " "
        StopRouteChange.SKIPPED -> stringResource(R.string.stop_change_skipped_accessibility) + " "
    }
    val operationalLabel = when {
        !operationalStatusVerified -> stringResource(R.string.operational_status_unverified)
        delay > 0 -> pluralStringResource(R.plurals.operational_delay_minutes, delay, delay)
        else -> stringResource(R.string.operational_on_time)
    }
    val platformLabel = platformSpoken(platform, scheduledPlatform)
    val positionLabel = when (position) {
        StopTimelinePosition.PASSED -> stringResource(R.string.stop_position_passed)
        StopTimelinePosition.LAST_CONFIRMED -> stringResource(R.string.map_role_last_confirmed)
        StopTimelinePosition.NEXT -> stringResource(R.string.map_role_next)
        StopTimelinePosition.UPCOMING -> null
    }
    val stopLabel = stringResource(
        R.string.accessibility_stop_timeline,
        station,
        changeLabel,
        scheduled,
        if (operationalStatusVerified && expected != scheduled) stringResource(R.string.expected_time, expected) + ". " else "",
        operationalLabel,
        platformLabel
    )
    val accessibilityLabel = positionLabel?.let { stringResource(R.string.accessibility_title_detail, it, stopLabel) } ?: stopLabel
    val roleLabel = stringResource(
        when {
            isFirst -> R.string.stop_role_departure
            isLast -> R.string.stop_role_arrival
            else -> R.string.stop_role_intermediate
        }
    )
    val reached = position == StopTimelinePosition.PASSED || position == StopTimelinePosition.LAST_CONFIRMED
    val lineColor = BinarioTheme.colors.timeline
    val dotColor = when (position) {
        StopTimelinePosition.PASSED, StopTimelinePosition.LAST_CONFIRMED -> lineColor
        StopTimelinePosition.NEXT -> MaterialTheme.colorScheme.tertiary
        StopTimelinePosition.UPCOMING -> if (completed || isLast) lineColor else MaterialTheme.colorScheme.surface
    }
    val dotSize = if (position == StopTimelinePosition.LAST_CONFIRMED || position == StopTimelinePosition.NEXT || isFirst || isLast) 18.dp else 14.dp
    val endpoint = isFirst || isLast
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // The dot column sits at the start edge: the right one in a right-to-left layout.
                val fromStart = TIMELINE_START.toPx() + TIMELINE_WIDTH.toPx() / 2
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - fromStart else fromStart
                val dotCenter = TIMELINE_DOT_CENTER.toPx()
                val stroke = 3.dp.toPx()
                if (!isFirst) drawLine(lineColor, Offset(x, 0f), Offset(x, dotCenter), stroke)
                if (!isLast) drawLine(lineColor, Offset(x, dotCenter), Offset(x, size.height), stroke)
            }
            .padding(start = TIMELINE_START, end = 16.dp, top = TIMELINE_TOP, bottom = TIMELINE_TOP)
            // One stop is one TalkBack item (its full label); the alarm and share buttons stay their own controls.
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(TIMELINE_WIDTH).height(TIMELINE_DOT_SLOT), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(dotSize),
                shape = CircleShape,
                color = dotColor,
                border = BorderStroke(3.dp, if (reached || position == StopTimelinePosition.NEXT) dotColor else lineColor)
            ) {}
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(min = 56.dp).padding(end = 12.dp), horizontalAlignment = Alignment.Start) {
            Text(
                scheduled,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures),
                fontWeight = if (endpoint) FontWeight.Bold else FontWeight.Normal
            )
            if (operationalStatusVerified && delay > 0) {
                Text(stringResource(R.string.delay_minutes, delay), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            if (operationalStatusVerified && expected != scheduled) {
                Text(stringResource(R.string.expected_time_short, expected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                station,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (position == StopTimelinePosition.NEXT || position == StopTimelinePosition.LAST_CONFIRMED) FontWeight.ExtraBold else FontWeight.Bold,
                color = if (routeChange == StopRouteChange.SKIPPED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(roleLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            positionLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (position == StopTimelinePosition.NEXT) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                )
            }
            when (routeChange) {
                StopRouteChange.SKIPPED -> Text(stringResource(R.string.stop_skipped_uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                StopRouteChange.ADDED -> Text(stringResource(R.string.stop_added_uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                StopRouteChange.NONE -> Unit
            }
            platformText(platform, scheduledPlatform, long = true)?.let {
                val changed = PlatformChange.of(platform, scheduledPlatform) != null
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = platformColor(platform, scheduledPlatform, MaterialTheme.colorScheme.onSurfaceVariant),
                    fontWeight = if (changed) FontWeight.Bold else null
                )
            }
            if (!operationalStatusVerified) {
                Text(stringResource(R.string.operational_status_unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!completed && onSetAlarm != null) {
                TextButton(onClick = onSetAlarm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_stop_alarm))
                }
            }
        }
        onShare?.let { share ->
            IconButton(onClick = share, modifier = Modifier.testTag("detail-share-stop")) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.share_stop_action, station),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private val TIMELINE_START = 16.dp
private val TIMELINE_WIDTH = 24.dp
private val TIMELINE_TOP = 12.dp
/** The dot sits on the first line of the station name (titleMedium). */
private val TIMELINE_DOT_SLOT = 24.dp
private val TIMELINE_DOT_CENTER = TIMELINE_TOP + TIMELINE_DOT_SLOT / 2

@Composable
private fun RouteChangeBanner(
    routeChange: OperationalRouteChange,
    sourceIds: Set<String>
) {
    val severe = routeChange.kind in setOf(
        RouteChangeKind.SKIPPED_STOPS,
        RouteChangeKind.SHORT_TURN,
        RouteChangeKind.REPLACEMENT_SERVICE
    )
    val container = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val foreground = if (severe) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    val sourceLabel = sourceIds.takeIf { it.isNotEmpty() }?.joinToString(", ")
    val routeChangeTitle = routeChangeLabel(routeChange)
    val skippedDescription = routeChange.skippedStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_skipped, it.joinToString(", ")) }
    val addedDescription = routeChange.addedStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_added, it.joinToString(", ")) }
    val viaDescription = routeChange.viaStopLabels.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.route_change_accessibility_via, it.joinToString(", ")) }
    val destinationDescription = routeChange.replacementDestinationLabel
        ?.let { stringResource(R.string.route_change_destination, it) }
    val accessibilityLabel = listOfNotNull(
        routeChangeTitle,
        routeChange.summary,
        skippedDescription,
        addedDescription,
        viaDescription,
        destinationDescription
    ).joinToString(". ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        color = container,
        contentColor = foreground,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(routeChangeTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(routeChange.summary, style = MaterialTheme.typography.bodyMedium)
                if (routeChange.skippedStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_skipped, routeChange.skippedStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.addedStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_added, routeChange.addedStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (routeChange.viaStopLabels.isNotEmpty()) {
                    Text(stringResource(R.string.route_change_via, routeChange.viaStopLabels.joinToString(", ")), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                routeChange.replacementDestinationLabel?.let {
                    Text(stringResource(R.string.route_change_destination, it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                sourceLabel?.let {
                    Text(stringResource(R.string.route_change_source, it), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * The delay compensation notice of the Info tab: what Regulation (EU) 2021/782 art. 19 grants for the delay at the
 * destination, its limits, and the operator's official refunds and compensation page when one is known. It states the
 * rule only; the app knows nothing of the ticket.
 */
@Composable
private fun DelayCompensationNotice(compensation: DelayCompensation, refundsPage: String?) {
    val uriHandler = LocalUriHandler.current
    val delay = pluralStringResource(R.plurals.minutes_short, compensation.delayMinutes, compensation.delayMinutes)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("detail-delay-compensation")
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    if (compensation.share == CompensationShare.HALF) R.string.compensation_notice_half else R.string.compensation_notice_quarter,
                    delay
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.compensation_notice_conditions), style = MaterialTheme.typography.bodySmall)
            refundsPage?.let { url ->
                TonalButton(
                    text = stringResource(R.string.tickets_link_refunds),
                    onClick = { openExternalLink(uriHandler, url) },
                    trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier.testTag("detail-delay-compensation-link")
                )
                // The official domain the page opens on, as in Biglietti e assistenza.
                runCatching { java.net.URI(url).host }.getOrNull()?.let { host -> Text(host, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
