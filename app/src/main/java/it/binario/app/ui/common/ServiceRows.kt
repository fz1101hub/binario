package it.binario.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.UiMessage
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.gtfs.UrbanStopBadge
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.localized
import it.binario.app.updateTimeMessage

// Rows, status lines and fields shared by the planner results, the departures and the detail.

private val TIME_COLUMN = 64.dp
private val SIDE_COLUMN = 56.dp

/**
 * How demonstration data is shown: the timetable only. Delays, expected times, platforms, progress and
 * route changes of the local demonstration catalogue are never presented as if they were real.
 */
internal fun TrainService.presentedAsDemonstration(): TrainService = copy(
    expectedDeparture = scheduledDeparture,
    platform = null,
    scheduledPlatform = null,
    delayMinutes = 0,
    officialDelayMinutes = null,
    status = ServiceStatus.ON_TIME,
    operationalStatusVerified = false,
    lastOperationalDetection = null,
    detectionDelayMinutes = null,
    routeChange = null,
    routeChangeSourceIds = emptySet(),
    stops = stops.map { stop ->
        stop.copy(
            expected = stop.scheduled,
            platform = null,
            scheduledPlatform = null,
            delayMinutes = 0,
            completed = false,
            routeChange = StopRouteChange.NONE,
            operationalStatusVerified = false
        )
    }
)

/**
 * Provenance of the departures above the rows: demonstration, unavailable, live or saved copy, always as
 * text (the icon colour only repeats it), with source, update time and quality visible.
 */
