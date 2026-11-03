package it.binario.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.binario.app.FavouriteTrainStore
import it.binario.app.R
import it.binario.app.data.RailTime
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.gpsalarm.GpsAlarmStation
import it.binario.app.gpsalarm.TripPositionEstimateToggle
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.journeys.journeyLegLabel
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun needsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * Runs [action] once notifications may be posted: at once when allowed, else after the system permission dialog;
 * a refusal calls [onDenied]. The permission is asked only when the user turns a live notification on.
 */
@Composable
private fun rememberWithNotificationPermission(onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else onDenied()
    }
    return { action ->
        if (needsPermission(context)) {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The whole row toggles; the switch only mirrors the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)

/**
 * The opt-in ongoing notification of a train followed with "Segui viaggio": from 30 minutes before the departure
 * to 30 minutes after the arrival, refreshed every 15 minutes, with "Aggiorna" and "Interrompi".
 */
@Composable
internal fun FollowedTripNotificationToggle(
    service: TrainService,
    modifier: Modifier = Modifier,
    /** Catalogue coordinates of a stop name; with it the toggle offers "Stima dalla tua posizione". */
    locate: ((String) -> GpsAlarmStation?)? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val label = stringResource(R.string.service_category_number, service.trainCategory.localizedLabel(), service.number)
    var active by remember(service.number) { mutableStateOf(FollowedTripScheduler.current(context)?.let { followsOnly(it, service) } == true) }
    var feedback by remember(service.number) { mutableStateOf<String?>(null) }
    val withPermission = rememberWithNotificationPermission { feedback = resources.getString(R.string.settings_live_updates_permission) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleRow(
            title = stringResource(R.string.followed_trip_notification_toggle),
            description = stringResource(R.string.followed_trip_notification_toggle_description),
            checked = active,
            onChange = { enable ->
                if (!enable) {
                    FollowedTripScheduler.stop(context)
                    active = false
                    feedback = null
                } else withPermission {
                    val trip = FollowedTrip.fromService(service, label, LocalDate.now(RailTime.defaultZone))
                    if (trip == null || !FollowedTripPlanner.isUpcomingOrRunning(trip, Instant.now())) {
                        feedback = resources.getString(R.string.followed_trip_notification_unavailable)
                    } else {
                        FollowedTripScheduler.start(context, trip)
                        active = true
                        val start = FollowedTripPlanner.windowStart(trip)
                        feedback = start.takeIf { it.isAfter(Instant.now()) }?.let {
                            resources.getString(R.string.followed_trip_notification_pending, it.atZone(RailTime.defaultZone).format(clock))
                        }
                    }
                }
            },
            modifier = Modifier.testTag("detail-follow-notification")
        )
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val leg = remember(active) { FollowedTripScheduler.current(context)?.legs?.singleOrNull()?.takeIf { active } }
        if (locate != null && leg != null) TripPositionEstimateToggle(service, leg, locate)
    }
}

private fun followsOnly(trip: FollowedTrip, service: TrainService): Boolean =
    trip.legs.size == 1 && TrainAlertWatch.key(trip.legs.single().number) == TrainAlertWatch.key(service.number)

/**
 * "Segui con notifica" for a journey with changes: the ongoing notification follows its trains and shows the risk of
 * each change ("Coincidenza a rischio") from live data. Shown only for a journey that has not ended.
 */
@Composable
internal fun FollowJourneyNotificationControl(row: JourneyOptionRow, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val labels = row.legs.map { journeyLegLabel(it) }
    val candidate = remember(row.id, labels) { FollowedTrip.fromJourney(row) { index -> labels.getOrElse(index) { "" } } } ?: return
    if (!FollowedTripPlanner.isUpcomingOrRunning(candidate, Instant.now())) return
    val signature = remember(row.id) { candidate.legs.map { it.number to it.departure } }
    var active by remember(row.id) {
        mutableStateOf(FollowedTripScheduler.current(context)?.let { trip -> trip.legs.map { it.number to it.departure } == signature } == true)
    }
    var feedback by remember(row.id) { mutableStateOf<String?>(null) }
    val withPermission = rememberWithNotificationPermission { feedback = resources.getString(R.string.settings_live_updates_permission) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.followed_trip_journey_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                if (active) {
                    FollowedTripScheduler.stop(context)
                    active = false
                    feedback = null
                } else withPermission {
                    FollowedTripScheduler.start(context, candidate)
                    active = true
                    val start = FollowedTripPlanner.windowStart(candidate)
                    feedback = start.takeIf { it.isAfter(Instant.now()) }?.let {
                        resources.getString(R.string.followed_trip_notification_pending, it.atZone(RailTime.defaultZone).format(clock))
                    }
                }
            },
            modifier = Modifier.testTag("journey-follow-notification")
        ) {
            Text(stringResource(if (active) R.string.followed_trip_action_stop else R.string.followed_trip_journey_follow))
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The bell of the train detail: notify the new ViaggiaTreno notices of this favourite or followed train. Shown only
 * for trains the per-train notice sources know; turning it on also turns on the switch in the settings.
 */
@Composable
internal fun TrainAlertToggle(service: TrainService, eligible: Boolean, modifier: Modifier = Modifier) {
    if (!eligible) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val noticeContext = remember(service.id) { TrainNoticeContext.from(service) }
    if (noticeContext == null || !noticeContext.matchesTrenitaliaNumbers || !remember { BinarioDataGraph.trainAlertNoticesService().hasTrainSources }) return
    val label = stringResource(R.string.service_category_number, service.trainCategory.localizedLabel(), service.number)
    val store = remember(context) { TrainAlertStore(context) }
    var subscribed by remember(service.number) { mutableStateOf(store.isSubscribed(service.number) && LiveNotificationSettings(context).trainAlertsEnabled) }
    var feedback by remember(service.number) { mutableStateOf<String?>(null) }
    val withPermission = rememberWithNotificationPermission { feedback = resources.getString(R.string.settings_live_updates_permission) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleRow(
            title = stringResource(R.string.train_alerts_toggle),
            description = stringResource(R.string.train_alerts_toggle_description),
            checked = subscribed,
            onChange = { enable ->
                if (!enable) {
                    store.unsubscribe(service.number)
                    subscribed = false
                    TrainAlertScheduler.reschedule(context)
                } else withPermission {
                    TrainAlertWatch.subscriptionOf(noticeContext, service.number, service.scheduledDeparture, label, Instant.now())?.let { subscription ->
                        store.subscribe(subscription)
                        LiveNotificationSettings(context).trainAlertsEnabled = true
                        subscribed = true
                        feedback = null
                        TrainAlertScheduler.reschedule(context)
                    }
                }
            },
            modifier = Modifier.testTag("detail-train-alerts")
        )
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * "Avvisami quando sta arrivando" for a favourite train (Info tab of the detail): a notification [ApproachAlert.leadMinutes]
 * before the train's expected time at a station of its route, by default the favourite's saved origin. The
 * notification permission is asked only when the user turns it on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApproachAlertControl(service: TrainService, eligible: Boolean, modifier: Modifier = Modifier) {
    if (!eligible) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember(context) { ApproachAlertStore(context) }
    val stops = remember(service.id, service.stops) {
        service.stops.filter { it.routeChange != StopRouteChange.SKIPPED && LiveStopTimes.parse(it.scheduled) != null }
    }
    if (stops.isEmpty()) return
    val label = stringResource(R.string.service_category_number, service.trainCategory.localizedLabel(), service.number)
    var alert by remember(service.number) { mutableStateOf(store.alert(service.number)) }
    var choosing by remember(service.number) { mutableStateOf(false) }
    var feedback by remember(service.number) { mutableStateOf<String?>(null) }
    val withPermission = rememberWithNotificationPermission { feedback = resources.getString(R.string.settings_live_updates_permission) }
    val defaultStop = remember(service.number, stops) {
        val origin = FavouriteTrainStore(context).all().firstOrNull { it.number.equals(service.number, ignoreCase = true) }?.origin.orEmpty()
        stops.firstOrNull { origin.isNotBlank() && LiveStopTimes.namesMatch(it.station, origin) } ?: stops.first()
    }
    val save = { stop: TrainStop, lead: Int ->
        val updated = ApproachAlert(
            number = service.number.trim(),
            label = label,
            station = stop.station,
            stationTime = stop.scheduled.trim(),
            departure = service.scheduledDeparture.trim().takeIf { LiveStopTimes.parse(it) != null } ?: stops.first().scheduled.trim(),
            leadMinutes = lead,
            createdAt = Instant.now()
        )
        store.save(updated)
        alert = updated
        feedback = null
        ApproachAlertScheduler.reschedule(context)
    }
    Column(modifier.testTag("detail-approach-alert"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow(
            title = stringResource(R.string.approach_alert_toggle),
            description = stringResource(R.string.approach_alert_toggle_description),
            checked = alert != null,
            onChange = { enable ->
                if (!enable) {
                    store.remove(service.number)
                    alert = null
                    feedback = null
                    ApproachAlertScheduler.reschedule(context)
                } else withPermission { save(defaultStop, ApproachAlertPlanner.DEFAULT_LEAD) }
            }
        )
        alert?.let { current ->
            val stationDescription = stringResource(R.string.accessibility_approach_alert_station, current.station)
            Box {
                OutlinedButton(onClick = { choosing = true }, modifier = Modifier.semantics { contentDescription = stationDescription }) {
                    Text(stringResource(R.string.approach_alert_station, current.station))
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    stops.forEach { stop ->
                        DropdownMenuItem(
                            text = { Text("${stop.station} · ${stop.scheduled.trim()}") },
                            onClick = {
                                choosing = false
                                save(stop, current.leadMinutes)
                            }
                        )
                    }
                }
            }
            Text(stringResource(R.string.approach_alert_lead), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApproachAlertPlanner.LEAD_OPTIONS.forEach { lead ->
                    FilterChip(
                        selected = current.leadMinutes == lead,
                        onClick = { save(stops.firstOrNull { it.station == current.station } ?: defaultStop, lead) },
                        label = { Text(pluralStringResource(R.plurals.minutes_short, lead, lead)) }
                    )
                }
            }
            Text(
                stringResource(R.string.approach_alert_summary, pluralStringResource(R.plurals.minutes_short, current.leadMinutes, current.leadMinutes), current.station),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** "Notifiche in tempo reale" in Impostazioni → Avvisi: the three switches of the live features. */
@Composable
fun LiveUpdatesSettingsPanel() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = remember(context) { LiveNotificationSettings(context) }
    var trainAlerts by remember { mutableStateOf(settings.trainAlertsEnabled) }
    var strikes by remember { mutableStateOf(settings.strikeReminderEnabled) }
    var punctuality by remember { mutableStateOf(settings.punctualityRecordingEnabled) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val withPermission = rememberWithNotificationPermission { feedback = resources.getString(R.string.settings_live_updates_permission) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("settings-live-updates"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.settings_live_updates_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        ToggleRow(
            title = stringResource(R.string.settings_train_alerts_title),
            description = stringResource(R.string.settings_train_alerts_description),
            checked = trainAlerts,
            onChange = { enable ->
                val apply = {
                    settings.trainAlertsEnabled = enable
                    trainAlerts = enable
                    TrainAlertScheduler.reschedule(context)
                }
                if (enable) withPermission(apply) else apply()
            }
        )
        if (BinarioDataGraph.strikeCalendar() != null) {
            ToggleRow(
                title = stringResource(R.string.settings_strike_reminder_title),
                description = stringResource(R.string.settings_strike_reminder_description),
                checked = strikes,
                onChange = { enable ->
                    val apply = {
                        settings.strikeReminderEnabled = enable
                        strikes = enable
                        StrikeReminderScheduler.reschedule(context)
                    }
                    if (enable) withPermission(apply) else apply()
                }
            )
        }
        ToggleRow(
            title = stringResource(R.string.settings_punctuality_recording_title),
            description = stringResource(R.string.settings_punctuality_recording_description),
            checked = punctuality,
            onChange = { enable ->
                settings.punctualityRecordingEnabled = enable
                punctuality = enable
                PunctualityRecorderScheduler.reschedule(context)
            }
        )
        Text(stringResource(R.string.settings_live_updates_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (strikes && needsPermission(context)) {
            Text(stringResource(R.string.settings_live_updates_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
