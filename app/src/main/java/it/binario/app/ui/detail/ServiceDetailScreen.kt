package it.binario.app.ui.detail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.binario.app.ActiveTrip
import it.binario.app.ArrivalConnectionsSection
import it.binario.app.PunctualityCard
import it.binario.app.PunctualityStats
import it.binario.app.R
import it.binario.app.TrainCalendarAction
import it.binario.app.TrainShareStrings
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.localized
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.maps.RouteMapCard
import it.binario.app.maps.RouteMapPlanner
import it.binario.app.maps.RouteProgress
import it.binario.app.ui.common.ServiceStatusBadge
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.presentedAsDemonstration
import it.binario.app.ui.common.routeChangeLabel
import it.binario.app.updateTimeMessage
import java.util.Locale

// Train detail: header, actions, stop timeline, alarms, map and share.

@Composable
internal fun trainShareStrings(locale: Locale): TrainShareStrings = TrainShareStrings(
    locale = locale,
    title = stringResource(R.string.share_train_title),
    detailTitle = stringResource(R.string.share_detail_title),
    operatorCategory = stringResource(R.string.share_operator_category),
    route = stringResource(R.string.share_route),
    expectedDeparture = stringResource(R.string.share_expected_departure),
    scheduledDeparture = stringResource(R.string.share_scheduled_departure),
    platform = stringResource(R.string.share_platform),
    platformToConfirm = stringResource(R.string.platform_to_confirm),
    status = stringResource(R.string.share_status),
    stopsTitle = stringResource(R.string.service_stops_title),
    stop = stringResource(R.string.share_stop),
    stopPlatform = stringResource(R.string.share_stop_platform),
    stopDelay = stringResource(R.string.share_stop_delay),
    data = stringResource(R.string.share_data),
    source = stringResource(R.string.share_source),
    updated = stringResource(R.string.share_updated),
    neverUpdated = stringResource(R.string.share_never_updated),
    demonstrationData = stringResource(R.string.data_status_demo),
    scheduledData = stringResource(R.string.share_scheduled_data),
    unavailableData = stringResource(R.string.data_status_unavailable),
    liveData = stringResource(R.string.share_live_data),
    statusLabels = mapOf(
        ServiceStatus.ON_TIME to stringResource(R.string.share_status_on_time),
        ServiceStatus.DELAYED to stringResource(R.string.share_status_delayed),
        ServiceStatus.CANCELLED to stringResource(R.string.share_status_cancelled)
    ),
    categoryLabels = mapOf(
        TrainCategory.REGIONALE to stringResource(R.string.share_category_regionale),
        TrainCategory.REGIONALE_VELOCE to stringResource(R.string.share_category_regionale_veloce),
        TrainCategory.INTERCITY to stringResource(R.string.share_category_intercity),
        TrainCategory.FRECCE to stringResource(R.string.share_category_frecce),
        TrainCategory.AV_ITALO to stringResource(R.string.share_category_av_italo),
        TrainCategory.TRENORD_REGIO to stringResource(R.string.share_category_trenord_regio),
        TrainCategory.METROPOLITANO to stringResource(R.string.share_category_metropolitano),
        TrainCategory.OTHER to stringResource(R.string.share_category_other)
    ),
    freshnessLabels = mapOf(
        Freshness.FRESH to stringResource(R.string.share_freshness_fresh),
        Freshness.AGING to stringResource(R.string.share_freshness_aging),
        Freshness.STALE to stringResource(R.string.share_freshness_stale),
        Freshness.UNAVAILABLE to stringResource(R.string.share_freshness_unavailable)
    )
)


