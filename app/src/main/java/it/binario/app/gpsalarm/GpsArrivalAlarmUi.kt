package it.binario.app.gpsalarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.StopAlarmPlan

/**
 * Options of the stop alarm ("Sveglia fermata"): the time-based alarm is always set; the GPS
 * part is an explicit opt-in whose text is also the in-app rationale shown before Android asks
 * for location access.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StopAlarmOptionsDialog(flow: GpsArrivalAlarmFlow, onScheduleTimeAlarm: (StopAlarmPlan) -> Unit) {
    val offer = flow.offer ?: return
    val runtime by GpsArrivalAlarmRuntime.state.collectAsState()
    var useLocation by rememberSaveable(offer) { mutableStateOf(false) }
    var radiusMeters by rememberSaveable(offer) { mutableIntStateOf(offer.initialRadiusMeters) }
    val locationChosen = useLocation && offer.locationAvailable
    AlertDialog(
        onDismissRequest = flow::dismissOffer,
        title = { Text(stringResource(R.string.stop_alarm_dialog_title, offer.plan.station)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stop_alarm_dialog_time, offer.plan.expectedTime), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = locationChosen,
                            enabled = offer.locationAvailable,
                            role = Role.Switch,
                            onValueChange = { useLocation = it }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.gps_alarm_option), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.gps_alarm_option_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = locationChosen, onCheckedChange = null, enabled = offer.locationAvailable)
                }
                when (offer.availability) {
                    GpsAlarmAvailability.NO_COORDINATES -> GpsAlarmNote(stringResource(R.string.gps_alarm_unavailable_no_coordinates))
                    GpsAlarmAvailability.TOO_EARLY -> {
                        val hours = GpsArrivalAlarmPolicy.maxDurationHours
                        GpsAlarmNote(pluralStringResource(R.plurals.gps_alarm_unavailable_too_early, hours, hours))
                    }
                    GpsAlarmAvailability.AVAILABLE -> Unit
                }
                if (locationChosen) {
                    Text(stringResource(R.string.gps_alarm_radius_label), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GpsArrivalAlarmPolicy.RADIUS_OPTIONS_METERS.forEach { option ->
                            FilterChip(
                                selected = radiusMeters == option,
                                onClick = { radiusMeters = option },
                                label = { Text(radiusLabel(option)) }
                            )
                        }
                    }
                    GpsAlarmNote(stringResource(R.string.gps_alarm_high_speed_hint))
                    GpsAlarmNote(stringResource(R.string.gps_alarm_privacy_rationale))
                    GpsAlarmNote(stringResource(R.string.gps_alarm_approximate_note))
                    GpsAlarmNote(stringResource(R.string.gps_alarm_restart_note))
                    (runtime as? GpsArrivalAlarmState.Active)?.target
                        ?.takeUnless { it.isFor(offer.plan.trainNumber, offer.plan.station) }
                        ?.let { GpsAlarmNote(stringResource(R.string.gps_alarm_replaces, it.stopName)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { flow.confirm(locationChosen, radiusMeters, onScheduleTimeAlarm) }) {
                Text(stringResource(R.string.action_set_stop_alarm))
            }
        },
        dismissButton = {
            TextButton(onClick = flow::dismissOffer) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Status of the GPS alarm in the train detail: running, interrupted by the system, or just ended. */
@Composable
internal fun GpsArrivalAlarmPanel(flow: GpsArrivalAlarmFlow, modifier: Modifier = Modifier) {
    val runtime by GpsArrivalAlarmRuntime.state.collectAsState()
    val storeRevision = flow.storeRevision
    val interrupted = remember(runtime, storeRevision) { flow.interruptedTarget(runtime) }
    val active = runtime as? GpsArrivalAlarmState.Active
    val ended = (runtime as? GpsArrivalAlarmState.Ended)?.takeIf { it.target != null }
    val notice = flow.notice
    if (active == null && interrupted == null && ended == null && notice == null) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active != null) {
                val radius = radiusLabel(GpsArrivalAlarmPolicy.effectiveRadiusMeters(active.target.radiusMeters, active.precise))
                Text(stringResource(R.string.gps_alarm_active_title, active.target.stopName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(if (active.precise) R.string.gps_alarm_active_precise else R.string.gps_alarm_active_approximate, radius),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(stringResource(R.string.gps_alarm_restart_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = flow::stopActive) { Text(stringResource(R.string.action_gps_alarm_stop)) }
            } else if (interrupted != null) {
                Text(stringResource(R.string.gps_alarm_interrupted, interrupted.stopName), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { flow.resume(interrupted) }) { Text(stringResource(R.string.action_gps_alarm_resume)) }
                    TextButton(onClick = flow::dismissInterrupted) { Text(stringResource(R.string.action_gps_alarm_dismiss)) }
                }
            }
            val message = notice?.let { noticeMessage(it) } ?: ended?.let { endedMessage(it) }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = flow::acknowledge) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun GpsAlarmNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun radiusLabel(radiusMeters: Int): String {
    val kilometres = radiusMeters / 1_000
    return pluralStringResource(R.plurals.gps_alarm_radius_km, kilometres, kilometres)
}

@Composable
private fun noticeMessage(notice: GpsAlarmNotice): String = when (notice.kind) {
    GpsAlarmNoticeKind.PERMISSION_DENIED -> stringResource(R.string.gps_alarm_permission_denied)
    GpsAlarmNoticeKind.LOCATION_DISABLED -> stringResource(R.string.gps_alarm_location_disabled)
    GpsAlarmNoticeKind.START_FAILED -> stringResource(R.string.gps_alarm_start_failed, notice.stopName)
    GpsAlarmNoticeKind.NOTIFICATIONS_REQUIRED -> stringResource(R.string.stop_alarm_permission_required)
}

@Composable
private fun endedMessage(ended: GpsArrivalAlarmState.Ended): String {
    val stop = ended.target?.stopName.orEmpty()
    return when (ended.reason) {
        GpsArrivalAlarmEndReason.TRIGGERED -> stringResource(R.string.gps_alarm_ended_triggered, stop)
        GpsArrivalAlarmEndReason.CANCELLED -> stringResource(R.string.gps_alarm_ended_cancelled, stop)
        GpsArrivalAlarmEndReason.EXPIRED -> stringResource(R.string.gps_alarm_ended_expired, stop)
        GpsArrivalAlarmEndReason.PERMISSION_REVOKED -> stringResource(R.string.gps_alarm_ended_permission, stop)
        GpsArrivalAlarmEndReason.LOCATION_UNAVAILABLE -> stringResource(R.string.gps_alarm_ended_unavailable, stop)
        GpsArrivalAlarmEndReason.START_FAILED -> stringResource(R.string.gps_alarm_start_failed, stop)
        GpsArrivalAlarmEndReason.TIME_ALARM_FIRED -> stringResource(R.string.gps_alarm_ended_time_alarm, stop)
    }
}
