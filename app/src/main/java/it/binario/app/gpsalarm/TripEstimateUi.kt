package it.binario.app.gpsalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import it.binario.app.R
import it.binario.app.data.TrainService
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.notifications.FollowedTripLeg
import it.binario.app.notifications.TripLiveText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The clock of the progress lines: the countdown by timetable moves on without a new fix. */
@Composable
private fun rememberProgressClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PROGRESS_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private const val PROGRESS_TICK_MILLIS = 30_000L

/**
 * "Scendi tra N fermate" and the position estimate of a running GPS session, as in its notification. Screen readers
 * get the estimate with words instead of "+" and "~". [officialDelayMinutes] is null without official live data.
 */
@Composable
internal fun GpsTrainProgressLines(target: GpsArrivalAlarmTarget, track: GpsTrainTrack?, officialDelayMinutes: Int?, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val now = rememberProgressClock()
    val countdown = target.route?.let { GpsTrainRoutes.countdown(it, track, now) }
    val position = TripLiveText.position(resources, track, officialDelayMinutes, now, includeWaiting = target.isEstimate)
    val spoken = TripLiveText.position(resources, track, officialDelayMinutes, now, includeWaiting = target.isEstimate, accessible = true)
    if (countdown == null && position == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        countdown?.let { Text(TripLiveText.countdown(resources, it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
        if (position != null) {
            Text(
                position,
                modifier = Modifier.semantics { contentDescription = spoken ?: position },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * "Stima dalla tua posizione" under the followed-trip notification of the train detail: while the trip is in
 * progress it starts the existing location foreground service, which never rings, compares the device position with
 * the train's route and ends ten minutes after the arrival or with the trip. It needs precise location, asked for
 * only when the user turns it on. A GPS alarm for the same train already provides the estimate.
 */
@Composable
internal fun TripPositionEstimateToggle(
    service: TrainService,
    leg: FollowedTripLeg,
    locate: (String) -> GpsAlarmStation?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val runtime by GpsArrivalAlarmRuntime.state.collectAsState()
    val active = runtime as? GpsArrivalAlarmState.Active
    val running = active?.takeIf { it.target.route?.isFor(service.number) == true }
    val otherAlarm = active != null && running == null && !active.target.isEstimate
    // Matching the stops to the catalogue runs off the main thread; until it is done the switch waits.
    val lookup by produceState<RouteLookup>(RouteLookup.Pending, service.id, service.stops, leg) {
        value = withContext(Dispatchers.Default) {
            val board = LiveStopTimes.stopNamed(service.stops, leg.boardStation).takeIf { it >= 0 } ?: 0
            val alight = LiveStopTimes.stopNamed(service.stops, leg.alightStation).takeIf { it > board } ?: service.stops.lastIndex
            RouteLookup.Done(GpsTrainRoutes.build(service.number, service.stops, board, alight, board, leg.departure, locate)?.takeIf { it.estimable })
        }
    }
    val route = (lookup as? RouteLookup.Done)?.route
    var feedback by remember(service.number) { mutableStateOf<String?>(null) }
    val official = service.takeIf { it.operationalStatusVerified }?.let { (it.officialDelayMinutes ?: it.delayMinutes).coerceAtLeast(0) }
    val now = System.currentTimeMillis()
    val inProgress = GpsTrainTracking.canStartEstimate(leg.departure.toEpochMilli(), leg.arrival.toEpochMilli(), official, now)
    val start = {
        val ready = route
        val target = ready?.let { GpsArrivalAlarmTarget.estimate(it, service.destination, System.currentTimeMillis()) }
        feedback = when {
            target == null -> resources.getString(R.string.trip_estimate_unavailable_route)
            !locationEnabled(context) -> resources.getString(R.string.trip_estimate_location_disabled)
            !GpsArrivalAlarmController.startEstimate(context, target) -> resources.getString(R.string.trip_estimate_start_failed)
            else -> null
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) start() else feedback = resources.getString(R.string.trip_estimate_precise_required)
    }
    val enabled = running?.target?.isEstimate == true || (running == null && !otherAlarm && inProgress && route != null)
    val note = when {
        running != null && !running.target.isEstimate -> stringResource(R.string.trip_estimate_via_alarm)
        running == null && !inProgress -> stringResource(R.string.trip_estimate_unavailable_time)
        running == null && lookup is RouteLookup.Done && route == null -> stringResource(R.string.trip_estimate_unavailable_route)
        else -> null
    }
    Column(modifier.testTag("detail-trip-estimate"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = running != null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { enable ->
                    if (!enable) {
                        GpsArrivalAlarmController.stopEstimate(context)
                        feedback = null
                    } else if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                        start()
                    } else {
                        // Fine and coarse together, as Android 12+ requires; only precise location makes an estimate.
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.trip_estimate_toggle), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.trip_estimate_toggle_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The whole row toggles; the switch only mirrors the state.
            Switch(checked = running != null, onCheckedChange = null, enabled = enabled)
        }
        running?.let { GpsTrainProgressLines(it.target, it.track, official) }
        (feedback ?: note)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private sealed interface RouteLookup {
    data object Pending : RouteLookup
    data class Done(val route: GpsTrainRoute?) : RouteLookup
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun locationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true
