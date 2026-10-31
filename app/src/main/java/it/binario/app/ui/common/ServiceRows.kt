package it.binario.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.UiMessage
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.localized
import it.binario.app.updateTimeMessage

// Rows, status lines and fields shared by the planner results, the departures and the detail.

/** Column titles of the compact rows; minimum widths keep them aligned while letting large text grow. */
@Composable
internal fun BoardColumnHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.board_column_time), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = TIME_COLUMN))
        Text(stringResource(R.string.board_column_service), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.board_column_platform), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = SIDE_COLUMN), textAlign = TextAlign.End)
        Text(stringResource(R.string.board_column_status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = SIDE_COLUMN), textAlign = TextAlign.End)
    }
}

private val TIME_COLUMN = 76.dp
private val SIDE_COLUMN = 50.dp

/**
 * How demonstration data is shown: the timetable only. Delays, expected times, platforms, progress and
 * route changes of the local demonstration catalogue are never presented as if they were real.
 */
internal fun TrainService.presentedAsDemonstration(): TrainService = copy(
    expectedDeparture = scheduledDeparture,
    platform = null,
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clearAndSetSemantics {
                contentDescription = statusContentDescription
            },
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A compact, neutral row of a departures or results list (the board highlights its next service separately). */
@Composable
internal fun DepartureListRow(service: TrainService, isDemonstration: Boolean = false, onOpen: () -> Unit) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = serviceContentDescription
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.widthIn(min = TIME_COLUMN).padding(end = 8.dp)) {
            Text(shown.scheduledDeparture, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            when {
                !shown.operationalStatusVerified -> Text(stringResource(R.string.service_scheduled_lowercase), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.status == ServiceStatus.CANCELLED -> Text(stringResource(R.string.service_status_cancelled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                shown.delayMinutes > 0 -> Text(stringResource(R.string.delay_minutes, shown.delayMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                else -> Text(stringResource(R.string.service_on_time_lowercase), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            if (shown.operationalStatusVerified && shown.expectedDeparture != shown.scheduledDeparture) {
                Text(stringResource(R.string.expected_time_short, shown.expectedDeparture), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(shown.destination, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.service_operator_category_number, shown.displayOperator, shown.trainCategory.localizedLabel(), shown.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(shown.platform?.let { stringResource(R.string.platform_short, it) } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = SIDE_COLUMN)) {
            Text(compactStatusLabel(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SearchInfoState(title: String, message: String, openRoutes: (() -> Unit)? = null) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) })
        if (matches.isNotEmpty() && (matches.none { it.name.equals(value, ignoreCase = true) } || matches.any { it.id.startsWith("manual:") })) {
            matches.take(5).forEach { station ->
                TextButton(onClick = { onSelect(station) }, modifier = Modifier.fillMaxWidth()) {
                    val stationLabel = if (station.id.startsWith("manual:")) {
                        stringResource(R.string.station_suggestion_free_text, station.name)
                    } else {
                        buildString {
                            append(station.name)
                            station.railwayCode?.let { append(" · ").append(it) }
                        }
                    }
                    Icon(Icons.Default.Train, contentDescription = null, modifier = Modifier.size(20.dp))
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
        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.service_status_scheduled_badge))
    } else when (service.status) {
        ServiceStatus.ON_TIME -> Triple(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary, stringResource(R.string.operational_on_time))
        ServiceStatus.DELAYED -> Triple(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, stringResource(R.string.delay_minutes, service.delayMinutes))
        ServiceStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, stringResource(R.string.service_status_cancelled))
    }
    Surface(color = container, contentColor = foreground, shape = RoundedCornerShape(999.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun compactStatusLabel(service: TrainService): String {
    if (!service.operationalStatusVerified) return stringResource(R.string.service_status_scheduled_compact)
    return when (service.status) {
        ServiceStatus.ON_TIME -> stringResource(R.string.operational_on_time)
        ServiceStatus.DELAYED -> stringResource(R.string.delay_minutes, service.delayMinutes)
        ServiceStatus.CANCELLED -> stringResource(R.string.service_status_cancelled)
    }
}