@Composable
internal fun DataStatusLine(
    freshness: Freshness?,
    provider: String,
    confidence: String,
    refreshedAt: Long,
    isDemonstration: Boolean,
    isCachedCopy: Boolean,
    message: UiMessage?
) {
    val messageText = message?.localized()
    val label = when {
        isDemonstration -> stringResource(R.string.data_status_demo)
        freshness == Freshness.UNAVAILABLE || freshness == null -> stringResource(R.string.data_status_unavailable)
        else -> stringResource(R.string.data_status_live, freshness.localizedLabel())
    }
    val color = when {
        isDemonstration -> MaterialTheme.colorScheme.tertiary
        freshness == Freshness.UNAVAILABLE || freshness == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val updated = updateTimeMessage(refreshedAt).localized()
    val sourceLine = if (refreshedAt > 0L) stringResource(R.string.provider_updated_at, provider, updated) else provider
    val qualityLine = stringResource(R.string.data_status_quality, confidence)
    val cachedLine = if (isCachedCopy && !isDemonstration) stringResource(R.string.data_status_cached, updated) else null
    val statusContentDescription = stringResource(
        R.string.accessibility_data_status,
        listOfNotNull(label, cachedLine).joinToString(". "),
        provider,
        confidence,
        updated,
        messageText?.takeIf(String::isNotBlank)?.let { stringResource(R.string.accessibility_optional_message, it) }.orEmpty()
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clearAndSetSemantics {
                contentDescription = statusContentDescription
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
            cachedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
            Text(sourceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(qualityLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            messageText?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A notice in the style of the data status card ([DataStatusLine]): info icon, optional title, text, optional action
 * and extra content (a progress bar). Used for the scheduled-only, coverage and empty notices of the journeys search.
 */
@Composable
internal fun InfoNoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Title and text are read together; the action stays its own target.
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actionLabel?.let { label ->
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
            content()
        }
    }
}

/**
 * A compact, neutral row of a departures list (the board highlights its next service separately): time and
 * state, destination and train, platform. [selected] tints the row whose detail is open beside the list.
 */
@Composable
internal fun DepartureListRow(service: TrainService, isDemonstration: Boolean = false, selected: Boolean = false, onOpen: () -> Unit) {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val serviceState = if (shown.operationalStatusVerified) {
        stringResource(R.string.service_state_live, shown.status.localizedLabel())
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val serviceContentDescription = stringResource(
        R.string.accessibility_open_service,
        shown.localizedAccessibleSummary(),
        serviceState
    )
    val selectedState = if (selected) stringResource(R.string.accessibility_row_selected) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin, vertical = 4.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp)
            )
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = serviceContentDescription
                selectedState?.let { stateDescription = it }
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.widthIn(min = TIME_COLUMN).padding(end = 10.dp)) {
            Text(shown.scheduledDeparture, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures), fontWeight = FontWeight.Bold)
            when {
                !shown.operationalStatusVerified -> Text(stringResource(R.string.service_scheduled_lowercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.status == ServiceStatus.CANCELLED -> Text(stringResource(R.string.service_status_cancelled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                shown.delayMinutes > 0 -> Text(stringResource(R.string.delay_minutes, shown.delayMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                else -> Text(stringResource(R.string.service_on_time_lowercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (shown.operationalStatusVerified && shown.expectedDeparture != shown.scheduledDeparture) {
                Text(stringResource(R.string.expected_time_short, shown.expectedDeparture), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(shown.destination, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.service_category_number, shown.trainCategory.localizedLabel(), shown.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.routeChange?.let {
                Text(
                    routeChangeLabel(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = routeChangeAccent(it),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = SIDE_COLUMN)) {
            Text(
                platformText(shown.platform, shown.scheduledPlatform) ?: "—",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = platformColor(shown.platform, shown.scheduledPlatform, MaterialTheme.colorScheme.onSurface),
                textAlign = TextAlign.End
            )
            Text(compactStatusLabel(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

/**
 * A route search solution as a card of the render: "17:40 → 22:05", the scheduled duration, the train in
 * capitals, "Diretto" (or the number of changes the source published) and the platform. Demonstration data
 * shows the timetable only.
 */
@Composable
internal fun ResultCard(
    service: TrainService,
    isDemonstration: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    val locale = LocalConfiguration.current.locales[0]
    val arrival = JourneyTimes.arrival(shown)
    val duration = JourneyTimes.durationMinutes(shown)
    val serviceState = if (shown.operationalStatusVerified) {
        stringResource(R.string.service_state_live, shown.status.localizedLabel())
    } else {
        stringResource(R.string.service_state_scheduled)
    }
    val description = listOfNotNull(
        stringResource(R.string.accessibility_open_service, shown.localizedAccessibleSummary(), serviceState),
        arrival?.let { stringResource(R.string.service_scheduled_arrival, it) },
        duration?.let { durationAccessibleLabel(it) },
        if (selected) stringResource(R.string.accessibility_row_selected) else null
    ).joinToString(". ")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            },
        onClick = onOpen,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(shown.scheduledDeparture, arrival).joinToString(" → "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularFigures)
                )
                duration?.let {
                    Text(JourneyTimes.durationLabel(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(R.string.service_category_number, shown.trainCategory.localizedLabel(), shown.number).uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
            if (shown.operationalStatusVerified && shown.status != ServiceStatus.ON_TIME) {
                Text(
                    compactStatusLabel(shown),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (shown.status == ServiceStatus.CANCELLED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                )
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangesBadge(shown.transferCount, Modifier.weight(1f))
                Text(
                    platformText(shown.platform, shown.scheduledPlatform, long = true) ?: stringResource(R.string.platform_label, "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = platformColor(shown.platform, shown.scheduledPlatform, MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }
    }
}

/**
 * "Diretto" with a green check, or "1 cambio" / "N cambi" in the attention red of the render; the text always says
 * what the colour means.
 */
@Composable
internal fun ChangesBadge(changes: Int, modifier: Modifier = Modifier) {
    val direct = changes <= 0
    val color = if (direct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (direct) Icons.Default.CheckCircle else Icons.Default.SyncAlt, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(changesLabel(changes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

/** "Diretto" or "N cambi", shared by direct trains and journeys with changes. */
@Composable
internal fun changesLabel(changes: Int): String =
    if (changes <= 0) stringResource(R.string.journey_direct) else pluralStringResource(R.plurals.journey_changes, changes, changes)

/** "4 ore 25 min" for screen readers. */
@Composable
internal fun journeyDurationSpoken(minutes: Long): String {
    val hours = (minutes / 60).toInt()
    val rest = (minutes % 60).toInt()
    return listOfNotNull(
        if (hours > 0) pluralStringResource(R.plurals.journey_duration_hours, hours, hours) else null,
        if (rest > 0 || hours == 0) pluralStringResource(R.plurals.minutes_short, rest, rest) else null
    ).joinToString(" ")
}

/** Spoken duration ("Durata 4 ore 25 min") next to the visible "4h 25min". */
@Composable
internal fun durationAccessibleLabel(minutes: Int): String =
    stringResource(R.string.service_duration_accessible, journeyDurationSpoken(minutes.toLong()))

@Composable
internal fun SearchInfoState(title: String, message: String, openRoutes: (() -> Unit)? = null) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        openRoutes?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.action_open_routes)) } }
    }
}

@Composable
internal fun StationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    matches: List<Station>,
    onSelect: (Station) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
        if (matches.isNotEmpty() && (matches.none { it.name.equals(value, ignoreCase = true) } || matches.any { it.id.startsWith("manual:") })) {
            val urbanStops = BinarioDataGraph.urbanStopIndex(LocalContext.current)
            matches.take(5).forEach { station ->
                TextButton(onClick = { onSelect(station) }, modifier = Modifier.fillMaxWidth()) {
                    // A metro or tram stop of a city pack: "M"/"T" badge and the city instead of a railway code.
                    val badge = urbanStops.badgeOf(station.id)
                    val stationLabel = if (station.id.startsWith("manual:")) {
                        stringResource(R.string.station_suggestion_free_text, station.name)
                    } else {
                        buildString {
                            append(station.name)
                            if (badge != null) station.city.takeIf { it.isNotBlank() && it != station.name }?.let { append(" · ").append(it) }
                            else station.railwayCode?.let { append(" · ").append(it) }
                        }
                    }
                    if (badge != null) UrbanStopBadgeMark(badge) else Icon(Icons.Default.Train, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stationLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun routeChangeLabel(routeChange: OperationalRouteChange): String = when (routeChange.kind) {
    RouteChangeKind.DIVERTED -> stringResource(R.string.route_change_label_diverted)
    RouteChangeKind.SKIPPED_STOPS -> stringResource(R.string.route_change_label_skipped)
    RouteChangeKind.ADDED_STOPS -> stringResource(R.string.route_change_label_added)
    RouteChangeKind.SHORT_TURN -> stringResource(R.string.route_change_label_short_turn)
    RouteChangeKind.REPLACEMENT_SERVICE -> stringResource(R.string.route_change_label_replacement)
    RouteChangeKind.OTHER -> stringResource(R.string.route_change_label_other)
}

@Composable
internal fun routeChangeAccent(routeChange: OperationalRouteChange) =
    if (routeChange.kind in setOf(RouteChangeKind.SKIPPED_STOPS, RouteChangeKind.SHORT_TURN, RouteChangeKind.REPLACEMENT_SERVICE)) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }

@Composable
internal fun ServiceStatusBadge(service: TrainService) {
    val (container, foreground, text) = if (!service.operationalStatusVerified) {
        Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, stringResource(R.string.service_status_scheduled_badge))
    } else when (service.status) {
        ServiceStatus.ON_TIME -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, stringResource(R.string.operational_on_time))
        ServiceStatus.DELAYED -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, stringResource(R.string.delay_minutes, service.delayMinutes))
        ServiceStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, stringResource(R.string.service_status_cancelled))
    }
    Pill(text, container = container, content = foreground)
}

@Composable
internal fun compactStatusLabel(service: TrainService): String {
    if (!service.operationalStatusVerified) return stringResource(R.string.service_status_scheduled_compact)
    return when (service.status) {
        ServiceStatus.ON_TIME -> stringResource(R.string.operational_on_time)
        ServiceStatus.DELAYED -> stringResource(R.string.delay_minutes, service.delayMinutes)
        ServiceStatus.CANCELLED -> stringResource(R.string.service_status_cancelled)
    }
}

/** The "M" / "T" / "F" mark of a city stop in the station suggestions, read as "Fermata della metropolitana". */
@Composable
internal fun UrbanStopBadgeMark(badge: UrbanStopBadge) {
    val description = stringResource(
        when (badge) {
            UrbanStopBadge.METRO -> R.string.station_badge_metro
            UrbanStopBadge.TRAM -> R.string.station_badge_tram
            UrbanStopBadge.FUNICULAR -> R.string.station_badge_funicular
        }
    )
    Surface(
        modifier = Modifier.size(20.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(badge.letter, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}