/**
 * Train detail: header with time, status and platform; the actions the app really supports (follow the
 * trip locally, save it among the monitored trains, share it); then a vertical timeline of the stops whose
 * passed, last confirmed and next stops are named in text, not only by colour.
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
    onToggleMonitoring: () -> Unit,
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
    sourceSections: @Composable () -> Unit = {}
) {
    // Demonstration data shows the timetable only: no delay, expected time, platform or progress as if real.
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val dataLabel = if (isDemonstration) stringResource(R.string.data_status_demo) else freshness?.localizedLabel() ?: stringResource(R.string.data_status_unavailable)
    val isThisTrip = activeTrip?.trainNumber == service.number
    val progress = RouteMapPlanner.progress(shown.stops)
    LazyColumn(modifier = modifier.fillMaxSize().testTag("train-detail"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { ServiceDetailHeader(shown, isDemonstration) }
        shown.routeChange?.let { routeChange ->
            item {
                RouteChangeBanner(
                    routeChange = routeChange,
                    sourceIds = shown.routeChangeSourceIds
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isThisTrip) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Train, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.active_trip_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(onClick = onStartActiveTrip, modifier = Modifier.testTag("detail-follow-trip")) {
                            Icon(Icons.Default.Train, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_follow_trip))
                        }
                    }
                    OutlinedButton(onClick = onToggleMonitoring, modifier = Modifier.testTag("detail-save")) {
                        Icon(if (isMonitored) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (isMonitored) R.string.action_saved else R.string.action_save))
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.testTag("detail-share")) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
                if (!isThisTrip) {
                    Text(stringResource(R.string.follow_trip_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.detail_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isThisTrip) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.active_trip_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val tripDescription = activeTrip?.nextStop?.let { stringResource(R.string.personal_travel_next_stop, it.station, it.expected) }
                            ?: stringResource(R.string.personal_travel_completed)
                        Text(tripDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAdvanceActiveTrip, enabled = activeTrip?.isComplete == false) { Text(stringResource(R.string.action_stop_reached)) }
                            TextButton(onClick = onEndActiveTrip) { Text(stringResource(R.string.action_end)) }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.service_stops_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.service_data_summary, dataLabel, provider, confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(updateTimeMessage(refreshedAt).localized(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh_service)) }
            }
        }
        alarmFeedback?.let { feedback ->
            item {
                Text(
                    feedback,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { gpsAlarmPanel() }
        item {
            Text(
                stringResource(R.string.stop_alarm_notice),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        itemsIndexed(shown.stops, key = { index, stop -> "${service.id}-$index-${stop.station}" }) { index, stop ->
            val original = service.stops.getOrNull(index) ?: stop
            StopTimelineRow(
                station = stop.station,
                expected = stop.expected,
                scheduled = stop.scheduled,
                delay = stop.delayMinutes,
                platform = stop.platform,
                completed = stop.completed,
                operationalStatusVerified = stop.operationalStatusVerified ?: shown.operationalStatusVerified,
                routeChange = stop.routeChange,
                onSetAlarm = { onScheduleStopAlarm(service, original) },
                position = StopTimelinePosition.of(index, progress, stop.completed),
                isFirst = index == 0,
                isLast = index == shown.stops.lastIndex
            )
        }
        item(key = "route-map-${service.id}") {
            RouteMapCard(shown, mapStationIndex, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        item { TrainCalendarAction(service = service, shareStrings = shareStrings) }
        item {
            ArrivalConnectionsSection(
                service = service,
                preferredStop = connectionStop,
                hasBoardProviders = hasBoardProviders,
                loadBoard = loadConnectionBoard
            )
        }
        item { sourceSections() }
        punctuality?.let { stats ->
            item { PunctualityCard(stats = stats, onClear = onClearPunctuality) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceDetailHeader(service: TrainService, isDemonstration: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.service_operator_category, service.displayOperator, service.trainCategory.localizedLabel()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.service_number_route, service.number, service.origin, service.destination),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column {
                    Text(stringResource(R.string.service_scheduled_departure_label), style = MaterialTheme.typography.labelMedium)
                    Text(service.scheduledDeparture, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    if (service.operationalStatusVerified && service.expectedDeparture != service.scheduledDeparture) {
                        Text(stringResource(R.string.expected_time, service.expectedDeparture), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServiceStatusBadge(service)
                    Text(
                        service.platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (isDemonstration) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.data_status_demo),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

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
    isLast: Boolean = false
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
    val platformLabel = platform?.let { stringResource(R.string.platform_label, it) }
        ?: stringResource(R.string.platform_unavailable)
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
    val reached = position == StopTimelinePosition.PASSED || position == StopTimelinePosition.LAST_CONFIRMED
    val lineColor = MaterialTheme.colorScheme.outline
    val dotColor = when (position) {
        StopTimelinePosition.PASSED, StopTimelinePosition.LAST_CONFIRMED -> MaterialTheme.colorScheme.primary
        StopTimelinePosition.NEXT -> MaterialTheme.colorScheme.tertiary
        StopTimelinePosition.UPCOMING -> if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    }
    val dotSize = if (position == StopTimelinePosition.LAST_CONFIRMED || position == StopTimelinePosition.NEXT) 18.dp else 14.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = TIMELINE_START.toPx() + TIMELINE_WIDTH.toPx() / 2
                val dotCenter = TIMELINE_DOT_CENTER.toPx()
                val stroke = 2.dp.toPx()
                if (!isFirst) drawLine(lineColor, Offset(x, 0f), Offset(x, dotCenter), stroke)
                if (!isLast) drawLine(lineColor, Offset(x, dotCenter), Offset(x, size.height), stroke)
            }
            .padding(start = TIMELINE_START, end = 20.dp, top = TIMELINE_TOP, bottom = TIMELINE_TOP)
            .semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(TIMELINE_WIDTH).height(TIMELINE_DOT_SLOT), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(dotSize),
                shape = CircleShape,
                color = dotColor,
                border = BorderStroke(2.dp, if (reached || position == StopTimelinePosition.NEXT) dotColor else MaterialTheme.colorScheme.outline)
            ) {}
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                station,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (position == StopTimelinePosition.NEXT || position == StopTimelinePosition.LAST_CONFIRMED) FontWeight.ExtraBold else FontWeight.Bold,
                color = if (routeChange == StopRouteChange.SKIPPED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
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
            Text(platform?.let { stringResource(R.string.platform_label, it) } ?: stringResource(R.string.platform_to_confirm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(scheduled, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (!operationalStatusVerified) {
                Text(stringResource(R.string.operational_status_unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            } else if (delay > 0) {
                Text(stringResource(R.string.delay_minutes, delay), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            if (operationalStatusVerified && expected != scheduled) {
                Text(stringResource(R.string.expected_time_short, expected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!completed && onSetAlarm != null) {
                TextButton(onClick = onSetAlarm) {
                    Text(stringResource(R.string.action_stop_alarm))
                }
            }
        }
    }
}

private val TIMELINE_START = 20.dp
private val TIMELINE_WIDTH = 28.dp
private val TIMELINE_TOP = 10.dp
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
            .semantics { contentDescription = accessibilityLabel },
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
